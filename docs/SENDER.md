# WebCast 电脑端发送网页（SENDER）

发送端是**纯静态页面**，由电视端 App 内置的 HTTPS 服务直接提供。电脑 Chrome/Edge 打开
`https://<电视IP>:8443/` 即可使用，不需要安装、构建或外部 CDN。

- 协议契约：[`../PROTOCOL.md`](../PROTOCOL.md)（唯一权威，本页实现以其为准）
- 文件：`app/assets/web/`（index.html / style.css / app.js / worklet.js）、本文件
- 自测工具：`tools/mock-ws.js`（无第三方依赖，模拟电视端并逐条校验协议）

## 1. 文件与职责

| 文件 | 说明 |
| --- | --- |
| `app/assets/web/index.html` | 页面结构：电视信息、分辨率/帧率/码率/声音、开始/停止、统计、预览、错误/提示条 |
| `app/assets/web/style.css` | 中文深色主题、响应式布局（无外部字体/图标依赖） |
| `app/assets/web/app.js` | 参数选择、采集、WebSocket 状态机、VideoEncoder、音频、统计、清理、演示模式 |
| `app/assets/web/worklet.js` | AudioWorklet：下混立体声、浮点→s16le、20ms 分帧 |
| `tools/mock-ws.js` | 自测工具：模拟电视 HTTP + WebSocket 服务端，按 PROTOCOL 逐条校验（可选拉起无头 Chrome） |

电视端 App 的静态资源路由（`/`、`/app.js`、`/style.css`、`/worklet.js`）见 PROTOCOL.md §2；
页面内所有请求都是相对路径（`api/info`、`worklet.js`），因此与电视同源，无需配置地址。

## 2. 使用方式

1. 电视端启动投屏服务，待机页显示 `https://<电视IP>:8443`。
2. 电脑 Chrome/Edge 打开该地址；首次会提示证书不受信任，点「高级 → 继续前往」
   （仍是 secure context，`getDisplayMedia`/WebCodecs 可用）。
3. 选择分辨率、帧率、码率、声音（关闭 / 共享系统音频 / 麦克风），点「开始投屏」。
4. 浏览器询问要共享的屏幕/窗口，选择后电视开始显示电脑画面。
5. 点「停止投屏」或关闭页面结束。

参数在开始投屏后锁定（避免运行中重建编码器），停止后可再修改。

## 3. 启动时序（与 PROTOCOL.md §3.3 对应）

```
页面加载 → GET /api/info → 显示电视信息，并按 maxWidth/maxHeight 禁用超限分辨率选项
选择 分辨率 / 帧率 / 码率 / 声音（码率默认自动推荐，显示当前 Mbps）
点击「开始投屏」
  ├─ 同步兼容性检查（secure context / VideoEncoder / getDisplayMedia / AudioWorklet）
  ├─ 读取所选参数并按其电视上限钳制（state.target）
  ├─ 同步调用 getDisplayMedia()          ← 必须在点击手势内，任何 await 之前
  │    video: { frameRate: 所选fps, width:{ideal:所选宽}, height:{ideal:所选高} }
  ├─ 连接 wss://location.host/ws
  ├─ 发送 hello {v:1, ua}
  ├─ 等待 hello-ack（8s 超时；连接本身 10s 超时）
  ├─ 选择编码器：isConfigSupported(avc1.42E028 → avc1.4D4028)
  ├─ 创建 VideoEncoder（width/height/bitrate/framerate 均取所选值，latencyMode:"realtime"）
  ├─ 开始采集循环（requestVideoFrameCallback；demo 模式按所选 fps 定时器）
  ├─ 首个带 decoderConfig.description 的输出 → 发 video-config（base64 avcC，fps = 所选值）
  ├─ 之后每帧：8 字节头 + AVCC 负载
  └─ 音频（异步）：AudioContext(48000) + AudioWorklet → audio-config → 4 字节头 + s16le
```

关键点：`getDisplayMedia` 是 `start()` 的第一个异步调用，之前只做同步检查，保证用户手势有效。
麦克风授权**不阻塞投屏**：视频先开始，授权成功后再异步挂上音频；20s 未授权则放弃并提示。

## 4. 实现要点

