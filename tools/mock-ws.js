#!/usr/bin/env node
/*
 * WebCast 发送端自测服务器（无第三方依赖，只用 Node 内置模块）
 * ------------------------------------------------------------
 * 作用：在本机模拟电视端，提供
 *   GET  /              发送页 index.html（app/assets/web/）
 *   GET  /app.js /style.css /worklet.js
 *   GET  /api/info      模拟设备信息
 *   GET  /health        ok
 *   GET  /ws            手写 WebSocket 服务端
 *   POST /__selftest    接收页面自测报告（app.js 里 ?selftest=1 时上报）
 *
 * 它会按 PROTOCOL.md 校验发送端的每一条消息（hello / hello-ack 响应 /
 * video-config / 8 字节视频头 + AVCC 负载 / audio-config / 4 字节音频头 /
 * need-keyframe 补 IDR / stop），并可选自动拉起无头 Chrome 跑端到端自测。
 *
 * 用法：
 *   node tools/mock-ws.js                        手动模式，用 Chrome 打开打印的地址
 *   node tools/mock-ws.js --chrome-test          自动拉起无头 Chrome（demo=1&auto=1&selftest=1）
 *   node tools/mock-ws.js --chrome-test --frames 60 --need-keyframe-at 1 --verbose
 *   node tools/mock-ws.js --chrome-test --audio          （演示模式 + 440Hz 测试音，验证音频链路）
 *   node tools/mock-ws.js --test-worklet                离线单测 worklet.js 的下混/16bit/20ms 分帧
 *   node tools/mock-ws.js --chrome-test --quality 720p   （验证 720p 编码参数）
 *   node tools/mock-ws.js --port 8899 --duration 30 --stop-after 8
 *
 * 选项：
 *   --port=8899            监听端口（0 = 随机）
 *   --host=127.0.0.1       监听地址
 *   --frames=45            判定通过所需视频帧数
 *   --duration=30          最长运行秒数（超时判失败）
 *   --stop-after=0         服务端在第 N 秒主动发 stop（0 = 由 --frames 触发）
 *   --need-keyframe-at=2.5 首次视频帧后第 N 秒发 need-keyframe（0 = 关闭）
 *   --chrome-test          自动启动无头 Chrome 并等待自测结果
 *   --chrome-bin=PATH      指定 Chrome/Chromium/Edge 可执行文件
 *   --verbose              打印每一帧/详细日志
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');

// ---------------------------------------------------------------------------
// 路径与常量
// ---------------------------------------------------------------------------
const ROOT = path.resolve(__dirname, '..');
const WEB_DIR = path.join(ROOT, 'app', 'assets', 'web');
const WORK_DIR = path.join(ROOT, 'work');
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const CODEC_WHITELIST = ['avc1.42E028', 'avc1.4D4028'];
const MAX_WIDTH = 1920;
const MAX_HEIGHT = 1080;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8'
};

const STATIC_FILES = {
  '/': 'index.html',
  '/index.html': 'index.html',
  '/app.js': 'app.js',
  '/style.css': 'style.css',
  '/worklet.js': 'worklet.js'
};

// ---------------------------------------------------------------------------
// 参数
// ---------------------------------------------------------------------------
function usage() {
  console.log('用法: node tools/mock-ws.js [--port=8899] [--frames=45] [--duration=30]' +
    ' [--stop-after=0] [--need-keyframe-at=2.5] [--chrome-test] [--audio] [--quality=720p]' +
    ' [--chrome-bin=PATH] [--verbose]');
  console.log('      node tools/mock-ws.js --test-worklet   离线单测 worklet.js（不启动服务器）');
}

function parseArgs(argv) {
  const opts = {
    port: 8899,
    host: '127.0.0.1',
    frames: 45,
    duration: 30,
    stopAfter: 0,
    needKeyframeAt: 2.5,
    chromeTest: false,
    audio: false,
    quality: '',
    chromeBin: '',
    verbose: false,
    testWorklet: false
  };
  for (let i = 0; i < argv.length; i++) {
    const raw = argv[i];
    if (raw === '--help' || raw === '-h') { usage(); process.exit(0); }
    if (raw === '--chrome-test') { opts.chromeTest = true; continue; }
    if (raw === '--audio') { opts.audio = true; continue; }
    if (raw === '--test-worklet') { opts.testWorklet = true; continue; }
    if (raw === '--verbose' || raw === '-v') { opts.verbose = true; continue; }
    let key = raw;
    let value = null;
    const eq = raw.indexOf('=');
    if (eq >= 0) {
      key = raw.slice(0, eq);
      value = raw.slice(eq + 1);
    } else if (i + 1 < argv.length && argv[i + 1].indexOf('--') !== 0) {
      value = argv[++i];
    }
    switch (key) {
      case '--port': opts.port = Number(value); break;
      case '--host': opts.host = value; break;
      case '--frames': opts.frames = Math.max(1, Number(value) || 45); break;
      case '--duration': opts.duration = Math.max(5, Number(value) || 30); break;
      case '--stop-after': opts.stopAfter = Number(value) || 0; break;
      case '--need-keyframe-at': opts.needKeyframeAt = Number(value) || 0; break;
      case '--chrome-bin': opts.chromeBin = value; break;
      case '--quality': opts.quality = value || ''; break;
      default:
        console.error('未知参数: ' + raw);
        usage();
        process.exit(2);
    }
  }
  return opts;
}

const opts = parseArgs(process.argv.slice(2));

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------
const ctx = {
  port: 0,
  startedAt: Date.now(),
  firstVideoAt: 0,
  errors: [],
  warnings: [],
  hello: null,
  ackSent: false,
  videoConfig: null,
  videoConfigValidity: null,
  videoFrames: 0,
  keyframes: 0,
  videoBytes: 0,
  nalCount: 0,
  lastPtsMs: -1,
  frameAt: [],
  needKeyframeSentAt: 0,
  needKeyframeSatisfied: false,
  audioConfig: null,
  audioFrames: 0,
  audioPayloadSizes: [],
  lastAudioSeq: -1,
  clientStopReceived: false,
  clientClosed: false,
  serverStopSentAt: 0,
  selftestReports: 0,
  lastSelftest: null,
  ws: null,
  timers: {},
  chrome: null,
  chromeExitCode: null,
  chromeLogTail: '',
  chromeProfileDir: '',
  finished: false
};

function log() {
  const args = Array.prototype.slice.call(arguments);
  args.unshift('[mock-ws]');
  console.log.apply(console, args);
}

function verbose() {
  if (!opts.verbose) return;
  const args = Array.prototype.slice.call(arguments);
  args.unshift('[mock-ws]');
  console.log.apply(console, args);
}

function fail(message) {
  if (ctx.errors.length < 50) ctx.errors.push(message);
  console.error('[mock-ws][协议错误] ' + message);
}

function warn(message) {
  if (ctx.warnings.length < 50) ctx.warnings.push(message);
  console.warn('[mock-ws][警告] ' + message);
}

function setTimer(name, fn, ms) {
  clearTimer(name);
  ctx.timers[name] = setTimeout(fn, ms);
}

function clearTimer(name) {
  if (ctx.timers[name]) {
    clearTimeout(ctx.timers[name]);
    clearInterval(ctx.timers[name]);
    delete ctx.timers[name];
  }
}

function clearAllTimers() {
  Object.keys(ctx.timers).forEach(clearTimer);
}

// ---------------------------------------------------------------------------
// HTTP
// ---------------------------------------------------------------------------
function infoJson() {
  return {
    name: 'WebCast 模拟电视',
    model: 'Mock TV (tools/mock-ws.js)',
    version: '1.0.0',
    protocol: 1,
    ip: opts.host,
    port: ctx.port,
    width: 1920,
    height: 1080,
    fps: 50,
    audio: true,
    codecs: CODEC_WHITELIST.slice(),
    maxWidth: MAX_WIDTH,
    maxHeight: MAX_HEIGHT
  };
}

function sendBody(res, status, contentType, body) {
  res.writeHead(status, {
    'Content-Type': contentType,
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

function sendFile(res, filePath) {
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found');
      return;
    }
    const ext = path.extname(filePath).toLowerCase();
    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Cache-Control': 'no-store'
    });
    res.end(data);
  });
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('请求体过大'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function onSelfTestReport(report) {
  ctx.selftestReports++;
  ctx.lastSelftest = report;
  if (opts.verbose || report.stage !== 'tick') {
    const compact = {
      stage: report.stage,
      phase: report.phase,
      status: report.status,
      codec: report.codec,
      size: report.width && report.height ? report.width + 'x' + report.height : null,
      bitrate: report.bitrate,
      videoConfigSent: report.videoConfigSent,
      audioConfigSent: report.audioConfigSent,
      frames: report.frames,
      keyframes: report.keyframes,
      bytes: report.bytes,
      audioFrames: report.audioFrames,
      skipped: report.skipped,
      error: report.error
    };
    log('页面自测报告: ' + JSON.stringify(compact));
  }
  if (report.stage === 'error' && opts.chromeTest) {
    finishSoon(300);
  } else if (report.stage === 'stop' && opts.chromeTest && ctx.serverStopSentAt) {
    finishSoon(150);
  }
}

function handleHttp(req, res) {
  const url = new URL(req.url, 'http://localhost');
  let pathname = url.pathname;
  if (opts.verbose && pathname !== '/__selftest') log('HTTP ' + req.method + ' ' + pathname);
  try { pathname = decodeURIComponent(pathname); } catch (e) { /* keep raw */ }

  if (req.method === 'POST' && pathname === '/__selftest') {
    readBody(req, 1024 * 1024).then((text) => {
      try {
        const report = JSON.parse(text);
        if (report && typeof report === 'object') onSelfTestReport(report);
      } catch (e) {
        warn('收到无法解析的 /__selftest 报告');
      }
    }).catch((err) => warn('读取 /__selftest 失败: ' + err.message));
    res.writeHead(204, { 'Cache-Control': 'no-store' });
    res.end();
    return;
  }

  if (req.method !== 'GET' && req.method !== 'HEAD') {
    sendBody(res, 405, 'text/plain; charset=utf-8', '405 Method Not Allowed');
    return;
  }

  if (pathname === '/api/info') {
    sendBody(res, 200, 'application/json; charset=utf-8', JSON.stringify(infoJson(), null, 2));
    return;
  }
  if (pathname === '/health') {
    sendBody(res, 200, 'text/plain; charset=utf-8', 'ok');
    return;
  }
  if (pathname === '/favicon.ico') {
    res.writeHead(204);
    res.end();
    return;
  }
  const fileName = STATIC_FILES[pathname];
  if (fileName) {
    sendFile(res, path.join(WEB_DIR, fileName));
    return;
  }
  sendBody(res, 404, 'text/plain; charset=utf-8', '404 Not Found');
}

