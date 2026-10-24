# WebCast 协议 v1（电视端 ↔ 电脑浏览器发送端）

本文件是 **唯一权威契约**。电视端（Java/Android）与网页发送端（JS）必须严格遵守。
修改协议必须同时改两端，并在此记录版本。

## 1. 传输层

- 电视端在局域网内启动 **HTTPS** 服务，默认端口 **8443**（可用时同时启动 HTTP 8080，仅做 302 跳转）。
- 证书为自签名 RSA-2048/SHA-256，SAN 含 `webcast.local`、当前局域网 IP、`127.0.0.1`。
  Chrome 首次访问需点「高级 → 继续前往」；绕过警告后仍是 secure context，`getDisplayMedia` 可用。
- 发送端页面从电视加载（`https://<tv-ip>:8443/`），因此与 WebSocket 同源。
- WebSocket 端点：`wss://<tv-ip>:8443/ws`，单向媒体流（电脑 → 电视），控制消息双向。

## 2. HTTP 路由

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/` | 发送端页面 index.html |
| GET | `/app.js` `/style.css` `/worklet.js` | 静态资源（可带 `?v=` 查询串） |
| GET | `/api/info` | 设备信息 JSON |
| GET | `/health` | 纯文本 `ok`，测试用 |
| GET | `/ws` | WebSocket 升级 |
| GET | 其它 | 404 |

`/api/info` 响应示例：

```json
{
  "name": "WebCast",
  "model": "Android 4.3 电视",
  "version": "1.0.0",
  "protocol": 1,
  "ip": "192.168.6.169",
  "port": 8443,
  "width": 1920,
  "height": 1080,
  "fps": 60,
  "audio": true,
  "codecs": ["avc1.42E028", "avc1.4D4028"],
  "maxWidth": 1920,
  "maxHeight": 1080
}
```

## 3. WebSocket 消息

- **文本帧** = UTF-8 JSON 控制消息，字段 `t` 表示类型。
- **二进制帧** = 媒体数据，第 1 字节为媒体类型，见下。
- 文本/二进制消息的先后顺序必须保留：`video-config` 必须先于第一个视频二进制帧。

### 3.1 电脑 → 电视

| 消息 | 字段 | 说明 |
| --- | --- | --- |
| `hello` | `v`:1, `ua`:string | 连接后立即发送 |
| `video-config` | `codec`(string), `width`(int), `height`(int), `desc`(base64 string), `fps`(int, 可选) | `codec` 为 `avc1.42E028` 等；`desc` 为 WebCodecs `decoderConfig.description`（avcC box）的 base64 |
| `audio-config` | `format`:"s16le", `rate`:48000, `channels`:2 | 有音频时发送，必须先于音频帧 |
| `overlay` | `show`:boolean | 电视投屏状态条（左上角 FPS/码率）显示/隐藏，默认 true；可在投屏中切换 |
| `stop` | — | 发送端主动停止投屏 |
| `resize` | `width`, `height` | 可选，暂不实现（v1 忽略） |

**二进制视频帧**（8 字节头 + 负载）：

```
偏移 0  u8   0x01                       媒体类型：视频
偏移 1  u8   flags                        bit0 = 1 关键帧(IDR)
偏移 2  u16  reserved = 0
偏移 4  u32  ptsMs（大端，毫秒，从发送端 performance.now() 取整）
偏移 8  ...  AVCC 负载：每个 NAL = u32 长度(大端) + NAL 字节
```

**二进制音频帧**（4 字节头 + 负载）：

```
偏移 0  u8   0x02                       媒体类型：音频
偏移 1  u8   reserved = 0
偏移 2  u16  seq（大端，递增，可回绕）
偏移 4  ...  PCM s16le，交织，48000Hz，2 声道，每帧 20ms（960 样本/声道 = 3840 字节）
```

### 3.2 电视 → 电脑

| 消息 | 字段 | 说明 |
| --- | --- | --- |
| `hello-ack` | `v`:1, `name`, `model`, `width`, `height`, `maxWidth`, `maxHeight`, `fps`, `audio`, `codecs` | 收到 hello 后回；`codecs` 为电视可解码的 codec 串列表 |
| `stats` | `fps`(解码帧率), `kbps`(接收码率), `decodeMs`(平均解码耗时), `drops`(丢弃帧数) | 每 1000ms 一条 |
| `need-keyframe` | — | 解码器需要 IDR（启动、丢帧后、surface 重建） |
| `error` | `message` | 严重错误，随后服务端可能关闭连接 |
| `stop` | — | 电视要求停止（例如 Activity 退到后台） |

### 3.3 时序

1. 页面加载 → `GET /api/info` 显示电视信息。
2. 用户点「开始投屏」→ 打开 `wss://.../ws`。
3. 连上 → 发送 `hello`；等待 `hello-ack`；超时 8s 报错。
4. 调用 `getDisplayMedia({video:{frameRate:30}, audio:...})`（必须在点击手势内同步调用）。
5. 选择编码参数 → 创建并配置 `VideoEncoder`（`latencyMode:"realtime"`）。
6. 编码器第一个输出带 `decoderConfig` → 发送 `video-config`，随后发送二进制帧。
7. 有音频轨 → `audio-config` + 音频二进制帧。
8. 每 2s 强制关键帧；收到 `need-keyframe` 时下一帧强制 IDR。
9. 状态条开关：hello-ack 后发送一次 `{t:"overlay", show:true|false}`，用户切换时立即再发。
10. 停止：`stop` → 关闭 encoder/tracks → 关闭 ws。