### 4.1 可调参数（分辨率 / 帧率 / 码率）

**分辨率**（`/api/info` 的 `maxWidth/maxHeight` 钳制）：

| 选项 | 说明 |
| --- | --- |
| 1920×1080 | 默认 |
| 1600×900 | |
| 1280×720 | |
| 1024×576 | |

- 加载 `/api/info` 后，超出电视能力的选项自动 `disabled`；若当前选择超限，自动改选最大可用档并提示。
- 开始编码前（拿到 `hello-ack` 后）再按电视上限做一次等比钳制，尺寸取偶数；若发生钳制，码率按像素比例缩放。

**帧率**：

| 选项 | 说明 |
| --- | --- |
| 30 fps | 默认 |
| 50 fps | 可选，负载更低 |
| 60 fps | 真机硬解实测 59–65fps、0 丢帧、解码 44–63ms；电视面板 60Hz，50/60fps 均可选，60fps 动态更顺滑 |

**码率**：`自动（推荐）` + 预设 `2/4/6/8/10/12/16/20 Mbps`，控件下方实时显示当前使用值。

自动推荐（Mbps）：

| 分辨率 \ 帧率 | 30 | 50 | 60 |
| --- | --- | --- | --- |
| 1920×1080 | 8 | 12 | 12 |
| 1600×900 | 6 | 10 | 10 |
| 1280×720 | 4 | 8 | 8 |
| 1024×576 | 2 | 4 | 4 |

要求中的示例（1080p30→8、1080p50/60→12、720p30→4）均在表中。

**所有链路都使用所选值**：

- `getDisplayMedia` 的 `frameRate` / `width.ideal` / `height.ideal`；
- `VideoEncoder` 的 `width` / `height` / `bitrate` / `framerate`；
- 编码 canvas 目标尺寸、`VideoFrame.duration = 1e6/fps`；
- 采集循环间隔 `1000/fps`（demo 模式与降级定时器都遵守）；
- `video-config.fps`；
- 背压阈值 `max(3MB, bitrate×2s/8)`（如 8Mbps→3MB、20Mbps→5MB）；
- 「编码：」信息显示实际 codec / 分辨率 / 码率 / 帧率；
- `?demo=1` 同样遵守所选参数（用于电视上验证 60fps/码率，不需要屏幕采集权限）。

### 4.1b 编码器选择

- codec 依次尝试 `avc1.42E028`（Baseline 4.0）→ `avc1.4D4028`（Main 4.0），
  用 `VideoEncoder.isConfigSupported()` 逐个探测；`hello-ack.codecs` 提供时优先交集。
- `latencyMode:"realtime"`、`avc:{format:"avc"}`（保证输出 AVCC，且首帧元数据里有 avcC description）。
- 每 2s 置一次 `forceKeyframe`；收到 `need-keyframe` 立即置位，下一帧以 `{keyFrame:true}` 编码。
- 背压：`ws.bufferedAmount > 阈值` 时**跳过采集/编码**（不丢已编码帧），恢复后自动补 IDR；
  另外 `encodeQueueSize > 6` 时也跳过采集，防止主线程排队堆积。

### 4.2 采集与缩放

- 屏幕模式优先 `video.requestVideoFrameCallback()`，不支持时降级为 `setInterval(1000/所选fps)`。
- 每帧把采集源等比绘制到隐藏的 `#encode-canvas`（黑边填充，目标尺寸=所选分辨率），
  再由 `new VideoFrame(canvas)` 送入编码器；`timestamp` 取 `performance.now()*1000`（微秒），
  编码输出的 `ptsMs = round(timestamp/1000)`。
- `?demo=1` 时用 1920×1080 canvas 动画（彩条 + 时钟 + 帧计数 + 移动色块）代替屏幕采集，
  按所选 fps 出帧、按所选分辨率缩放后编码；画面上会标注「采集 → 编码」的实际参数。

### 4.3 视频帧封装（PROTOCOL.md §3.1）

```
偏移 0  u8  0x01                        媒体类型：视频
偏移 1  u8  chunk.type === 'key' ? 1:0  bit0 = 关键帧
偏移 2  u16 0
偏移 4  u32 ptsMs（大端）
偏移 8  ... chunk.copyTo(...)            AVCC（每 NAL = u32 长度 + NAL），原样
```