// ---------------------------------------------------------------------------
// 极简 WebSocket 服务端（RFC 6455 子集：文本/二进制/ping/pong/close/分片）
// ---------------------------------------------------------------------------
function encodeFrame(opcode, payload) {
  const len = payload.length;
  let header;
  if (len < 126) {
    header = Buffer.alloc(2);
    header[1] = len;
  } else if (len < 65536) {
    header = Buffer.alloc(4);
    header[1] = 126;
    header.writeUInt16BE(len, 2);
  } else {
    header = Buffer.alloc(10);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(len), 2);
  }
  header[0] = 0x80 | opcode;
  return Buffer.concat([header, payload]);
}

function sendText(msg) {
  const conn = ctx.ws;
  if (!conn || conn.closed) return false;
  try {
    conn.socket.write(encodeFrame(0x1, Buffer.from(JSON.stringify(msg), 'utf8')));
    return true;
  } catch (e) {
    return false;
  }
}

function sendFrame(socket, opcode, payload) {
  try { socket.write(encodeFrame(opcode, payload)); } catch (e) { /* ignore */ }
}

function tryParseFrame(buffer) {
  if (buffer.length < 2) return null;
  const b0 = buffer[0];
  const b1 = buffer[1];
  const fin = (b0 & 0x80) !== 0;
  const opcode = b0 & 0x0f;
  const masked = (b1 & 0x80) !== 0;
  let len = b1 & 0x7f;
  let offset = 2;
  if (len === 126) {
    if (buffer.length < 4) return null;
    len = buffer.readUInt16BE(2);
    offset = 4;
  } else if (len === 127) {
    if (buffer.length < 10) return null;
    const big = buffer.readBigUInt64BE(2);
    if (big > 64n * 1024n * 1024n) throw new Error('WebSocket 帧过大');
    len = Number(big);
    offset = 10;
  }
  if (masked) {
    if (buffer.length < offset + 4) return null;
  }
  const total = offset + (masked ? 4 : 0) + len;
  if (buffer.length < total) return null;
  let payload;
  if (masked) {
    const mask = buffer.subarray(offset, offset + 4);
    offset += 4;
    payload = Buffer.allocUnsafe(len);
    for (let i = 0; i < len; i++) payload[i] = buffer[offset + i] ^ mask[i & 3];
  } else {
    payload = buffer.subarray(offset, offset + len);
  }
  return { fin, opcode, payload, consumed: total };
}

