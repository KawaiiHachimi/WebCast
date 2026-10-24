/*
 * WebCast 音频采集 AudioWorklet
 * ------------------------------------------------------------
 * 输入：MediaStreamAudioSourceNode（标签页/系统声音或麦克风）的浮点 PCM。
 * 输出：每 20ms（48kHz 下 960 帧/声道）下混为立体声 s16le，
 *       postMessage 到主线程，由主线程按协议加 4 字节头后经 WebSocket 发送。
 *
 * 协议：PROTOCOL.md §3.1 音频二进制帧
 *   偏移 0  u8  0x02
 *   偏移 1  u8  reserved = 0
 *   偏移 2  u16 seq（大端，递增）
 *   偏移 4  ... PCM s16le 交织，48000Hz，2 声道，20ms = 7680 字节（1920 样本）
 */
class PcmSenderProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const opts = (options && options.processorOptions) || {};
    // AudioWorkletGlobalScope 的 sampleRate 是 AudioContext 的实际采样率
    this.frameMs = opts.frameMs || 20;
    this.frameSamples = Math.max(1, Math.round(sampleRate * this.frameMs / 1000));
    this.pending = new Int16Array(this.frameSamples * 2);
    this.filled = 0;
    this.closed = false;
    this.port.onmessage = (event) => {
      const data = event.data;
      if (data === 'close') {
        this.closed = true;
        this.filled = 0;
      } else if (data === 'flush') {
        this.flush();
      }
    };
  }

  process(inputs, outputs) {
    const input = inputs[0];
    if (input && input.length > 0 && !this.closed) {
      const channels = input.length;
      const n = input[0].length;
      const left = input[0];
      const right = channels > 1 ? input[1] : null;
      for (let i = 0; i < n; i++) {
        let l, r;
        if (channels === 1) {
          l = left[i];
          r = left[i];
        } else if (channels === 2) {
          l = left[i];
          r = right[i];
        } else {
          // 多声道下混：偶数声道计入左、奇数声道计入右，各自取平均避免削波
          let sl = 0, sr = 0, nl = 0, nr = 0;
          for (let c = 0; c < channels; c++) {
            const v = input[c][i];
            if ((c & 1) === 0) { sl += v; nl++; } else { sr += v; nr++; }
          }
          l = nl > 0 ? sl / nl : 0;
          r = nr > 0 ? sr / nr : l;
        }
        this.pending[this.filled * 2] = floatToInt16(l);
        this.pending[this.filled * 2 + 1] = floatToInt16(r);
        this.filled++;
        if (this.filled >= this.frameSamples) {
          this.flush();
        }
      }
    }

    // 本节点只用于采集：输出静音，避免回放/回声；连接到 destination 是为了
    // 让音频图保持活跃，从而持续调用 process()。
    const out = outputs[0];
    if (out) {
      for (let c = 0; c < out.length; c++) {
        out[c].fill(0);
      }
    }
    return !this.closed;
  }

  flush() {
    if (this.filled <= 0) return;
    const copy = this.pending.slice(0, this.filled * 2);
    this.pending.fill(0);
    this.filled = 0;
    this.port.postMessage(copy, [copy.buffer]);
  }
}

function floatToInt16(v) {
  if (v > 1) v = 1;
  else if (v < -1) v = -1;
  return v < 0 ? (v * 0x8000) | 0 : (v * 0x7fff) | 0;
}

registerProcessor('pcm-sender', PcmSenderProcessor);