`video-config` 只发一次，且保证先于第一个视频二进制帧：首帧输出缺少
`decoderConfig.description` 时丢弃该帧并请求关键帧，等带 description 的输出再发。

### 4.4 音频（PROTOCOL.md §3.1 音频帧）

- 音源：`getDisplayMedia({audio:true})` 的 display 音轨，或 `getUserMedia` 麦克风。
- `new AudioContext({sampleRate:48000, latencyHint:"interactive"})` +
  `audioWorklet.addModule("worklet.js")` + `AudioWorkletNode('pcm-sender')`。
- `worklet.js`：任意声道数→立体声下混（单声道复制到左右，>2 声道奇偶平均），
  浮点裁剪到 [-1,1] 后转 s16le，每 960 帧/声道（48kHz 下 20ms）postMessage 一帧，
  即 1920 个 int16 = **3840 字节**。
- 先发文本 `audio-config {format:"s16le",rate:48000,channels:2}`，再发二进制：
  `u8 0x02 | u8 0 | u16 seq(大端，逐帧递增回绕) | s16le 交织 PCM`。
- 音频节点输出静音并连到 `destination`，只为让音频图保持运行，不会回放/回声。
- 音频帧与视频共用同一个背压阈值，拥塞时音频帧优先丢弃（视频已编码帧不丢）。

### 4.5 电视消息处理

- `stats`：显示电视解码 fps、接收码率、解码耗时、丢帧；超过 3.5s 没更新则显示「— / 统计超时」。
- `need-keyframe`：下一帧强制 IDR。
- `error`：红色错误条，停止投屏。
- `stop`：电视要求停止，提示后清理。

### 4.6 统计与延迟估算

本端每秒统计发送帧率、发送码率、关键帧数、跳过/丢帧数；「延迟（估算）」=
`ws.bufferedAmount / 当前码率字节每毫秒 + 电视端 decodeMs`（约等于本端发送缓冲时长 + 电视解码耗时），
仅作参考，不是精确端到端时延。

### 4.7 兼容性与错误提示

启动前检查并给出中文提示（致命项会禁用「开始投屏」）：

- 非安全上下文：提示用 `https://<电视IP>:8443/` 打开并信任自签名证书；
- 无 `VideoEncoder`：提示 Chrome / Edge 94+；
- 无 `getDisplayMedia`：提示用桌面版 Chrome/Edge（`?demo=1` 不受此限制）；
- 无 AudioWorklet：仅提示声音不可用，画面仍可投屏。

`?demo=1` 在非安全上下文（如 `http://192.168.x.x`）同样会被拦截，因为 WebCodecs 本身要求 secure context；
自动化测试请用 `http://127.0.0.1`/`localhost`（浏览器视为安全上下文）或电视的 HTTPS 地址。

### 4.8 清理

停止/失败/断线/页面关闭时统一清理：取消采集回调、`encoder.flush()` 后关闭（保证已编码帧先发完）、
关闭 AudioContext/停止音轨、停止所有 MediaStreamTrack、发送 `stop` 并关闭 WebSocket。
`pagehide`/`beforeunload` 走同步兜底清理。授权弹窗在停止后才返回时，也会立即 stop 对应轨道。

## 5. 调试与自动化参数

| 参数 | 说明 |
| --- | --- |
| `?demo=1` | 用 canvas 动画测试图代替屏幕采集（不需要共享屏幕权限，仍遵守所选参数） |
| `&auto=1` | 页面就绪后自动开始（配合 `demo=1` 可在电视上直接验证） |
| `&res=1600x900` | 预设分辨率（`1920x1080`/`1600x900`/`1280x720`/`1024x576`） |
| `&fps=60` | 预设帧率（`30`/`50`/`60`） |
| `&bitrate=12` | 预设码率（`auto` 或 `2/4/6/8/10/12/16/20`） |
| `&quality=720p` | 兼容旧参数，等价于 `&res=1280x720` |
| `&selftest=1` | 每秒及停止时向 `/__selftest` POST 运行数据；同时暴露 `window.__WEBCAST_DEBUG__` 供离线测试 |
| `&sound=mic\|display\|none` | 预设声音选项 |
| `&sound=tone` | **仅测试**：用 440Hz 振荡器代替真实音源，验证音频链路（无头/无麦克风环境用） |