function attachConnection(socket) {
  const conn = {
    socket,
    buffer: Buffer.alloc(0),
    fragments: [],
    fragmentOpcode: 0,
    closed: false
  };
  ctx.ws = conn;

  socket.on('data', (chunk) => {
    conn.buffer = Buffer.concat([conn.buffer, chunk]);
    drainFrames(conn);
  });
  socket.on('error', () => { /* 客户端断开 */ });
  socket.on('close', () => onClientClose(conn));
  log('WebSocket 已连接');
}

function drainFrames(conn) {
  while (!conn.closed) {
    let frame = null;
    try {
      frame = tryParseFrame(conn.buffer);
    } catch (err) {
      fail('WebSocket 帧解析失败: ' + err.message);
      try { conn.socket.destroy(); } catch (e) {}
      return;
    }
    if (!frame) return;
    conn.buffer = conn.buffer.subarray(frame.consumed);
    handleFrame(conn, frame);
  }
}

function handleFrame(conn, frame) {
  if (frame.opcode === 0x8) {
    sendFrame(conn.socket, 0x8, Buffer.alloc(0));
    conn.closed = true;
    try { conn.socket.end(); } catch (e) {}
    onClientClose(conn);
    return;
  }
  if (frame.opcode === 0x9) {
    sendFrame(conn.socket, 0xA, frame.payload);
    return;
  }
  if (frame.opcode === 0xA) return;
  if (frame.opcode === 0x1 || frame.opcode === 0x2) {
    if (!frame.fin) {
      conn.fragmentOpcode = frame.opcode;
      conn.fragments = [frame.payload];
      return;
    }
    dispatchMessage(frame.opcode, frame.payload);
    return;
  }
  if (frame.opcode === 0x0) {
    conn.fragments.push(frame.payload);
    if (frame.fin) {
      const payload = Buffer.concat(conn.fragments);
      const opcode = conn.fragmentOpcode;
      conn.fragments = [];
      conn.fragmentOpcode = 0;
      dispatchMessage(opcode, payload);
    }
    return;
  }
  warn('忽略不支持的 WebSocket opcode: ' + frame.opcode);
}

function dispatchMessage(opcode, payload) {
  if (opcode === 0x1) onText(payload.toString('utf8'));
  else onBinary(new Uint8Array(payload));
}

function onClientClose(conn) {
  if (conn && conn.closed !== true) conn.closed = true;
  if (ctx.clientClosed) return;
  ctx.clientClosed = true;
  log('WebSocket 已断开');
  if (ctx.finished) return;
  if (opts.chromeTest) finishSoon(2000);
  else finalize('客户端断开');
}

function onText(text) {
  let msg;
  try {
    msg = JSON.parse(text);
  } catch (err) {
    fail('收到无法解析的文本消息');
    return;
  }
  if (!msg || typeof msg.t !== 'string') {
    fail('文本消息缺少 t 字段');
    return;
  }
  if (!ctx.hello && msg.t !== 'hello') {
    fail('第一条消息不是 hello，而是 ' + msg.t);
  }

  switch (msg.t) {
    case 'hello':
      onHello(msg);
      break;
    case 'video-config':
      onVideoConfig(msg);
      break;
    case 'audio-config':
      onAudioConfig(msg);
      break;
    case 'stop':
      ctx.clientStopReceived = true;
      log('收到客户端 stop');
      if (!opts.chromeTest) finishSoon(150);
      break;
    default:
      warn('收到未知文本消息类型: ' + msg.t);
  }
}

