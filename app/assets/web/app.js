/*
 * WebCast 电脑端发送页
 * ------------------------------------------------------------
 * 纯静态、无构建、无外部依赖。部署在电视端 App 的 assets/web/ 下，
 * 由电视的 HTTPS 服务（默认 https://<tv-ip>:8443/）提供。
 *
 * 协议：见仓库根目录 PROTOCOL.md（唯一权威契约）。
 * 流程：getDisplayMedia（用户手势内）→ wss://<host>/ws → hello → hello-ack(8s)
 *       → VideoEncoder(avc1.42E028 / avc1.4D4028) → video-config → 8 字节头视频帧
 *       → 可选音频（AudioWorklet，下混 48kHz 立体声 s16le，20ms/帧，4 字节头）
 *
 * 可调参数（开始投屏后可锁定，停止后可改）：
 *   分辨率 1920×1080(默认) / 1600×900 / 1280×720 / 1024×576（受 /api/info 的 maxWidth/maxHeight 钳制）
 *   帧率   30(默认) / 50 / 60 fps（电视面板 60Hz，50/60fps 均可选；60fps 实测硬解 59–65fps、0 丢帧）
 *   码率   自动推荐（1080p30→8、1080p50/60→12、720p30→4）或 2/4/6/8/10/12/16/20 Mbps
 *
 * 调试查询参数：
 *   ?demo=1   用 canvas 动画测试图代替屏幕采集（同样遵守所选参数）
 *   &auto=1   页面就绪后自动开始
 *   &selftest=1 把运行数据 POST 到 /__selftest，并暴露离线测试用调试对象
 *   &res=1600x900 / &fps=60 / &bitrate=12  预设参数（自动化/电视端 demo 验证用）
 */