电视上验证 60fps / 码率示例：

```
https://<电视IP>:8443/?demo=1&auto=1&res=1600x900&fps=60&bitrate=12
https://<电视IP>:8443/?demo=1&auto=1&res=1920x1080&fps=50
```

页面会按这些参数编码，并在「编码：」与统计面板显示实际值；参数在投屏中锁定，停止后可继续用 UI 修改。

## 6. 自测工具与结果

### 6.1 `tools/mock-ws.js`

无第三方依赖（只用 Node 内置模块），手写 WebSocket 服务端，同时提供静态文件与 `/api/info`，
并按协议校验发送端消息：hello → hello-ack、video-config（codec 白名单/尺寸上限/avcC 结构/fps 字段）、
视频 8 字节头（类型、关键帧位、reserved、pts 单调、AVCC 长度）、audio-config、音频 4 字节头
（类型、reserved、seq 连续、20ms=3840 字节负载）、need-keyframe 后补 IDR、stop 流程。

```bash
# 1) 语法检查（三个 JS 文件必须通过）
node --check app/assets/web/app.js
node --check app/assets/web/worklet.js
node --check tools/mock-ws.js

# 2) worklet.js 离线单测（下混/削波/20ms 分帧/3840 字节/close）
node tools/mock-ws.js --test-worklet

# 3) task-4 参数离线单测（本机临时脚本，位于 work/，不启动浏览器）：
#    DOM 桩 + vm 加载 app.js，校验默认值/推荐表/手动码率/电视上限钳制/提示文本/背压/静态接线
node work/sender-param-test.js

# 4) task-4 协议校验（本机临时脚本，不启动浏览器）：
#    Node 手写 WS 客户端模拟发送端，按 5 组参数组合发送 hello/video-config/视频帧/音频帧/
#    need-keyframe 补 IDR/stop，由本工具逐条校验并输出 PASS/FAIL
node work/protocol-check.js

# 5) 其他环境若允许浏览器，仍可用无头模式：
node tools/mock-ws.js --chrome-test --frames 90 --need-keyframe-at 1
node tools/mock-ws.js --chrome-test --audio --frames 90 --need-keyframe-at 1
```

### 6.2 本机验证记录（2026-10-24，task-4；按要求不启动 Chrome/Edge）

| 用例 | 结果 |
| --- | --- |
| `node --check` app.js / worklet.js / mock-ws.js | 通过 |
| 参数离线单测 `work/sender-param-test.js`（17 用例：默认值、12 组自动推荐、手动码率、电视上限钳制、选项禁用/自动改选、提示文本、背压阈值、旧档位移除、静态接线） | 17/17 PASS |
| 协议校验 `work/protocol-check.js` × `tools/mock-ws.js` | 5/5 组合 PASS |
| └ 1920×1080@30 8Mbps | PASS：video-config 尺寸正确、24 视频帧、2 关键帧、need-keyframe→IDR 16ms |
| └ 1600×900@50 10Mbps | PASS：video-config fps=50、24 帧、2 关键帧、need-keyframe→IDR |
| └ 1280×720@60 8Mbps（含音频） | PASS：fps=60、24 帧、12 音频帧（3840B/帧、seq 连续） |
| └ 1024×576@30 2Mbps | PASS：小分辨率/低码率链路 |
| └ 1920×1080@60 12Mbps（含音频） | PASS：fps=60、12 音频帧 |
| 上一版本真机验收（Lead，Chrome 桌面） | 1080p30 硬解、UI、/api/info、`?sound=tone` 音频通路均通过 |

说明：
- `tools/mock-ws.js` 对 `video-config.fps != 30` 只打印**警告**（历史校验假设固定 30fps），不影响 PASS；
  协议 v1 中 `fps` 是可选参考字段，电视端可忽略，本轮 50/60fps 组合均已通过其余全部校验。
- 本轮按用户要求**未启动浏览器**，真实 `getDisplayMedia` 与真实音频仍未在本机自动化验证（见 §7）。
- 参数单测通过 `?selftest=1` 暴露的 `window.__WEBCAST_DEBUG__` 调用纯函数完成，不涉及 DOM 交互。