function onHello(msg) {
  if (ctx.hello) { fail('重复的 hello'); return; }
  if (msg.v !== 1) fail('hello.v 应为 1，实际为 ' + msg.v);
  if (typeof msg.ua !== 'string' || !msg.ua) fail('hello.ua 缺失或不是字符串');
  ctx.hello = msg;
  ctx.ackSent = true;
  sendText({
    t: 'hello-ack',
    v: 1,
    name: 'WebCast 模拟电视',
    model: 'Mock TV (tools/mock-ws.js)',
    width: 1920,
    height: 1080,
    maxWidth: MAX_WIDTH,
    maxHeight: MAX_HEIGHT,
    fps: 50,
    audio: true,
    codecs: CODEC_WHITELIST.slice()
  });
  log('收到 hello，已回 hello-ack');
  startStats();
  setTimer('needKeyframe', () => {
    if (ctx.videoFrames < 1 || !ctx.ws || ctx.ws.closed) return;
    ctx.needKeyframeSentAt = Date.now();
    sendText({ t: 'need-keyframe' });
    log('已发送 need-keyframe，等待客户端补发 IDR');
    setTimer('needKeyframeCheck', () => {
      if (!ctx.needKeyframeSatisfied) fail('发送 need-keyframe 后 3 秒内没有收到关键帧（IDR）');
    }, 3000);
  }, Math.max(500, opts.needKeyframeAt * 1000));
}

function startStats() {
  let lastFrames = 0;
  let lastBytes = 0;
  const started = Date.now();
  ctx.timers.stats = setInterval(() => {
    const elapsed = Math.max(1, (Date.now() - started) / 1000);
    const fps = Math.max(0, (ctx.videoFrames - lastFrames) / elapsed);
    const kbps = Math.max(0, ((ctx.videoBytes - lastBytes) * 8) / elapsed / 1000);
    lastFrames = ctx.videoFrames;
    lastBytes = ctx.videoBytes;
    const drops = Math.max(0, ctx.keyframes - 1);
    sendText({ t: 'stats', fps: Math.round(fps * 10) / 10, kbps: Math.round(kbps), decodeMs: 12.3, drops: drops });
    verbose('已发送 stats: fps=' + Math.round(fps * 10) / 10 + ' kbps=' + Math.round(kbps));
    if (opts.stopAfter > 0 && !ctx.serverStopSentAt && (Date.now() - ctx.startedAt) / 1000 >= opts.stopAfter) {
      sendServerStop();
    }
  }, 1000);
}

function sendServerStop() {
  if (ctx.serverStopSentAt) return;
  ctx.serverStopSentAt = Date.now();
  const ok = sendText({ t: 'stop' });
  log('已向客户端发送 stop' + (ok ? '' : '（发送失败）'));
  setTimer('stopFallback', () => finishSoon(0), 5000);
}

// ---------------------------------------------------------------------------
// 协议校验
// ---------------------------------------------------------------------------
function onBinary(bytes) {
  if (!ctx.hello) {
    fail('hello 之前收到二进制帧');
    return;
  }
  if (bytes.length < 1) {
    fail('收到空二进制帧');
    return;
  }
  const type = bytes[0];
  if (type === 0x01) onVideoFrame(bytes);
  else if (type === 0x02) onAudioFrame(bytes);
  else fail('未知二进制媒体类型: 0x' + type.toString(16));
}

function onVideoConfig(msg) {
  if (ctx.videoFrames > 0) fail('video-config 晚于第一个视频帧');
  if (ctx.videoConfig) { fail('重复的 video-config'); return; }

  const record = {
    codec: msg.codec,
    width: Number(msg.width),
    height: Number(msg.height),
    descBytes: 0,
    valid: true
  };
  ctx.videoConfig = record;

  if (CODEC_WHITELIST.indexOf(msg.codec) < 0) {
    record.valid = false;
    fail('video-config.codec 不在白名单（Baseline/Main H.264）: ' + msg.codec);
  }
  if (!(record.width > 0 && record.height > 0)) {
    record.valid = false;
    fail('video-config 尺寸非法: ' + msg.width + 'x' + msg.height);
  }
  if (record.width > MAX_WIDTH || record.height > MAX_HEIGHT) {
    record.valid = false;
    fail('video-config 尺寸超过电视上限 1920x1080: ' + record.width + 'x' + record.height);
  }
  if (msg.fps != null && Number(msg.fps) !== 30) warn('video-config.fps 为 ' + msg.fps + '（预期 30）');
  if (typeof msg.desc !== 'string' || !msg.desc) {
    record.valid = false;
    fail('video-config.desc 缺失');
    return;
  }

  let desc;
  try {
    desc = Buffer.from(msg.desc, 'base64');
  } catch (err) {
    record.valid = false;
    fail('video-config.desc 不是合法 base64');
    return;
  }
  record.descBytes = desc.length;
  if (desc.length < 6) {
    record.valid = false;
    fail('video-config.desc 太短，不是合法 avcC（' + desc.length + ' 字节）');
    return;
  }
  if (desc[0] !== 1) {
    record.valid = false;
    fail('video-config.desc 首字节应为 1（avcC configurationVersion），实际 ' + desc[0]);
  }
  if ((desc[4] & 0x03) !== 3) {
    record.valid = false;
    fail('avcC lengthSizeMinusOne 应为 3（AVCC 4 字节长度前缀），实际 ' + (desc[4] & 0x03));
  }
  const numSps = desc[5] & 0x1f;
  if (numSps < 1) {
    record.valid = false;
    fail('avcC 中没有 SPS');
  } else {
    let offset = 6;
    let spsOk = false;
    let ppsOk = false;
    if (offset + 2 <= desc.length) {
      const spsLen = desc.readUInt16BE(offset);
      offset += 2;
      if (spsLen > 0 && offset + spsLen <= desc.length) {
        spsOk = true;
        offset += spsLen;
        if (offset < desc.length) {
          const numPps = desc[offset];
          offset += 1;
          if (numPps >= 1) {
            if (offset + 2 <= desc.length) {
              const ppsLen = desc.readUInt16BE(offset);
              offset += 2;
              if (ppsLen > 0 && offset + ppsLen <= desc.length) ppsOk = true;
            }
          } else {
            record.valid = false;
            fail('avcC 中没有 PPS');
          }
        }
      }
    }
    if (!spsOk) { record.valid = false; fail('avcC 中 SPS 解析失败'); }
    if (numSps > 0 && !ppsOk) { record.valid = false; fail('avcC 中 PPS 解析失败'); }
  }

  log('收到 video-config: ' + record.codec + ' ' + record.width + 'x' + record.height +
    '，avcC ' + record.descBytes + ' 字节' + (record.valid ? '' : '（校验失败）'));
}