(function () {
  'use strict';

  // ---------------------------------------------------------------------------
  // 常量
  // ---------------------------------------------------------------------------
  var PROTOCOL_VERSION = 1;
  var CODEC_CANDIDATES = ['avc1.42E028', 'avc1.4D4028'];
  var RESOLUTIONS = [
    { id: '1920x1080', width: 1920, height: 1080, label: '1920×1080' },
    { id: '1600x900', width: 1600, height: 900, label: '1600×900' },
    { id: '1280x720', width: 1280, height: 720, label: '1280×720' },
    { id: '1024x576', width: 1024, height: 576, label: '1024×576' }
  ];
  var FRAME_RATES = [30, 50, 60];
  var BITRATE_PRESETS = [2, 4, 6, 8, 10, 12, 16, 20];
  var DEFAULT_RESOLUTION = '1920x1080';
  var DEFAULT_FPS = 30;
  // 自动推荐码率（Mbps）：1080p30→8、1080p50/60→12、720p30→4（任务要求），其余档位按像素/帧率平滑递增
  var RECOMMENDED_BITRATE = {
    '1920x1080': { '30': 8, '50': 12, '60': 12 },
    '1600x900': { '30': 6, '50': 10, '60': 10 },
    '1280x720': { '30': 4, '50': 8, '60': 8 },
    '1024x576': { '30': 2, '50': 4, '60': 4 }
  };
  var KEYFRAME_INTERVAL_MS = 2000;
  var BACKPRESSURE_BASE_BYTES = 3 * 1024 * 1024; // 至少允许 3MB 发送缓冲
  var HELLO_ACK_TIMEOUT_MS = 8000;
  var CONNECT_TIMEOUT_MS = 10000;
  var AUDIO_RATE = 48000;
  var AUDIO_CHANNELS = 2;
  var AUDIO_FRAME_MS = 20;

  var params = new URLSearchParams(location.search);
  var DEMO = params.get('demo') === '1';
  var AUTO = params.get('auto') === '1';
  var SELFTEST = params.get('selftest') === '1';
  // 仅自动化测试使用：?sound=tone 时用 440Hz 振荡器代替真实系统声音/麦克风
  var TEST_TONE = params.get('sound') === 'tone';

  // ---------------------------------------------------------------------------
  // DOM / 状态
  // ---------------------------------------------------------------------------
  var ui = {};

  var state = {
    phase: 'idle', // idle | starting | running | stopping
    compat: { fatal: [], warn: [] },

    ws: null,
    wsWaiter: null,
    tvInfo: null,

    encoder: null,
    actual: null, // 实际编码参数 {codec,width,height,fps,bitrate}
    target: null, // 本次开始的期望参数（含电视上限钳制结果）
    backpressureBytes: 0, // 按码率放大的背压阈值
    videoConfigSent: false,
    encodeCtx: null,

    sourceKind: 'none', // none | display | demo
    displayStream: null,
    displayAudioTrack: null,
    micStream: null,
    videoTrack: null,
    videoEl: null,
    demoCtx: null,
    demoFrame: 0,

    audioCtx: null,
    audioSource: null,
    audioNode: null,
    audioConfigSent: false,
    audioSeq: 0,
    micPromise: null,
    toneOsc: null,
    toneGain: null,

    rvfcId: 0,
    timeoutId: 0,
    keyframeTimer: 0,
    statsTimer: 0,
    statsBase: null,

    forceKeyframe: true,
    overloaded: false,

    sentFrames: 0,
    sentBytes: 0,
    keyFrames: 0,
    audioFrames: 0,
    audioDropped: 0,
    skippedFrames: 0,
    capturedFrames: 0,
    lastPtsMs: 0,

    tvStats: { fps: null, kbps: null, decodeMs: null, drops: null },
    tvStatsAt: 0,
    tvStatParts: [],
    lastError: null,
    noticeTag: ''
  };

  var log = function () {
    var args = Array.prototype.slice.call(arguments);
    args.unshift('[WebCast]');
    try { console.log.apply(console, args); } catch (e) { /* ignore */ }
  };

  function silentError(message) {
    var err = new Error(message);
    err.silent = true;
    return err;
  }

  // ---------------------------------------------------------------------------
  // 工具函数
  // ---------------------------------------------------------------------------
  function $(id) { return document.getElementById(id); }

  function cacheUi() {
    var ids = [
      'status', 'status-dot', 'status-text',
      'compat-box', 'compat-list', 'demo-box',
      'error-box', 'error-text', 'notice-box',
      'tv-name', 'tv-model', 'tv-address', 'tv-resolution', 'tv-codecs', 'tv-audio',
      'resolution-select', 'fps-select', 'bitrate-select',
      'resolution-hint', 'bitrate-hint', 'fps-hint',
      'sound-select', 'sound-hint', 'overlay-toggle', 'overlay-hint', 'start-btn', 'stop-btn',
      'stat-fps', 'stat-bitrate', 'stat-tv-fps', 'stat-latency', 'stat-keyframes', 'stat-drops',
      'encoder-info', 'audio-info', 'tv-stat-info',
      'preview-label', 'preview-video', 'demo-canvas', 'preview-placeholder',
      'encode-canvas'
    ];
    for (var i = 0; i < ids.length; i++) {
      var id = ids[i];
      ui[id.replace(/-([a-z])/g, function (m, c) { return c.toUpperCase(); })] = $(id);
    }
  }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  function bytesToBase64(buffer) {
    var bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer);
    var binary = '';
    var chunk = 0x8000;
    for (var i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
    }
    return btoa(binary);
  }

  function fetchJson(url, timeoutMs) {
    var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = 0;
    if (controller) {
      timer = setTimeout(function () { try { controller.abort(); } catch (e) {} }, timeoutMs);
    }
    return fetch(url, { cache: 'no-store', signal: controller ? controller.signal : undefined })
      .then(function (resp) {
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        return resp.json();
      })
      .then(function (data) {
        if (timer) clearTimeout(timer);
        return data;
      }, function (err) {
        if (timer) clearTimeout(timer);
        throw err;
      });
  }

  // ---------------------------------------------------------------------------
  // UI 基础
  // ---------------------------------------------------------------------------
  function setStatus(mode, text) {
    ui.statusDot.className = 'dot' + (mode && mode !== 'idle' ? ' ' + mode : '');
    ui.statusText.textContent = text;
  }

  function showError(message) {
    state.lastError = message;
    ui.errorText.textContent = message;
    ui.errorBox.classList.remove('hidden');
    log('错误：' + message);
  }

  function clearError() {
    state.lastError = null;
    ui.errorText.textContent = '';
    ui.errorBox.classList.add('hidden');
  }

  function showNotice(text, kind, tag) {
    ui.noticeBox.textContent = text;
    ui.noticeBox.className = 'banner ' + (kind === 'warn' ? 'warn' : 'info');
    ui.noticeBox.classList.remove('hidden');
    state.noticeTag = tag || '';
  }

  function hideNotice(tag) {
    if (tag && state.noticeTag !== tag) return;
    ui.noticeBox.classList.add('hidden');
    ui.noticeBox.textContent = '';
    state.noticeTag = '';
  }

  function resetStatsUi() {
    ui.statFps.textContent = '—';
    ui.statBitrate.textContent = '—';
    ui.statTvFps.textContent = '—';
    ui.statLatency.textContent = '—';
    ui.statKeyframes.textContent = '0';
    ui.statDrops.textContent = '0';
    ui.tvStatInfo.textContent = '尚未收到统计';
    ui.audioInfo.textContent = '未开启';
    ui.encoderInfo.textContent = '—';
  }

  function selectedSoundMode() {
    if (TEST_TONE) return 'tone';
    var v = ui.soundSelect ? ui.soundSelect.value : 'none';
    return v === 'display' || v === 'mic' ? v : 'none';
  }

  function overlayEnabled() {
    return !ui.overlayToggle || ui.overlayToggle.checked;
  }

  function sendOverlaySetting() {
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      wsSendJson({ t: 'overlay', show: overlayEnabled() });
    }
  }

  function updateControls() {
    var active = state.phase === 'starting' || state.phase === 'running' || state.phase === 'stopping';
    var fatal = state.compat && state.compat.fatal ? state.compat.fatal.length : 0;
    ui.startBtn.disabled = active || fatal > 0;
    ui.stopBtn.disabled = !(state.phase === 'starting' || state.phase === 'running');
    ui.resolutionSelect.disabled = active;
    ui.fpsSelect.disabled = active;
    ui.bitrateSelect.disabled = active;
    ui.soundSelect.disabled = active;
  }

  // ---------------------------------------------------------------------------
  // 兼容性 / 电视信息
  // ---------------------------------------------------------------------------
  function checkCompat() {
    var fatal = [];
    var warn = [];
    if (!window.isSecureContext) {
      fatal.push('当前页面不是安全上下文（非 HTTPS）。请通过 https://<电视IP>:8443/ 打开本页；' +
        '自签名证书首次访问需要在警告页点「高级 → 继续前往」。');
    }
    if (typeof window.VideoEncoder === 'undefined') {
      fatal.push('当前浏览器不支持 WebCodecs 视频编码（VideoEncoder）。请使用 Chrome / Edge 94 及以上版本（推荐最新版）。');
    }
    if (!DEMO && (!navigator.mediaDevices || typeof navigator.mediaDevices.getDisplayMedia !== 'function')) {
      fatal.push('当前浏览器不支持屏幕共享（getDisplayMedia）。请使用桌面版 Chrome / Edge；演示模式（?demo=1）不受此限制。');
    }
    if (typeof window.AudioContext === 'undefined' || typeof window.AudioWorkletNode === 'undefined') {
      warn.push('当前浏览器不支持 AudioWorklet，声音选项不可用（仍可投屏画面）。');
    }
    return { fatal: fatal, warn: warn };
  }

  function renderCompat() {
    var fatal = state.compat.fatal;
    if (fatal.length) {
      ui.compatList.innerHTML = '';
      for (var i = 0; i < fatal.length; i++) {
        var li = document.createElement('li');
        li.textContent = fatal[i];
        ui.compatList.appendChild(li);
      }
      ui.compatBox.classList.remove('hidden');
    } else {
      ui.compatBox.classList.add('hidden');
    }
    if (state.compat.warn.length) showNotice(state.compat.warn.join(' '), 'warn', 'compat');
    updateControls();
  }

  function renderTvInfo(info) {
    if (!info) return;
    var name = info.name || info.model || '电视';
    ui.tvName.textContent = name;
    var meta = [];
    if (info.model) meta.push(info.model);
    if (info.version) meta.push('v' + info.version);
    if (info.protocol != null) meta.push('协议 v' + info.protocol);
    ui.tvModel.textContent = meta.join(' · ');
    var ip = info.ip || location.hostname || '';
    var port = info.port || location.port || 8443;
    ui.tvAddress.textContent = ip ? (ip + ':' + port) : (location.host || '—');
    if (info.width && info.height) {
      ui.tvResolution.textContent = info.width + '×' + info.height + (info.fps ? ' · ' + info.fps + 'Hz' : '');
    } else {
      ui.tvResolution.textContent = '—';
    }
    if (Array.isArray(info.codecs) && info.codecs.length) {
      ui.tvCodecs.textContent = info.codecs.join(' / ');
    } else {
      ui.tvCodecs.textContent = '未报告';
    }
    ui.tvAudio.textContent = info.audio === false ? '不支持' : '支持';
    applyTvCapabilities(info);
  }

  function loadTvInfo() {
    return fetchJson('api/info', 5000).then(function (info) {
      state.tvInfo = info;
      renderTvInfo(info);
      return info;
    }, function (err) {
      log('读取 /api/info 失败', err);
      ui.tvName.textContent = '无法获取电视信息';
      ui.tvModel.textContent = '（/api/info 请求失败）';
      ui.tvAddress.textContent = location.host || '—';
      showNotice('无法读取电视信息（/api/info），仍可尝试开始投屏；请确认电视端投屏服务已启动。', 'warn', 'info');
      return null;
    });
  }

  // ---------------------------------------------------------------------------
  // 采集源（必须保持用户手势：getDisplayMedia 在 start() 同步链路中调用）
  // ---------------------------------------------------------------------------
  // 非阻塞获取麦克风：投屏不等授权，授权成功后再挂上音频。
  // 返回 Promise<MediaStreamTrack|null>，20 秒无响应则放弃（用户可能忽略了权限弹窗）。
  function acquireMic() {
    var attempt = navigator.mediaDevices.getUserMedia({
      audio: {
        channelCount: AUDIO_CHANNELS,
        sampleRate: AUDIO_RATE,
        echoCancellation: false,
        noiseSuppression: false,
        autoGainControl: false
      }
    }).then(function (stream) {
      if (state.phase !== 'starting' && state.phase !== 'running') {
        // 投屏已停止，别把刚授权的麦克风留着占用
        stopStream(stream);
        return null;
      }
      state.micStream = stream;
      log('麦克风已打开');
      return stream.getAudioTracks()[0] || null;
    }, function (err) {
      log('麦克风获取失败', err);
      showNotice('无法打开麦克风（' + (err && err.message ? err.message : err) + '），本次仅投屏画面。', 'warn', 'audio');
      return null;
    });
    var timeout = new Promise(function (resolve) {
      setTimeout(function () {
        showNotice('麦克风授权超时，本次仅投屏画面（投屏本身不受影响）。', 'warn', 'audio');
        resolve(null);
      }, 20000);
    });
    return Promise.race([attempt, timeout]);
  }

  function acquireSource() {
    var soundMode = selectedSoundMode();

    if (DEMO) {
      state.sourceKind = 'demo';
      state.demoCtx = ui.demoCanvas.getContext('2d', { alpha: false });
      ui.demoCanvas.classList.remove('hidden');
      ui.previewVideo.classList.add('hidden');
      ui.previewPlaceholder.classList.add('hidden');
      ui.previewLabel.textContent = '演示画面';
      drawDemoFrame();
      if (soundMode === 'display') {
        showNotice('演示模式下没有可共享的系统声音，本次仅发送演示画面。', 'warn', 'audio');
      }
      if (soundMode === 'mic') state.micPromise = acquireMic();
      return Promise.resolve();
    }

    var target = state.target || computeTarget();
    var constraints = {
      video: {
        frameRate: target.fps,
        width: { ideal: target.width },
        height: { ideal: target.height }
      },
      audio: soundMode === 'display'
    };

    return navigator.mediaDevices.getDisplayMedia(constraints).then(function (stream) {
      if (state.phase !== 'starting') {
        // 用户在授权弹窗期间点了停止
        stopStream(stream);
        throw silentError('投屏已停止');
      }
      state.displayStream = stream;
      var videoTrack = stream.getVideoTracks()[0];
      if (!videoTrack) throw new Error('没有拿到屏幕视频轨道，请重新选择要共享的内容。');
      state.videoTrack = videoTrack;
      videoTrack.addEventListener('ended', function () {
        if (state.phase === 'starting' || state.phase === 'running') {
          showNotice('屏幕共享已结束，投屏已停止。', 'warn', 'capture-ended');
          stop('capture-ended');
        }
      });

      state.videoEl = ui.previewVideo;
      ui.previewVideo.srcObject = new MediaStream([videoTrack]);
      ui.previewVideo.classList.remove('hidden');
      ui.demoCanvas.classList.add('hidden');
      ui.previewPlaceholder.classList.add('hidden');
      ui.previewLabel.textContent = '屏幕采集';
      var playPromise = ui.previewVideo.play();
      if (playPromise && typeof playPromise.catch === 'function') {
        playPromise.catch(function (err) { log('预览视频播放失败（不影响采集）', err); });
      }

      if (soundMode === 'display') {
        var audioTrack = stream.getAudioTracks()[0];
        if (audioTrack) state.displayAudioTrack = audioTrack;
        else showNotice('浏览器没有提供系统声音（可能不支持共享音频），本次仅投屏画面。', 'warn', 'audio');
      }
      if (soundMode === 'mic') state.micPromise = acquireMic();
      return null;
    }, function (err) {
      if (err && (err.name === 'NotAllowedError' || err.name === 'AbortError')) {
        throw new Error('已取消屏幕共享，未开始投屏。');
      }
      throw new Error('无法获取电脑屏幕画面：' + (err && err.message ? err.message : err) +
        '。请确认已授予屏幕录制权限后重试。');
    });
  }

  // ---------------------------------------------------------------------------
  // WebSocket 连接
  // ---------------------------------------------------------------------------
  function wsSendJson(obj) {
    var ws = state.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      log('发送失败：WebSocket 未连接', obj && obj.t);
      return false;
    }
    try {
      ws.send(JSON.stringify(obj));
      return true;
    } catch (err) {
      log('发送失败', err);
      return false;
    }
  }

  function connectWs() {
    return new Promise(function (resolve, reject) {
      var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
      var url = proto + '//' + location.host + '/ws';
      var ws;
      try {
        ws = new WebSocket(url);
      } catch (err) {
        reject(new Error('无法创建 WebSocket 连接：' + err.message));
        return;
      }
      ws.binaryType = 'arraybuffer';
      state.ws = ws;

      var settled = false;
      var connectTimer = 0;
      var ackTimer = 0;

      function settle(err, ack) {
        if (settled) return;
        settled = true;
        clearTimeout(connectTimer);
        clearTimeout(ackTimer);
        state.wsWaiter = null;
        if (err) reject(err); else resolve(ack);
      }

      state.wsWaiter = {
        reject: function (err) {
          settle(err);
          try { ws.close(); } catch (e) {}
        }
      };

      connectTimer = setTimeout(function () {
        settle(new Error('连接电视超时（10 秒）。请确认电视端投屏服务已启动，且电脑与电视在同一局域网。'));
      }, CONNECT_TIMEOUT_MS);

      ws.onopen = function () {
        clearTimeout(connectTimer);
        setStatus('work', '等待电视确认…');
        wsSendJson({ t: 'hello', v: PROTOCOL_VERSION, ua: navigator.userAgent });
        ackTimer = setTimeout(function () {
          settle(new Error('电视 8 秒内没有回应 hello-ack，连接可能被拒绝。请检查电视端日志。'));
        }, HELLO_ACK_TIMEOUT_MS);
      };

      ws.onmessage = function (event) {
        if (typeof event.data !== 'string') {
          log('收到未预期的二进制控制消息，已忽略');
          return;
        }
        var msg;
        try {
          msg = JSON.parse(event.data);
        } catch (err) {
          log('收到无法解析的文本消息', event.data);
          return;
        }
        if (!msg || typeof msg.t !== 'string') return;
        if (msg.t === 'hello-ack') {
          if (settled) { log('重复的 hello-ack，已忽略'); return; }
          state.tvInfo = msg;
          renderTvInfo(msg);
          settle(null, msg);
          sendOverlaySetting();
          return;
        }
        handleServerMessage(msg);
      };

      ws.onerror = function () {
        if (!settled) {
          settle(new Error('连接电视失败（WebSocket 错误）。请检查电视地址、服务状态与证书信任情况。'));
        }
      };

      ws.onclose = function (event) {
        if (!settled) {
          settle(new Error('电视关闭了连接（代码 ' + event.code + '）。'));
          return;
        }
        if (state.phase === 'running' || state.phase === 'starting') {
          showError('与电视的连接已断开（代码 ' + event.code + '），投屏已停止。');
          stop('ws-closed').then(function () { setStatus('error', '连接已断开'); });
        }
      };
    });
  }

  function handleServerMessage(msg) {
    switch (msg.t) {
      case 'stats':
        handleTvStats(msg);
        break;
      case 'need-keyframe':
        state.forceKeyframe = true;
        log('电视请求关键帧（need-keyframe），下一帧补发 IDR');
        break;
      case 'error':
        showError('电视端错误：' + (msg.message || '未知错误'));
        if (state.phase === 'starting' || state.phase === 'running') {
          stop('tv-error').then(function () { setStatus('error', '电视端错误'); });
        }
        break;
      case 'stop':
        showNotice('电视端已要求停止投屏。', 'warn', 'tv-stop');
        if (state.phase === 'starting' || state.phase === 'running') stop('tv-stop');
        break;
      default:
        log('收到未知消息类型：' + msg.t);
    }
  }

  function handleTvStats(msg) {
    state.tvStats = {
      fps: typeof msg.fps === 'number' ? msg.fps : null,
      kbps: typeof msg.kbps === 'number' ? msg.kbps : null,
      decodeMs: typeof msg.decodeMs === 'number' ? msg.decodeMs : null,
      drops: typeof msg.drops === 'number' ? msg.drops : null
    };
    state.tvStatsAt = performance.now();
    var parts = [];
    if (state.tvStats.kbps != null) parts.push('接收 ' + Math.round(state.tvStats.kbps) + ' kbps');
    if (state.tvStats.decodeMs != null) parts.push('解码 ' + state.tvStats.decodeMs.toFixed(1) + ' ms');
    if (state.tvStats.drops != null) parts.push('丢帧 ' + state.tvStats.drops);
    state.tvStatParts = parts;
    if (state.tvStats.fps != null) ui.statTvFps.textContent = String(Math.round(state.tvStats.fps));
    ui.tvStatInfo.textContent = parts.length ? parts.join(' · ') : '已收到电视统计';
  }

  // ---------------------------------------------------------------------------
  // 编码器
  // ---------------------------------------------------------------------------
  function findResolution(id) {
    for (var i = 0; i < RESOLUTIONS.length; i++) {
      if (RESOLUTIONS[i].id === id) return RESOLUTIONS[i];
    }
    return null;
  }

  function selectedResolution() {
    var id = ui.resolutionSelect ? ui.resolutionSelect.value : DEFAULT_RESOLUTION;
    return findResolution(id) || findResolution(DEFAULT_RESOLUTION) || RESOLUTIONS[0];
  }

  function selectedFps() {
    var value = ui.fpsSelect ? Number(ui.fpsSelect.value) : DEFAULT_FPS;
    return FRAME_RATES.indexOf(value) >= 0 ? value : DEFAULT_FPS;
  }

  function recommendedBitrateMbps(res, fps) {
    var table = RECOMMENDED_BITRATE[res && res.id] || RECOMMENDED_BITRATE[DEFAULT_RESOLUTION];
    return table[String(fps)] || table[String(DEFAULT_FPS)] || 8;
  }

  function bitrateIsAuto() {
    return !ui.bitrateSelect || ui.bitrateSelect.value === 'auto';
  }

  function selectedBitrateMbps() {
    if (bitrateIsAuto()) return recommendedBitrateMbps(selectedResolution(), selectedFps());
    var value = Number(ui.bitrateSelect.value);
    return BITRATE_PRESETS.indexOf(value) >= 0
      ? value
      : recommendedBitrateMbps(selectedResolution(), selectedFps());
  }

  function tvMaxSize(info) {
    var width = Number(info && (info.maxWidth || info.width)) || 1920;
    var height = Number(info && (info.maxHeight || info.height)) || 1080;
    return { width: Math.max(2, width), height: Math.max(2, height) };
  }

  // 超出电视 maxWidth/maxHeight 时等比收敛到不超上限的偶数尺寸
  function clampResolution(res, info) {
    var max = tvMaxSize(info || state.tvInfo || {});
    if (res.width <= max.width && res.height <= max.height) {
      return { width: res.width, height: res.height, scaled: false };
    }
    var ratio = Math.min(max.width / res.width, max.height / res.height);
    var width = Math.max(2, Math.floor((res.width * ratio) / 2) * 2);
    var height = Math.max(2, Math.floor((res.height * ratio) / 2) * 2);
    return { width: width, height: height, scaled: true };
  }

  // 综合三组控件 + 电视能力，得到本次实际使用的编码参数
  function computeTarget() {
    var res = selectedResolution();
    var fps = selectedFps();
    var mbps = selectedBitrateMbps();
    var clamped = clampResolution(res, state.tvInfo || {});
    var bitrate = mbps * 1000000;
    if (clamped.scaled) {
      var ratio = (clamped.width * clamped.height) / (res.width * res.height);
      bitrate = Math.max(1000000, Math.round(bitrate * ratio));
      log('按电视最大分辨率收敛到 ' + clamped.width + '×' + clamped.height +
        '，码率调整为 ' + (bitrate / 1000000).toFixed(1) + ' Mbps');
    }
    return {
      width: clamped.width,
      height: clamped.height,
      fps: fps,
      bitrate: bitrate,
      requestedWidth: res.width,
      requestedHeight: res.height,
      requestedMbps: mbps,
      clamped: clamped.scaled
    };
  }

  // 背压阈值：允许约 2 秒的发送缓冲，至少 3MB（20Mbps → 5MB）
  function backpressureLimit(bitrate) {
    return Math.max(BACKPRESSURE_BASE_BYTES, Math.round((bitrate * 2) / 8));
  }

  function updateParamHints() {
    var res = selectedResolution();
    var fps = selectedFps();
    var mbps = selectedBitrateMbps();
    if (ui.bitrateHint) {
      ui.bitrateHint.textContent = '当前使用 ' + mbps + ' Mbps' +
        (bitrateIsAuto() ? '（自动推荐）' : '（手动选择）') +
        ' · ' + res.label + ' @ ' + fps + 'fps';
    }
    if (ui.resolutionHint) {
      var max = tvMaxSize(state.tvInfo || {});
      ui.resolutionHint.textContent = '电视最高支持 ' + max.width + '×' + max.height +
        '；超出能力的选项已禁用，实际编码尺寸以电视能力为准。';
    }
  }

  // 根据 /api/info（以及 hello-ack）的 maxWidth/maxHeight 禁用/钳制分辨率选项
  function applyTvCapabilities(info) {
    if (!info || !ui.resolutionSelect) return;
    var max = tvMaxSize(info);
    var options = ui.resolutionSelect.options;
    var firstAllowedId = '';
    for (var i = 0; i < options.length; i++) {
      var res = findResolution(options[i].value);
      if (!res) continue;
      var allowed = res.width <= max.width && res.height <= max.height;
      options[i].disabled = !allowed;
      if (allowed && !firstAllowedId) firstAllowedId = res.id;
    }
    if (!firstAllowedId) {
      updateParamHints();
      return;
    }
    var current = selectedResolution();
    if (current.width > max.width || current.height > max.height) {
      ui.resolutionSelect.value = firstAllowedId;
      var allowedRes = findResolution(firstAllowedId);
      showNotice('电视最高支持 ' + max.width + '×' + max.height + '，已自动选择 ' +
        (allowedRes ? allowedRes.label : firstAllowedId) + '。', 'warn', 'resolution');
    }
    updateParamHints();
  }

  // 仅 ?selftest=1 时暴露给 Node 离线测试（本任务不启动浏览器）
  function exposeDebugApi() {
    window.__WEBCAST_DEBUG__ = {
      resolutions: RESOLUTIONS.map(function (r) {
        return { id: r.id, width: r.width, height: r.height, label: r.label };
      }),
      frameRates: FRAME_RATES.slice(),
      bitratePresets: BITRATE_PRESETS.slice(),
      defaults: { resolution: DEFAULT_RESOLUTION, fps: DEFAULT_FPS },
      recommendBitrateMbps: recommendedBitrateMbps,
      selectedResolution: selectedResolution,
      selectedFps: selectedFps,
      selectedBitrateMbps: selectedBitrateMbps,
      bitrateIsAuto: bitrateIsAuto,
      tvMaxSize: tvMaxSize,
      clampResolution: clampResolution,
      computeTarget: computeTarget,
      backpressureLimit: backpressureLimit,
      applyTvCapabilities: applyTvCapabilities,
      updateParamHints: updateParamHints,
      getHintText: function () { return ui.bitrateHint ? ui.bitrateHint.textContent : ''; },
      getEncoderInfoText: function () { return ui.encoderInfo ? ui.encoderInfo.textContent : ''; },
      setTvInfo: function (info) { state.tvInfo = info; },
      setSelection: function (resId, fps, mbps) {
        if (ui.resolutionSelect && findResolution(resId)) ui.resolutionSelect.value = resId;
        if (ui.fpsSelect && FRAME_RATES.indexOf(Number(fps)) >= 0) ui.fpsSelect.value = String(Number(fps));
        if (ui.bitrateSelect) {
          if (mbps == null || mbps === 'auto') ui.bitrateSelect.value = 'auto';
          else if (BITRATE_PRESETS.indexOf(Number(mbps)) >= 0) ui.bitrateSelect.value = String(Number(mbps));
        }
        updateParamHints();
      }
    };
  }

  function setupEncoder() {
    var target = computeTarget();
    state.target = target;
    var width = target.width;
    var height = target.height;
    var fps = target.fps;
    var bitrate = target.bitrate;
    state.backpressureBytes = backpressureLimit(bitrate);

    var candidates = CODEC_CANDIDATES.slice();
    var ackCodecs = state.tvInfo && Array.isArray(state.tvInfo.codecs) ? state.tvInfo.codecs : null;
    if (ackCodecs && ackCodecs.length) {
      var filtered = candidates.filter(function (c) { return ackCodecs.indexOf(c) >= 0; });
      if (filtered.length) candidates = filtered;
      else showNotice('电视报告的 H.264 编码能力未知，按默认顺序尝试。', 'warn', 'codec');
    }

    var attempted = [];
    var lastError = null;

    function tryNext(index) {
      if (index >= candidates.length) {
        throw new Error('当前浏览器无法编码 H.264（已尝试 ' + attempted.join(' / ') +
          '）。请升级 Chrome / Edge，或确认系统支持 H.264 编码。' +
          (lastError && lastError.message ? '（' + lastError.message + '）' : ''));
      }
      var codec = candidates[index];
      attempted.push(codec);
      var config = {
        codec: codec,
        width: width,
        height: height,
        bitrate: bitrate,
        framerate: fps,
        latencyMode: 'realtime',
        avc: { format: 'avc' }
      };
      return VideoEncoder.isConfigSupported(config).catch(function (err) {
        lastError = err;
        log('isConfigSupported 失败', codec, err);
        return { supported: false };
      }).then(function (support) {
        if (!support || !support.supported) {
          log('编码配置不受支持', codec);
          return tryNext(index + 1);
        }
        var encoder = null;
        try {
          encoder = new VideoEncoder({ output: onEncodedChunk, error: onEncoderError });
          encoder.configure(config);
        } catch (err) {
          lastError = err;
          log('VideoEncoder.configure 失败', codec, err);
          if (encoder) { try { encoder.close(); } catch (e2) {} }
          return tryNext(index + 1);
        }
        state.encoder = encoder;
        state.actual = {
          codec: codec,
          width: width,
          height: height,
          fps: fps,
          bitrate: bitrate
        };
        state.videoConfigSent = false;
        state.forceKeyframe = true;
        ui.encodeCanvas.width = width;
        ui.encodeCanvas.height = height;
        state.encodeCtx = ui.encodeCanvas.getContext('2d', { alpha: false, desynchronized: true });
        ui.encoderInfo.textContent = codec + ' · ' + width + '×' + height +
          ' · ' + (bitrate / 1000000).toFixed(2).replace(/\.?0+$/, '') + ' Mbps · ' + fps + 'fps';
        log('已选编码器', config);
        return null;
      });
    }

    return tryNext(0);
  }

  function onEncodedChunk(chunk, metadata) {
    try {
      var ws = state.ws;
      if (!ws || ws.readyState !== WebSocket.OPEN) return;

      if (!state.videoConfigSent) {
        var decoderConfig = metadata && metadata.decoderConfig;
        var description = decoderConfig && decoderConfig.description;
        if (!description) {
          // 协议要求 video-config 先于第一个视频帧；等带 description 的关键帧
          log('首个输出缺少 decoderConfig.description，等待下一个关键帧');
          state.forceKeyframe = true;
          state.skippedFrames++;
          return;
        }
        if (!state.actual) return;
        wsSendJson({
          t: 'video-config',
          codec: state.actual.codec,
          width: state.actual.width,
          height: state.actual.height,
          desc: bytesToBase64(description),
          fps: state.actual.fps
        });
        state.videoConfigSent = true;
        log('已发送 video-config：' + state.actual.codec + ' ' + state.actual.width + '×' +
          state.actual.height + '，desc ' + description.byteLength + ' 字节');
      }

      var ptsMs = Math.max(0, Math.round(chunk.timestamp / 1000));
      var packet = new Uint8Array(8 + chunk.byteLength);
      packet[0] = 0x01; // 媒体类型：视频
      packet[1] = chunk.type === 'key' ? 0x01 : 0x00; // bit0 = 关键帧
      packet[2] = 0;
      packet[3] = 0;
      new DataView(packet.buffer).setUint32(4, ptsMs, false);
      chunk.copyTo(packet.subarray(8));

      ws.send(packet);
      state.sentFrames++;
      state.sentBytes += packet.byteLength;
      state.lastPtsMs = ptsMs;
      if (chunk.type === 'key') state.keyFrames++;
    } catch (err) {
      log('处理编码输出失败', err);
    } finally {
      try { chunk.close(); } catch (e) {}
    }
  }

  function onEncoderError(error) {
    log('编码器错误', error);
    showError('视频编码器出错：' + (error && error.message ? error.message : error));
    if (state.phase === 'running' || state.phase === 'starting') {
      stop('encoder-error').then(function () { setStatus('error', '编码失败'); });
    }
  }

  // ---------------------------------------------------------------------------
  // 采集循环 / 编码
  // ---------------------------------------------------------------------------
  function startCaptureLoop() {
    var video = state.videoEl;

    var fps = state.actual && state.actual.fps ? state.actual.fps : DEFAULT_FPS;
    if (state.sourceKind === 'demo') {
      var tick = function () {
        if (state.phase !== 'running') return;
        state.demoFrame++;
        drawDemoFrame();
        captureFrame();
        state.timeoutId = setTimeout(tick, Math.round(1000 / fps));
      };
      tick();
    } else if (video && typeof video.requestVideoFrameCallback === 'function') {
      var onFrame = function () {
        if (state.phase !== 'running') return;
        captureFrame();
        state.rvfcId = video.requestVideoFrameCallback(onFrame);
      };
      state.rvfcId = video.requestVideoFrameCallback(onFrame);
    } else {
      log('浏览器不支持 requestVideoFrameCallback，降级为 ' + Math.round(1000 / fps) + 'ms 定时采集');
      state.timeoutId = setInterval(function () {
        if (state.phase === 'running') captureFrame();
      }, Math.round(1000 / fps));
    }

    state.keyframeTimer = setInterval(function () {
      if (state.phase === 'running') state.forceKeyframe = true;
    }, KEYFRAME_INTERVAL_MS);
  }

  function cancelCaptureLoop() {
    if (state.rvfcId && state.videoEl && typeof state.videoEl.cancelVideoFrameCallback === 'function') {
      try { state.videoEl.cancelVideoFrameCallback(state.rvfcId); } catch (e) {}
    }
    state.rvfcId = 0;
    if (state.timeoutId) {
      clearTimeout(state.timeoutId);
      clearInterval(state.timeoutId);
      state.timeoutId = 0;
    }
    if (state.keyframeTimer) {
      clearInterval(state.keyframeTimer);
      state.keyframeTimer = 0;
    }
  }

  function scaleToEncodeCanvas(source, sw, sh) {
    var canvas = ui.encodeCanvas;
    var ctx = state.encodeCtx || (state.encodeCtx = canvas.getContext('2d', { alpha: false }));
    var cw = canvas.width;
    var ch = canvas.height;
    ctx.fillStyle = '#000';
    ctx.fillRect(0, 0, cw, ch);
    var ratio = Math.min(cw / sw, ch / sh);
    var dw = Math.max(2, Math.round(sw * ratio));
    var dh = Math.max(2, Math.round(sh * ratio));
    ctx.drawImage(source, Math.round((cw - dw) / 2), Math.round((ch - dh) / 2), dw, dh);
  }

  function captureFrame() {
    if (state.phase !== 'running') return;
    var encoder = state.encoder;
    var ws = state.ws;
    if (!encoder || encoder.state !== 'configured') return;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;

    // 背压：跳过采集/编码，但绝不丢弃已编码帧（已编码帧仍按序发出）
    var backpressureBytes = state.backpressureBytes || BACKPRESSURE_BASE_BYTES;
    if (ws.bufferedAmount > backpressureBytes) {
      state.skippedFrames++;
      if (!state.overloaded) {
        state.overloaded = true;
        state.forceKeyframe = true;
        showNotice('网络拥塞：已暂停采集/编码，等待发送缓冲回落后自动补发关键帧（不会丢弃已编码帧）。', 'warn', 'backpressure');
        log('触发背压，bufferedAmount=' + ws.bufferedAmount);
      }
      return;
    }
    if (state.overloaded) {
      state.overloaded = false;
      state.forceKeyframe = true;
      hideNotice('backpressure');
      log('发送缓冲恢复，下一帧补发 IDR');
    }

    // 编码器内部队列也做轻量保护，避免主线程排队堆积
    if (typeof encoder.encodeQueueSize === 'number' && encoder.encodeQueueSize > 6) {
      state.skippedFrames++;
      state.forceKeyframe = true;
      return;
    }

    var source = state.sourceKind === 'demo' ? ui.demoCanvas : ui.previewVideo;
    var sw = state.sourceKind === 'demo' ? source.width : source.videoWidth;
    var sh = state.sourceKind === 'demo' ? source.height : source.videoHeight;
    if (!sw || !sh) return;

    scaleToEncodeCanvas(source, sw, sh);

    var keyFrame = state.forceKeyframe;
    state.forceKeyframe = false;
    var frame = null;
    try {
      frame = new VideoFrame(ui.encodeCanvas, {
        timestamp: Math.round(performance.now() * 1000),
        duration: Math.round(1000000 / (state.actual && state.actual.fps ? state.actual.fps : DEFAULT_FPS))
      });
      encoder.encode(frame, { keyFrame: keyFrame });
      state.capturedFrames++;
    } catch (err) {
      log('创建/提交 VideoFrame 失败', err);
      state.forceKeyframe = keyFrame;
    } finally {
      if (frame) { try { frame.close(); } catch (e) {} }
    }
  }

  function drawDemoFrame() {
    var canvas = ui.demoCanvas;
    var ctx = state.demoCtx || (state.demoCtx = canvas.getContext('2d', { alpha: false }));
    var W = canvas.width;
    var H = canvas.height;
    var t = performance.now() / 1000;

    ctx.fillStyle = '#05070d';
    ctx.fillRect(0, 0, W, H);

    var bars = ['#ffffff', '#ffe600', '#00e5ff', '#00e676', '#e040fb', '#ff1744', '#2979ff'];
    var barH = Math.round(H * 0.52);
    var barW = W / bars.length;
    for (var i = 0; i < bars.length; i++) {
      ctx.fillStyle = bars[i];
      ctx.fillRect(Math.round(i * barW), 0, Math.ceil(barW) + 1, barH);
    }

    var grad = ctx.createLinearGradient(0, barH, W, H);
    grad.addColorStop(0, '#0b1a3a');
    grad.addColorStop(1, '#02040a');
    ctx.fillStyle = grad;
    ctx.fillRect(0, barH, W, H - barH);

    var size = Math.round(H * 0.12);
    var x = (Math.sin(t * 1.2) * 0.5 + 0.5) * (W - size);
    var y = barH + (H - barH - size) * (0.5 + 0.42 * Math.sin(t * 1.9));
    ctx.save();
    ctx.shadowColor = 'rgba(79,140,255,0.85)';
    ctx.shadowBlur = 42;
    ctx.fillStyle = '#4f8cff';
    ctx.fillRect(Math.round(x), Math.round(y), size, size);
    ctx.restore();

    var now = new Date();
    var clock = pad2(now.getHours()) + ':' + pad2(now.getMinutes()) + ':' + pad2(now.getSeconds());
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = '#ffffff';
    ctx.font = '700 ' + Math.round(H * 0.13) + 'px "PingFang SC", "Microsoft YaHei", system-ui, sans-serif';
    ctx.fillText(clock, W / 2, barH + (H - barH) * 0.36);

    var actual = state.actual;
    var paramText = actual
      ? ('采集 ' + W + '×' + H + ' → 编码 ' + actual.width + '×' + actual.height +
        ' @' + actual.fps + 'fps · ' + Math.round(actual.bitrate / 1000000) + ' Mbps')
      : ('采集 ' + W + '×' + H);
    ctx.font = '600 ' + Math.round(H * 0.042) + 'px system-ui, sans-serif';
    ctx.fillStyle = 'rgba(233,239,251,0.92)';
    ctx.fillText('WebCast 演示画面 · 第 ' + state.demoFrame + ' 帧 · ' + paramText,
      W / 2, H - Math.round(H * 0.075));

    ctx.font = '500 ' + Math.round(H * 0.028) + 'px ui-monospace, Menlo, monospace';
    ctx.fillStyle = 'rgba(142,160,191,0.9)';
    ctx.fillText('demo=1 · 本机时间 ' + now.toISOString(), W / 2, H - Math.round(H * 0.032));
  }

  // ---------------------------------------------------------------------------
  // 音频
  // ---------------------------------------------------------------------------
  function onAudioSamples(samples) {
    if (!state.audioConfigSent) return;
    if (state.phase !== 'running' && state.phase !== 'starting') return;
    var ws = state.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    if (ws.bufferedAmount > (state.backpressureBytes || BACKPRESSURE_BASE_BYTES)) {
      state.audioDropped++;
      return;
    }
    var payload = samples instanceof Int16Array ? samples : new Int16Array(samples);
    var packet = new Uint8Array(4 + payload.byteLength);
    packet[0] = 0x02; // 媒体类型：音频
    packet[1] = 0;
    new DataView(packet.buffer).setUint16(2, state.audioSeq & 0xffff, false);
    state.audioSeq = (state.audioSeq + 1) & 0xffff;
    packet.set(new Uint8Array(payload.buffer, payload.byteOffset, payload.byteLength), 4);
    try {
      ws.send(packet);
      state.audioFrames++;
    } catch (err) {
      log('音频发送失败', err);
    }
  }

  // 视频先开跑，音频（尤其是需要授权弹窗的麦克风）随后异步挂上，避免阻塞投屏
  function attachAudio() {
    var mode = selectedSoundMode();
    if (mode === 'none') return;
    if (mode === 'display' || mode === 'tone') {
      setupAudio().catch(function (err) { log('音频启动失败', err); });
      return;
    }
    ui.audioInfo.textContent = '等待麦克风授权…';
    var pending = state.micPromise;
    if (!pending) {
      setupAudio().catch(function (err) { log('音频启动失败', err); });
      return;
    }
    pending.then(function (track) {
      if (state.phase !== 'running') return;
      if (!track) return;
      return setupAudio();
    }).catch(function (err) { log('音频启动失败', err); });
  }

  function setupAudio() {
    var mode = selectedSoundMode();
    if (mode === 'none') {
      ui.audioInfo.textContent = '关闭（仅画面）';
      return Promise.resolve();
    }
    if (state.tvInfo && state.tvInfo.audio === false) {
      ui.audioInfo.textContent = '电视不支持音频';
      showNotice('电视端报告不支持音频，本次仅发送画面。', 'warn', 'audio');
      return Promise.resolve();
    }

    var track = null;
    if (mode === 'display') track = state.displayAudioTrack;
    if (mode === 'mic' && state.micStream) track = state.micStream.getAudioTracks()[0] || null;
    if (mode !== 'tone' && !track) {
      ui.audioInfo.textContent = '无可用音轨';
      showNotice(mode === 'display'
        ? '没有捕获到系统音频（浏览器可能不支持共享声音），本次仅投屏画面。'
        : '没有可用的麦克风音轨，本次仅投屏画面。', 'warn', 'audio');
      return Promise.resolve();
    }

    var AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx || typeof window.AudioWorkletNode === 'undefined') {
      ui.audioInfo.textContent = '不支持 AudioWorklet';
      showNotice('当前浏览器不支持 AudioWorklet，声音不可用（仍可投屏画面）。', 'warn', 'audio');
      return Promise.resolve();
    }

    var ctx = null;
    return Promise.resolve().then(function () {
      ctx = new AudioCtx({ sampleRate: AUDIO_RATE, latencyHint: 'interactive' });
      state.audioCtx = ctx;
      return ctx.audioWorklet.addModule(new URL('worklet.js', location.href).href);
    }).then(function () {
      if (state.phase !== 'starting' && state.phase !== 'running') {
        throw silentError('投屏已停止');
      }
      var node = new AudioWorkletNode(ctx, 'pcm-sender', {
        numberOfInputs: 1,
        numberOfOutputs: 1,
        outputChannelCount: [AUDIO_CHANNELS],
        channelCount: AUDIO_CHANNELS,
        channelCountMode: 'explicit',
        channelInterpretation: 'speakers',
        processorOptions: { frameMs: AUDIO_FRAME_MS }
      });
      node.port.onmessage = function (event) { onAudioSamples(event.data); };
      // 节点输出静音，仅用于让音频图保持运行
      node.connect(ctx.destination);

      // 协议要求 audio-config 先于任何音频帧
      var ok = wsSendJson({ t: 'audio-config', format: 's16le', rate: AUDIO_RATE, channels: AUDIO_CHANNELS });
      if (!ok) throw new Error('音频配置发送失败（连接已断开）');
      state.audioConfigSent = true;
      state.audioSeq = 0;

      if (mode === 'tone') {
        var osc = ctx.createOscillator();
        osc.type = 'sine';
        osc.frequency.value = 440;
        var gain = ctx.createGain();
        gain.gain.value = 0.12;
        osc.connect(gain);
        gain.connect(node);
        osc.start();
        state.toneOsc = osc;
        state.toneGain = gain;
      } else {
        var source = ctx.createMediaStreamSource(new MediaStream([track]));
        source.connect(node);
        state.audioSource = source;
      }
      state.audioNode = node;
      if (ctx.state === 'suspended' && typeof ctx.resume === 'function') {
        return ctx.resume().catch(function (err) { log('AudioContext resume 失败', err); });
      }
      return null;
    }).then(function () {
      ui.audioInfo.textContent = (mode === 'display' ? '共享系统音频' : mode === 'mic' ? '麦克风' : '测试音 440Hz') +
        ' · ' + AUDIO_RATE + 'Hz 立体声 s16le · ' + AUDIO_FRAME_MS + 'ms/帧';
      log('音频已开启', mode, 'context.sampleRate=' + (ctx ? ctx.sampleRate : '?'));
    }).catch(function (err) {
      if (err && err.silent) {
        log('音频初始化被中止（投屏已停止）');
        return;
      }
      log('音频初始化失败', err);
      ui.audioInfo.textContent = '初始化失败（仅画面）';
      showNotice('声音初始化失败，已改为仅投屏画面：' + (err && err.message ? err.message : err), 'warn', 'audio');
      return shutdownAudio();
    });
  }

  function shutdownAudio() {
    var node = state.audioNode;
    var source = state.audioSource;
    var ctx = state.audioCtx;
    var osc = state.toneOsc;
    var gain = state.toneGain;
    state.audioNode = null;
    state.audioSource = null;
    state.audioCtx = null;
    state.toneOsc = null;
    state.toneGain = null;
    if (osc) {
      try { osc.stop(); } catch (e) {}
      try { osc.disconnect(); } catch (e) {}
    }
    if (gain) { try { gain.disconnect(); } catch (e) {} }
    if (node) {
      try { node.port.postMessage('close'); } catch (e) {}
      try { node.port.onmessage = null; } catch (e) {}
      try { node.disconnect(); } catch (e) {}
    }
    if (source) { try { source.disconnect(); } catch (e) {} }
    if (!ctx) return Promise.resolve();
    try {
      return ctx.close().catch(function () { return null; });
    } catch (e) {
      return Promise.resolve();
    }
  }

  // ---------------------------------------------------------------------------
  // 统计
  // ---------------------------------------------------------------------------
  function startStats() {
    state.statsBase = { time: performance.now(), frames: state.sentFrames, bytes: state.sentBytes };
    state.statsTimer = setInterval(updateStats, 1000);
    updateStats();
  }

  function updateStats() {
    if (state.phase !== 'running' && state.phase !== 'starting') return;
    var now = performance.now();
    var base = state.statsBase || { time: now, frames: state.sentFrames, bytes: state.sentBytes };
    var dt = Math.max(0.001, (now - base.time) / 1000);
    var fps = (state.sentFrames - base.frames) / dt;
    var kbps = ((state.sentBytes - base.bytes) * 8) / dt / 1000;
    state.statsBase = { time: now, frames: state.sentFrames, bytes: state.sentBytes };

    ui.statFps.textContent = fps.toFixed(1);
    ui.statBitrate.textContent = (kbps / 1000).toFixed(2);
    ui.statKeyframes.textContent = String(state.keyFrames);
    ui.statDrops.textContent = String(state.skippedFrames + state.audioDropped);

    // 延迟估算：本地发送缓冲折算时长 + 电视端平均解码耗时
    var bitrate = state.actual ? state.actual.bitrate : FALLBACK_BITRATE;
    var bytesPerMs = Math.max(1, bitrate / 8 / 1000);
    var ws = state.ws;
    var queueMs = ws ? ws.bufferedAmount / bytesPerMs : 0;
    var decodeMs = state.tvStats.decodeMs;
    ui.statLatency.textContent = (queueMs + (decodeMs == null ? 0 : decodeMs)).toFixed(0);

    var stale = !state.tvStatsAt || now - state.tvStatsAt > 3500;
    if (stale) {
      ui.statTvFps.textContent = '—';
      ui.tvStatInfo.textContent = state.tvStatsAt ? '超过 3.5 秒未收到电视统计' : '尚未收到统计';
    } else if (state.tvStats.fps != null) {
      ui.statTvFps.textContent = String(Math.round(state.tvStats.fps));
    }

    reportSelfTest('tick');
  }

  // ---------------------------------------------------------------------------
  // 启动 / 停止 / 清理
  // ---------------------------------------------------------------------------
  function resetSessionState() {
    state.wsWaiter = null;
    state.encoder = null;
    state.actual = null;
    state.target = null;
    state.backpressureBytes = 0;
    state.videoConfigSent = false;
    state.encodeCtx = null;
    state.sourceKind = 'none';
    state.videoTrack = null;
    state.videoEl = null;
    state.demoCtx = null;
    state.demoFrame = 0;
    state.displayAudioTrack = null;
    state.audioConfigSent = false;
    state.audioSeq = 0;
    state.micPromise = null;
    state.toneOsc = null;
    state.toneGain = null;
    state.rvfcId = 0;
    state.timeoutId = 0;
    state.keyframeTimer = 0;
    state.statsTimer = 0;
    state.statsBase = null;
    state.forceKeyframe = true;
    state.overloaded = false;
    state.sentFrames = 0;
    state.sentBytes = 0;
    state.keyFrames = 0;
    state.audioFrames = 0;
    state.audioDropped = 0;
    state.skippedFrames = 0;
    state.capturedFrames = 0;
    state.lastPtsMs = 0;
    state.tvStats = { fps: null, kbps: null, decodeMs: null, drops: null };
    state.tvStatsAt = 0;
    state.tvStatParts = [];
    state.lastError = null;
  }

  function ensurePhase() {
    if (state.phase !== 'starting' && state.phase !== 'running') throw silentError('投屏已停止');
  }

  function ensureActive() {
    ensurePhase();
    var ws = state.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) throw new Error('与电视的连接已断开。');
  }

  function teardown(reason) {
    cancelCaptureLoop();

    // 1) 先 flush 编码器，让剩余帧在 ws 仍打开时发出（不丢弃已编码帧）
    var encoder = state.encoder;
    state.encoder = null;
    var flushPromise = Promise.resolve();
    if (encoder) {
      try {
        if (encoder.state === 'configured') {
          flushPromise = Promise.race([
            encoder.flush().catch(function () { return null; }),
            new Promise(function (resolve) { setTimeout(resolve, 500); })
          ]);
        }
      } catch (err) {
        log('编码器 flush 失败', err);
      }
    }

    return flushPromise.then(function () {
      if (encoder) { try { encoder.close(); } catch (e) {} }
      return shutdownAudio();
    }).then(function () {
      // 2) 停止采集轨道
      stopStream(state.displayStream);
      state.displayStream = null;
      stopStream(state.micStream);
      state.micStream = null;
      state.displayAudioTrack = null;
      if (state.videoEl) {
        try { state.videoEl.srcObject = null; } catch (e) {}
      }
      state.videoEl = null;

      // 3) 通知电视并关闭连接
      var ws = state.ws;
      state.ws = null;
      if (state.wsWaiter) {
        var waiter = state.wsWaiter;
        state.wsWaiter = null;
        try { waiter.reject(silentError('投屏已停止')); } catch (e) {}
      }
      if (ws) {
        try {
          if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ t: 'stop' }));
        } catch (e) {}
        try { ws.close(1000, 'stop'); } catch (e) {}
      }

      // 4) UI 复位
      if (state.statsTimer) { clearInterval(state.statsTimer); state.statsTimer = 0; }
      state.statsBase = null;
      resetStatsUi();
      ui.previewVideo.classList.add('hidden');
      ui.demoCanvas.classList.add('hidden');
      ui.previewPlaceholder.classList.remove('hidden');
      ui.previewPlaceholder.textContent = '已停止。点击「开始投屏」重新开始。';
      ui.previewLabel.textContent = '已停止';
      log('已清理：' + reason);
    });
  }

  function stopStream(stream) {
    if (!stream) return;
    try {
      var tracks = stream.getTracks();
      for (var i = 0; i < tracks.length; i++) tracks[i].stop();
    } catch (e) {}
  }

  function stop(reason) {
    if (state.phase === 'stopping' || state.phase === 'idle') return Promise.resolve();
    state.phase = 'stopping';
    setStatus('work', '正在停止…');
    updateControls();
    hideNotice('backpressure');
    return teardown(reason || '用户停止').then(function () {
      state.phase = 'idle';
      setStatus('idle', '未连接');
      updateControls();
      reportSelfTest('stop');
    }, function (err) {
      state.phase = 'idle';
      setStatus('idle', '未连接');
      updateControls();
      log('停止时出错', err);
      reportSelfTest('stop');
    });
  }

  // 页面关闭/刷新时的同步兜底清理
  function emergencyCleanup() {
    if (state.phase === 'idle') return;
    log('页面关闭，执行应急清理');
    cancelCaptureLoop();
    var ws = state.ws;
    if (ws) {
      try {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ t: 'stop' }));
      } catch (e) {}
    }
    if (state.encoder) {
      try { state.encoder.close(); } catch (e) {}
      state.encoder = null;
    }
    if (state.audioCtx) {
      try { state.audioCtx.close(); } catch (e) {}
      state.audioCtx = null;
    }
    stopStream(state.displayStream);
    stopStream(state.micStream);
    state.displayStream = null;
    state.micStream = null;
    state.ws = null;
    if (ws) { try { ws.close(); } catch (e) {} }
    state.phase = 'idle';
  }

  // ---------------------------------------------------------------------------
  // 自测回报（仅 tools/mock-ws.js 的 /__selftest 使用）
  // ---------------------------------------------------------------------------
  function reportSelfTest(stage) {
    if (!SELFTEST) return;
    var report = {
      t: 'selftest',
      stage: stage,
      ts: Date.now(),
      demo: DEMO,
      phase: state.phase,
      status: ui.statusText ? ui.statusText.textContent : '',
      secure: window.isSecureContext,
      codec: state.actual ? state.actual.codec : null,
      width: state.actual ? state.actual.width : null,
      height: state.actual ? state.actual.height : null,
      bitrate: state.actual ? state.actual.bitrate : null,
      fps: state.actual ? state.actual.fps : null,
      videoConfigSent: state.videoConfigSent,
      audioConfigSent: state.audioConfigSent,
      frames: state.sentFrames,
      keyframes: state.keyFrames,
      bytes: state.sentBytes,
      audioFrames: state.audioFrames,
      audioDropped: state.audioDropped,
      skipped: state.skippedFrames,
      captured: state.capturedFrames,
      tvStats: state.tvStats,
      tvInfo: state.tvInfo ? { name: state.tvInfo.name || null, model: state.tvInfo.model || null } : null,
      error: state.lastError,
      compatFatal: state.compat ? state.compat.fatal : []
    };
    try {
      fetch('__selftest', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(report),
        keepalive: true
      }).catch(function () { /* mock 服务器可能未实现，忽略 */ });
    } catch (e) {}
  }

  // ---------------------------------------------------------------------------
  // 启动流程
  // ---------------------------------------------------------------------------
  function start() {
    if (state.phase !== 'idle') return Promise.resolve();
    clearError();
    hideNotice();
    state.compat = checkCompat();
    if (state.compat.fatal.length) {
      renderCompat();
      showError('当前环境不满足投屏要求，请按上方提示处理（推荐 Chrome / Edge 94+ 并通过 HTTPS 打开）。');
      return Promise.resolve();
    }
    resetSessionState();
    state.target = computeTarget();
    resetStatsUi();
    state.phase = 'starting';
    setStatus('work', '准备中…');
    updateControls();

    // 重要：getDisplayMedia 必须紧跟在点击手势里同步发起，
    // 因此 acquireSource() 是 start() 的第一个 await 操作，之前不做任何异步等待。
    return acquireSource().then(function () {
      ensurePhase();
      setStatus('work', '正在连接电视…');
      return connectWs();
    }).then(function () {
      ensureActive();
      setStatus('work', '正在配置编码器…');
      return setupEncoder();
    }).then(function () {
      ensureActive();
      state.phase = 'running';
      startCaptureLoop();
      startStats();
      setStatus('on', '投屏中');
      updateControls();
      attachAudio();
      log('投屏已开始');
      reportSelfTest('running');
    }).catch(function (err) {
      if (err && err.silent) {
        log('启动流程被中止：' + err.message);
        return;
      }
      var message = err && err.message ? err.message : String(err);
      showError(message);
      log('启动失败', err);
      state.phase = 'stopping';
      return teardown('start-failed').then(function () {
        state.phase = 'idle';
        resetStatsUi();
        setStatus('error', '未连接');
        updateControls();
        reportSelfTest('error');
      });
    });
  }

  // ---------------------------------------------------------------------------
  // 初始化
  // ---------------------------------------------------------------------------
  function bindEvents() {
    ui.startBtn.addEventListener('click', function () { start(); });
    ui.stopBtn.addEventListener('click', function () { stop('用户停止'); });
    ui.resolutionSelect.addEventListener('change', updateParamHints);
    ui.fpsSelect.addEventListener('change', updateParamHints);
    ui.bitrateSelect.addEventListener('change', updateParamHints);
    if (ui.overlayToggle) {
      ui.overlayToggle.addEventListener('change', function () {
        try {
          localStorage.setItem('webcast.overlay', overlayEnabled() ? '1' : '0');
        } catch (e) {}
        if (ui.overlayHint) {
          ui.overlayHint.textContent = overlayEnabled()
            ? '投屏中显示电视左上角状态条（FPS / 码率 / 解码耗时）。'
            : '已关闭：电视只显示画面，不显示状态条。';
        }
        sendOverlaySetting();
      });
    }
    window.addEventListener('pagehide', emergencyCleanup, false);
    window.addEventListener('beforeunload', emergencyCleanup, false);
  }

  function applyQueryOverrides() {
    // 预设参数（便于电视上用 demo 验证 60fps / 码率，无需在无头浏览器里操作 UI）：
    //   ?res=1920x1080|1600x900|1280x720|1024x576&fps=30|50|60&bitrate=auto|2|4|...|20
    // 兼容旧参数 ?quality=1080p|720p
    var resParam = params.get('res');
    var legacyQuality = params.get('quality');
    if (!resParam && legacyQuality === '1080p') resParam = '1920x1080';
    if (!resParam && legacyQuality === '720p') resParam = '1280x720';
    if (ui.resolutionSelect && resParam && findResolution(resParam)) {
      ui.resolutionSelect.value = resParam;
    }
    var fpsParam = Number(params.get('fps'));
    if (ui.fpsSelect && FRAME_RATES.indexOf(fpsParam) >= 0) {
      ui.fpsSelect.value = String(fpsParam);
    }
    var bitrateParam = params.get('bitrate');
    if (ui.bitrateSelect && bitrateParam) {
      if (bitrateParam === 'auto') ui.bitrateSelect.value = 'auto';
      else if (BITRATE_PRESETS.indexOf(Number(bitrateParam)) >= 0) {
        ui.bitrateSelect.value = String(Number(bitrateParam));
      }
    }
    var sound = params.get('sound');
    if (sound === 'none' || sound === 'display' || sound === 'mic') {
      ui.soundSelect.value = sound;
    }
    updateParamHints();
  }

  function init() {
    cacheUi();
    try {
      if (localStorage.getItem('webcast.overlay') === '0' && ui.overlayToggle) {
        ui.overlayToggle.checked = false;
        if (ui.overlayHint) ui.overlayHint.textContent = '已关闭：电视只显示画面，不显示状态条。';
      }
    } catch (e) {}
    applyQueryOverrides();
    if (SELFTEST) exposeDebugApi();
    state.compat = checkCompat();
    bindEvents();
    if (DEMO) ui.demoBox.classList.remove('hidden');
    resetStatsUi();
    renderCompat();
    updateControls();
    loadTvInfo().then(function () {
      if (AUTO && state.compat.fatal.length === 0 && state.phase === 'idle') {
        log('auto=1：自动开始投屏' + (DEMO ? '（演示模式）' : ''));
        setTimeout(function () { start(); }, 150);
      }
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