## 7. 已知限制与待联调

1. **真实屏幕采集仍需人工验证**：`getDisplayMedia` 需要用户选择共享内容；本轮按要求未启动浏览器，
   自动化覆盖了「参数→缩放→编码→封装→发送」链路，真实屏幕采集请在桌面 Chrome 人工点一次确认
   （macOS 屏幕录制权限、整屏/窗口/标签页选择）。
2. **60fps 与面板 60Hz**：电视面板实测为 60Hz，50/60fps 均可选；60fps 真机硬解实测 59–65fps、
   0 丢帧、解码 44–63ms，动态更顺滑；50fps 可用于降低编码/网络负载。
3. **码率上限 20 Mbps**：依赖局域网带宽与电视硬解余量；1080p60 + 20Mbps 属高负载组合，建议优先用自动推荐。
   `2 Mbps @ 1080p` 在高动态画面下画质会明显下降。
4. **真实系统音频/麦克风未端到端验证**：无头环境假麦克风会挂起（与页面无关），音频 e2e 使用
   `sound=tone`；`worklet.js` 的下混/分帧另有 `--test-worklet` 单测覆盖。
   真机请手动验证「共享系统音频」和「麦克风」；macOS 上 Chrome 的系统声音共享依赖标签页音频。
5. **延迟是估算值**（发送缓冲折算 + 电视解码耗时），不是严格端到端测量；协议 v1 无 ping/回声测量。
6. **video-config 只发一次**：v1 没有「重新配置」消息；投屏中参数锁定，停止后重开会重新发送。
7. **首帧缺少 description 的处理**：极少数编码器首帧不带 `decoderConfig.description` 时会丢弃该帧并等
   下一个关键帧（≤2s），保证协议「video-config 先于视频帧」不被破坏。
8. `resize` 消息 v1 不实现；投屏过程中不允许切换参数/声音（UI 已禁用，停止后可改）。
9. **电视能力钳制**：若电视 `maxWidth/maxHeight` 低于所选分辨率，会自动等比缩小（偶数尺寸）并按像素比例
   降低码率；「编码：」行显示的是钳制后的实际值。
10. Safari/Firefox 不支持 H.264 WebCodecs 编码，页面会给出兼容性提示。

## 8. 协议歧义记录

PROTOCOL.md §3.1 音频二进制帧注释写「每帧约 20ms（1920 样本/声道 = 7680 字节）」，
但 48kHz 下 **20ms = 960 样本/声道 = 3840 字节**（1920 样本/声道对应 40ms）。
本实现以任务要求与「约 20ms」为准，发送 **3840 字节/帧（1920 个 int16，交织立体声）**。
电视端 `AudioPlayer` 直接写 PCM 即可，写成任意帧长都不影响播放；若后续统一，建议协议改为
「20ms = 960 样本/声道 = 3840 字节」。`tools/mock-ws.js` 对非 3840 字节帧会告警并在 `--audio`
模式下判失败。

另外协议 v1 的 `video-config.fps` 为可选 int，本实现在 30/50/60 三档下分别发送 30/50/60；
电视端按参考字段处理即可（当前电视端接口 `beginVideo` 不含 fps，实际只使用 codec/宽高/avcC）。

## 9. 电视端联调检查清单

- `video-config.desc` 是 avcC（首字节 1、`lengthSizeMinusOne=3`、含 SPS/PPS），可直接解析出 csd-0/csd-1。
- `video-config` 的 `width/height` 为实际编码尺寸（可能因电视上限被钳制），`fps` 可能为 30/50/60。
- 视频负载是 AVCC（u32 长度前缀），需转 Annex-B 后喂 MediaCodec；首帧必为关键帧。
- 每 2s 或收到 `need-keyframe` 后有 IDR。
- 每个视频二进制帧都带 ptsMs（发送端 `performance.now()`，毫秒），可用于音视频同步/丢帧判断。
- 音频帧 3840 字节 = 20ms；`seq` 每帧 +1（回绕），电视端可按 seq 检测丢包。
- 发送端在 `stop` 时先 flush 编码器再发 `stop` 文本并关闭连接；电视端收到后回待机页。