function onVideoFrame(bytes) {
  if (!ctx.videoConfig) {
    fail('视频帧早于 video-config（协议要求先发 video-config）');
    return;
  }
  if (bytes.length < 9) {
    fail('视频帧长度过短: ' + bytes.length + ' 字节（至少 8 字节头 + 负载）');
    return;
  }
  if (bytes[0] !== 0x01) fail('视频帧媒体类型应为 0x01，实际 0x' + bytes[0].toString(16));
  const key = (bytes[1] & 0x01) === 1;
  const reserved = (bytes[2] << 8) | bytes[3];
  if (reserved !== 0) fail('视频帧头 reserved 字段应为 0，实际 ' + reserved);

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const ptsMs = view.getUint32(4, false);
  if (ctx.lastPtsMs >= 0 && ptsMs < ctx.lastPtsMs) {
    fail('视频 ptsMs 回退: ' + ctx.lastPtsMs + ' -> ' + ptsMs);
  }
  ctx.lastPtsMs = ptsMs;

  // 解析 AVCC：每个 NAL = u32 长度 + NAL 字节，必须正好铺满负载
  let offset = 8;
  let nalCount = 0;
  let payloadOk = true;
  while (offset < bytes.length) {
    if (offset + 4 > bytes.length) { payloadOk = false; break; }
    const nalLen = view.getUint32(offset, false);
    offset += 4;
    if (nalLen <= 0) { payloadOk = false; break; }
    if (offset + nalLen > bytes.length) { payloadOk = false; break; }
    offset += nalLen;
    nalCount++;
  }
  if (!payloadOk) fail('AVCC 负载长度不一致（第 ' + (ctx.videoFrames + 1) + ' 帧）');
  if (nalCount === 0) fail('视频帧中没有 NAL 单元');
  ctx.nalCount += nalCount;

  if (ctx.videoFrames === 0) {
    if (!key) fail('第一个视频帧不是关键帧（flags bit0 应为 1）');
    if (ctx.videoConfig.width !== MAX_WIDTH || ctx.videoConfig.height !== MAX_HEIGHT) {
      verbose('编码分辨率 ' + ctx.videoConfig.width + 'x' + ctx.videoConfig.height);
    }
    ctx.firstVideoAt = Date.now();
  }

  if (ctx.needKeyframeSentAt && !ctx.needKeyframeSatisfied && key) {
    ctx.needKeyframeSatisfied = true;
    log('need-keyframe 后收到 IDR（延迟 ' + (Date.now() - ctx.needKeyframeSentAt) + ' ms）');
  }

  ctx.videoFrames++;
  if (key) ctx.keyframes++;
  ctx.videoBytes += bytes.length;
  ctx.frameAt.push(Date.now());
  if (ctx.frameAt.length > 200) ctx.frameAt.shift();

  verbose('视频帧 #' + ctx.videoFrames + ' key=' + key + ' pts=' + ptsMs +
    ' bytes=' + bytes.length + ' nals=' + nalCount);

  if (opts.stopAfter === 0 && !ctx.serverStopSentAt && ctx.videoFrames >= opts.frames && opts.chromeTest) {
    sendServerStop();
  }
}

function onAudioConfig(msg) {
  if (ctx.audioFrames > 0) fail('audio-config 晚于第一个音频帧');
  if (ctx.audioConfig) { fail('重复的 audio-config'); return; }
  const record = {
    format: msg.format,
    rate: Number(msg.rate),
    channels: Number(msg.channels),
    valid: true
  };
  ctx.audioConfig = record;
  if (msg.format !== 's16le') { record.valid = false; fail('audio-config.format 应为 s16le，实际 ' + msg.format); }
  if (record.rate !== 48000) { record.valid = false; fail('audio-config.rate 应为 48000，实际 ' + msg.rate); }
  if (record.channels !== 2) { record.valid = false; fail('audio-config.channels 应为 2，实际 ' + msg.channels); }
  log('收到 audio-config: ' + record.format + ' ' + record.rate + 'Hz ' + record.channels + 'ch');
}

function onAudioFrame(bytes) {
  if (!ctx.audioConfig) { fail('音频帧早于 audio-config'); return; }
  if (bytes.length < 5) { fail('音频帧长度过短: ' + bytes.length); return; }
  if (bytes[0] !== 0x02) fail('音频帧媒体类型应为 0x02，实际 0x' + bytes[0].toString(16));
  if (bytes[1] !== 0) fail('音频帧头 reserved 字节应为 0，实际 ' + bytes[1]);
  const seq = (bytes[2] << 8) | bytes[3];
  if (ctx.lastAudioSeq >= 0 && seq !== ((ctx.lastAudioSeq + 1) & 0xffff)) {
    fail('音频 seq 不连续: ' + ctx.lastAudioSeq + ' -> ' + seq);
  }
  ctx.lastAudioSeq = seq;
  const payloadLen = bytes.length - 4;
  if (payloadLen % 4 !== 0) fail('音频负载不是 16bit 对齐: ' + payloadLen + ' 字节');
  // 20ms @48kHz 立体声 s16le = 960 样本/声道 x 2ch x 2B = 3840 字节
  if (ctx.audioPayloadSizes.indexOf(payloadLen) < 0) ctx.audioPayloadSizes.push(payloadLen);
  if (payloadLen !== 3840) {
    warn('音频负载 ' + payloadLen + ' 字节（约定 20ms = 3840 字节，见 PROTOCOL.md 音频说明）');
  }
  ctx.audioFrames++;
  verbose('音频帧 #' + ctx.audioFrames + ' seq=' + seq + ' bytes=' + bytes.length);
}