## 4. 编码参数约定

- 视频编码仅用 **H.264 Baseline/Main**（无 B 帧）：默认 `avc1.42E028`（Baseline 4.0），备选 `avc1.4D4028`（Main 4.0）。发送端用 `VideoEncoder.isConfigSupported` 择优。
- 参数由发送端界面选择，`video-config` 的 `width/height/fps` 携带实际值（电视端按尺寸重建解码器；fps 仅作参考，不参与解码配置）：
  - 分辨率：1920×1080（默认）/ 1600×900 / 1280×720 / 1024×576；不得超电视 `maxWidth/maxHeight`。
  - 帧率：30（默认）/ 50 / 60 fps；电视面板为 1080p60，60fps 已真机验证可硬解。
  - 码率：2 / 4 / 6 / 8 / 10 / 12 / 16 / 20 Mbps，默认自动推荐：
    1080p30→8、1080p50/60→12；1600×900→6/10/10；1280×720→4/8/8；1024×576→2/4/4。
- 关键帧间隔 2s；背压阈值 `max(3MB, bitrate×2s/8)`。
- 音频：PCM 裸流（无编码），48kHz 立体声 s16le，端到端延迟 < 100ms 为佳。
- 背压：`ws.bufferedAmount > 3MB` 时跳过采集/编码（不得丢弃已编码帧）；恢复后继续，必要时请求关键帧。
- 电视端解码队列上限约 12 帧；超过则丢弃并回 `need-keyframe`，由发送端补 IDR。

## 5. 电视端处理约定

- 收到 `video-config` → 校验 `codec` 白名单、尺寸 ≤ 1920×1080 → 重建 MediaCodec（`video/avc`，硬件优先）。
- 用 `desc`（avcC）解析出 SPS/PPS，以 Annex-B 写入 MediaFormat `csd-0`/`csd-1`；输入帧把 AVCC 长度前缀转成 Annex-B（`00 00 00 01`）。
- 解码输出直接渲染到 SurfaceView（`releaseOutputBuffer(index, true)`），不做软件转换。
- 首帧到达后 Activity 切到全屏播放态；断流 3s 或收到 stop → 回到待机页（显示地址/提示）。
- `stats` 每秒最多一条，避免占用带宽。