// ---------------------------------------------------------------------------
// worklet.js 离线单测（用 vm 模拟 AudioWorkletGlobalScope）
// ---------------------------------------------------------------------------
function runWorkletTest() {
  const vm = require('vm');
  const results = [];
  const check = (name, ok, detail) => {
    results.push({ name, ok, detail: detail || '' });
    log((ok ? 'PASS ' : 'FAIL ') + name + (detail ? '  [' + detail + ']' : ''));
  };

  let registered = null;
  const messages = [];
  class FakeAudioWorkletProcessor {
    constructor() {
      this.port = {
        onmessage: null,
        postMessage: (data) => { messages.push(data); }
      };
    }
  }
  const sandbox = {
    sampleRate: 48000,
    AudioWorkletProcessor: FakeAudioWorkletProcessor,
    registerProcessor: (name, cls) => { registered = { name, cls }; },
    console
  };
  vm.createContext(sandbox);
  try {
    vm.runInContext(fs.readFileSync(path.join(WEB_DIR, 'worklet.js'), 'utf8'), sandbox, { filename: 'worklet.js' });
  } catch (err) {
    check('worklet.js 可在 AudioWorkletGlobalScope 中加载', false, err.message);
    return results;
  }
  check('注册处理器 pcm-sender', !!registered && registered.name === 'pcm-sender', registered ? registered.name : '未注册');
  if (!registered) return results;

  const createProc = () => new registered.cls({ processorOptions: { frameMs: 20 } });
  const BLOCK = 128;
  const feedBlocks = (proc, inputs, blocks) => {
    const outputs = [[new Float32Array(BLOCK), new Float32Array(BLOCK)]];
    for (let b = 0; b < blocks; b++) proc.process([inputs], [outputs]);
  };
  const makeChannels = (n, value) => {
    const channels = [];
    for (let c = 0; c < n; c++) {
      const channel = new Float32Array(BLOCK);
      channel.fill(Array.isArray(value) ? value[c] : value);
      channels.push(channel);
    }
    return channels;
  };

  // 8 个 128 帧渲染块 = 1024 帧 → 产出 1 个完整 20ms 帧（960 帧/声道，余 64 帧留待下次）
  let proc = createProc();
  feedBlocks(proc, makeChannels(2, [0.5, -0.5]), 8);
  check('20ms 分帧（1024 帧渲染 → 1 个完整 20ms 帧）', messages.length === 1, messages.length + ' 帧');
  const sizes = new Set(messages.map((m) => m.byteLength));
  check('每帧 3840 字节（960 样本/声道 × 2ch × s16le）', sizes.size === 1 && sizes.has(3840),
    Array.from(sizes).join('/') + ' 字节');
  const totalSamples = messages.reduce((n, m) => n + m.length, 0);
  check('样本数（960 帧/声道 × 2ch = 1920 个 int16）', totalSamples === 1920, String(totalSamples));
  const first = messages[0];
  check('立体声交织：L=0.5→16383，R=-0.5→-16384',
    first[0] === 16383 && first[1] === -16384, first[0] + ',' + first[1]);

  // 单声道上混到左右声道 + 正负削波
  messages.length = 0;
  proc = createProc();
  feedBlocks(proc, makeChannels(1, 1.5), 8);
  const clippedPos = messages[0];
  messages.length = 0;
  proc = createProc();
  feedBlocks(proc, makeChannels(1, -1.5), 8);
  const clippedNeg = messages[0];
  check('单声道上混 + 削波（+1.5→32767 左右相同，-1.5→-32768）',
    !!clippedPos && !!clippedNeg &&
    clippedPos[0] === 32767 && clippedPos[1] === 32767 &&
    clippedNeg[0] === -32768 && clippedNeg[1] === -32768,
    (clippedPos ? clippedPos[0] + ',' + clippedPos[1] : '无') + ' / ' +
    (clippedNeg ? clippedNeg[0] + ',' + clippedNeg[1] : '无'));

  // 4 声道下混：L=(1.0+0.25)/2=0.625→20479，R=(0.5+0.0)/2=0.25→8191
  messages.length = 0;
  proc = createProc();
  feedBlocks(proc, makeChannels(4, [1, 0.5, 0.25, 0]), 8);
  const mixed = messages[0];
  check('4 声道下混（L=20479，R=8191）',
    !!mixed && mixed[0] === 20479 && mixed[1] === 8191,
    mixed ? mixed[0] + ',' + mixed[1] : '无');

  // close 后不再产帧
  messages.length = 0;
  proc = createProc();
  feedBlocks(proc, makeChannels(2, 0.1), 8);
  const before = messages.length;
  if (proc.port.onmessage) proc.port.onmessage({ data: 'close' });
  feedBlocks(proc, makeChannels(2, 0.1), 8);
  check('收到 close 后停止产帧', messages.length === before, before + ' -> ' + messages.length);

  return results;
}

// ---------------------------------------------------------------------------
// 结果
// ---------------------------------------------------------------------------
function finishSoon(delay) {
  if (ctx.finished) return;
  if (ctx.finishScheduled) return;
  ctx.finishScheduled = true;
  setTimer('finish', () => finalize('测试结束'), Math.max(0, delay || 0));
}

function killChrome() {
  const child = ctx.chrome;
  if (!child || child.killed) return;
  try { process.kill(-child.pid, 'SIGKILL'); } catch (e) {
    try { child.kill('SIGKILL'); } catch (e2) {}
  }
  ctx.chrome = null;
  const profileDir = ctx.chromeProfileDir;
  if (profileDir && profileDir.indexOf(WORK_DIR + path.sep) === 0) {
    setTimer('cleanProfile', () => {
      try { fs.rmSync(profileDir, { recursive: true, force: true }); } catch (e) {}
    }, 1500);
  }
}

function finalize(reason) {
  if (ctx.finished) return;
  ctx.finished = true;
  clearAllTimers();
  killChrome();

  const durationSec = Math.max(0.001, (Date.now() - ctx.startedAt) / 1000);
  const avgFps = ctx.videoFrames / durationSec;
  const mbps = (ctx.videoBytes * 8) / durationSec / 1000 / 1000;

  const problems = ctx.errors.slice();
  if (!ctx.hello) problems.push('未收到 hello');
  if (!ctx.ackSent) problems.push('未回复 hello-ack');
  if (!ctx.videoConfig) problems.push('未收到 video-config');
  else if (ctx.videoConfig.valid === false) problems.push('video-config 内容校验失败');
  if (ctx.videoFrames === 0) problems.push('未收到视频帧');
  if (ctx.videoFrames > 0 && ctx.videoFrames < opts.frames) {
    problems.push('视频帧不足: ' + ctx.videoFrames + ' < ' + opts.frames);
  }
  if (ctx.videoFrames > 0 && ctx.keyframes < 2) {
    problems.push('关键帧不足: ' + ctx.keyframes + '（期望至少 2：起始关键帧 + 周期/need-keyframe 补发）');
  }
  if (ctx.needKeyframeSentAt && !ctx.needKeyframeSatisfied) {
    problems.push('收到 need-keyframe 后未补发 IDR');
  }
  if (ctx.serverStopSentAt && !ctx.clientStopReceived) {
    problems.push('服务端 stop 后客户端未发送 stop 消息');
  }
  if (ctx.audioFrames > 0 && !ctx.audioConfig) problems.push('有音频帧但缺少 audio-config');
  if (opts.audio) {
    if (!ctx.audioConfig) problems.push('--audio 模式下未收到 audio-config');
    else if (ctx.audioFrames < 10) problems.push('--audio 模式下音频帧不足: ' + ctx.audioFrames + ' < 10');
    if (ctx.audioFrames > 0 && ctx.audioPayloadSizes.length > 0 &&
      !(ctx.audioPayloadSizes.length === 1 && ctx.audioPayloadSizes[0] === 3840)) {
      problems.push('音频帧负载不是 20ms（3840 字节），实际: ' + ctx.audioPayloadSizes.join('/'));
    }
  }
  const report = ctx.lastSelftest;
  if (report) {
    if (report.error) problems.push('页面报告错误: ' + report.error);
    if (!report.videoConfigSent) problems.push('页面状态显示 videoConfigSent=false');
    if (typeof report.frames === 'number' && report.frames < Math.min(10, opts.frames)) {
      problems.push('页面只发送了 ' + report.frames + ' 帧');
    }
  } else if (opts.chromeTest) {
    problems.push('未收到页面 selftest 报告（?selftest=1）');
  }

  log('===== WebCast 发送端自测结果 =====');
  log('结束原因      : ' + reason);
  log('hello         : ' + (ctx.hello ? 'OK (v=' + ctx.hello.v + ', ua=' + String(ctx.hello.ua).slice(0, 60) + ')' : '缺失'));
  log('hello-ack     : ' + (ctx.ackSent ? '已发送' : '未发送'));
  log('video-config  : ' + (ctx.videoConfig
    ? (ctx.videoConfig.codec + ' ' + ctx.videoConfig.width + 'x' + ctx.videoConfig.height +
      '，avcC ' + ctx.videoConfig.descBytes + ' 字节')
    : '缺失'));
  log('视频帧        : ' + ctx.videoFrames + ' 帧，关键帧 ' + ctx.keyframes + ' 个，' +
    (ctx.videoBytes / 1024).toFixed(1) + ' KB，平均 ' + avgFps.toFixed(1) + ' fps / ' +
    mbps.toFixed(2) + ' Mbps，NAL ' + ctx.nalCount + ' 个');
  log('need-keyframe : ' + (ctx.needKeyframeSentAt
    ? (ctx.needKeyframeSatisfied ? '已发送，并收到 IDR' : '已发送，未收到 IDR')
    : '未发送'));
  log('音频          : ' + (opts.audio ? '[要求] ' : '') + (ctx.audioFrames > 0
    ? ctx.audioFrames + ' 帧（' + (ctx.audioConfig ? ctx.audioConfig.format + '/' + ctx.audioConfig.rate + 'Hz/' + ctx.audioConfig.channels + 'ch' : '配置缺失') +
      '，负载 ' + ctx.audioPayloadSizes.join('/') + ' 字节）'
    : (ctx.audioConfig ? '已收到配置，无音频帧' : '未启用')));
  log('客户端 stop   : ' + (ctx.clientStopReceived ? '已收到' : '未收到'));
  if (report) {
    log('页面自测      : frames=' + report.frames + '，keyframes=' + report.keyframes +
      '，codec=' + report.codec + '，status=' + report.status + '，error=' + (report.error || '无'));
  }
  if (ctx.warnings.length) log('警告          : ' + ctx.warnings.join(' | '));
  if (problems.length) {
    log('协议/结果错误 :');
    problems.forEach((p) => log('  - ' + p));
  } else {
    log('协议/结果错误 : 无');
  }
  log('结果          : ' + (problems.length ? 'FAIL' : 'PASS'));
  log('==================================');
  if (problems.length && opts.chromeTest && ctx.chromeLogTail) {
    console.error('[mock-ws] Chrome 日志尾部:\n' + ctx.chromeLogTail.slice(-4000));
  }

  if (ctx.ws && !ctx.ws.closed) {
    try { ctx.ws.socket.destroy(); } catch (e) {}
  }
  try { server.close(); } catch (e) {}

  setTimeout(() => process.exit(problems.length ? 1 : 0), 120);
}

// ---------------------------------------------------------------------------
// 无头 Chrome 自测
// ---------------------------------------------------------------------------
function findChrome() {
  const candidates = [
    opts.chromeBin,
    process.env.CHROME_BIN,
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser'
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      if (fs.existsSync(candidate)) return candidate;
    } catch (e) { /* ignore */ }
  }
  return '';
}

function launchChrome() {
  const chromeBin = findChrome();
  if (!chromeBin) {
    fail('找不到 Chrome/Chromium/Edge，可用 --chrome-bin=PATH 指定');
    finalize('找不到浏览器');
    return;
  }
  fs.mkdirSync(WORK_DIR, { recursive: true });
  const profileDir = path.join(WORK_DIR, 'web-selftest-' + process.pid + '-' + ctx.port);
  ctx.chromeProfileDir = profileDir;
  try { fs.rmSync(profileDir, { recursive: true, force: true }); } catch (e) {}

  let query = '?demo=1&auto=1&selftest=1';
  // 无头 Chrome 环境无法完成假麦克风授权，用页面内置 440Hz 测试音验证音频链路
  if (opts.audio) query += '&sound=tone';
  if (opts.quality) query += '&quality=' + encodeURIComponent(opts.quality);
  const url = 'http://' + opts.host + ':' + ctx.port + '/' + query;
  const args = [
    '--headless=new',
    '--no-sandbox',
    '--disable-gpu',
    '--ignore-certificate-errors',
    '--autoplay-policy=no-user-gesture-required',
    '--disable-background-timer-throttling',
    '--disable-backgrounding-occluded-windows',
    '--disable-renderer-backgrounding',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-extensions',
    '--mute-audio',
    '--user-data-dir=' + profileDir
  ];
  if (opts.audio) {
    // 真实 Chrome 手动测试时可用假设备；无头环境仍用页面内置 sound=tone 音源
    args.push('--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream');
  }
  args.push(url);
  log('启动无头 Chrome: ' + chromeBin);
  log('测试地址: ' + url);
  const child = spawn(chromeBin, args, { detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
  ctx.chrome = child;

  const collect = (chunk) => {
    ctx.chromeLogTail = (ctx.chromeLogTail + chunk.toString('utf8')).slice(-20000);
  };
  if (child.stdout) child.stdout.on('data', collect);
  if (child.stderr) child.stderr.on('data', collect);

  child.on('error', (err) => {
    fail('Chrome 启动失败: ' + err.message);
    finishSoon(0);
  });
  child.on('exit', (code, signal) => {
    ctx.chromeExitCode = code;
    if (!ctx.finished) {
      fail('Chrome 提前退出（code=' + code + ', signal=' + signal + '）');
      if (ctx.chromeLogTail) {
        console.error('[mock-ws] Chrome 日志尾部:\n' + ctx.chromeLogTail.slice(-4000));
      }
      finishSoon(0);
    }
  });

  log('等待页面连接（最长 ' + opts.duration + ' 秒）…');
}

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------
if (opts.testWorklet) {
  const results = runWorkletTest();
  const failed = results.filter((r) => !r.ok);
  log('===== worklet.js 单测结果 =====');
  log('用例: ' + results.length + '，通过: ' + (results.length - failed.length) + '，失败: ' + failed.length);
  log('结果: ' + (failed.length ? 'FAIL' : 'PASS'));
  process.exit(failed.length ? 1 : 0);
}

const server = http.createServer(handleHttp);

server.on('upgrade', (req, socket) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname !== '/ws') {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
    socket.destroy();
    return;
  }
  const key = req.headers['sec-websocket-key'];
  if (!key) {
    socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
    socket.destroy();
    return;
  }
  if (ctx.ws) {
    // 只保留最新连接（手动测试时刷新页面会重连）
    try { ctx.ws.socket.destroy(); } catch (e) {}
    ctx.ws = null;
    ctx.clientClosed = false;
  }
  const accept = crypto.createHash('sha1').update(key + WS_GUID).digest('base64');
  socket.write(
    'HTTP/1.1 101 Switching Protocols\r\n' +
    'Upgrade: websocket\r\n' +
    'Connection: Upgrade\r\n' +
    'Sec-WebSocket-Accept: ' + accept + '\r\n\r\n'
  );
  attachConnection(socket);
});

server.on('error', (err) => {
  console.error('[mock-ws] 服务器错误: ' + err.message);
  process.exit(1);
});

server.listen(opts.port, opts.host, () => {
  ctx.port = server.address().port;
  log('监听 http://' + opts.host + ':' + ctx.port + '（静态目录 ' + WEB_DIR + '）');
  setTimer('duration', () => {
    fail('运行超时（' + opts.duration + ' 秒）');
    finalize('超时');
  }, opts.duration * 1000);

  if (opts.chromeTest) {
    launchChrome();
  } else {
    log('手动自测：用 Chrome 打开 http://' + opts.host + ':' + ctx.port + '/?demo=1&auto=1&selftest=1');
    log('（localhost 属于安全上下文，无需 HTTPS；真实电视请直接用 https://<tv-ip>:8443/）');
    log('等待发送端连接…（Ctrl+C 退出）');
  }
});

process.on('SIGINT', () => {
  log('收到 SIGINT，退出');
  if (!ctx.finished) finalize('SIGINT');
  else process.exit(0);
});
