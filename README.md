# WebCast

> 把电脑屏幕投到客厅电视，只需要一个浏览器。

WebCast 是一款面向 **Android 4.3（API 18）电视**的局域网投屏接收端。电视安装 App 后会自动开启局域网服务并显示访问地址；电脑用 Chrome / Edge 打开这个地址，点击「开始投屏」，电视即可全屏显示电脑画面。电脑端无需安装任何软件，画面由电视硬件解码后原生渲染。

**仓库**：<https://github.com/KawaiiHachimi/WebCast> · **下载 APK**：<https://github.com/KawaiiHachimi/WebCast/releases/latest>

![电视待机页](docs/screenshots/tv-idle.png)

![投屏中](docs/screenshots/tv-casting.png)

## 特性

- **电脑零安装**：浏览器打开即用，支持共享整个屏幕、窗口或单个标签页。
- **原生硬件解码**：电视端使用 MediaCodec 硬解 H.264，并由 TextureView 全屏渲染，不依赖 WebView。
- **高清流畅**：最高 1080p / 60fps，实际帧率与电视解码性能自动匹配。
- **画质可调**：分辨率（1920×1080 / 1600×900 / 1280×720 / 1024×576）、帧率（30 / 50 / 60 fps）、码率（2–20 Mbps）按需选择。
- **支持声音**：电脑声音或麦克风以 48kHz 立体声 PCM 传到电视播放。
- **状态条开关**：可在电脑页面上显示或隐藏电视左上角的实时帧率 / 码率状态条。
- **遥控器友好**：全屏播放，按返回键即可停止投屏并回到待机页。
- **局域网安全**：HTTPS/WSS 加密传输，仅接受局域网设备访问。
- **轻量**：APK 约 0.9MB，兼容 Android 4.3 及以上的老电视。

## 环境要求

| 设备 | 要求 |
| --- | --- |
| 电视 | Android 4.3 及以上（API 18+），支持 H.264 硬件解码 |
| 电脑 | Chrome / Edge 94+（推荐最新版），与电视在同一局域网 |
| 网络 | 建议 5GHz Wi-Fi 或有线网络 |

## 快速开始

### 1. 安装电视端

从 [Releases](https://github.com/KawaiiHachimi/WebCast/releases/latest) 下载 `WebCast.apk`，或使用 adb 安装：

```bash
adb install -r WebCast.apk
adb shell am start -n app.webcast/.MainActivity
```

启动后电视会全屏显示待机页，上面有当前局域网地址，例如 `https://192.168.6.169:8443`。

### 2. 电脑开始投屏

1. 在电脑上用 Chrome / Edge 打开电视上显示的地址。
2. 首次访问可能提示不安全，点「高级 → 继续前往」即可。
3. 在页面中选择分辨率、帧率、码率和声音。
4. 点击「开始投屏」，在浏览器弹窗中选择要共享的屏幕、窗口或标签页。
5. 电视会自动切换到全屏画面；按电视遥控器返回键停止投屏。

> macOS 首次使用时，需要在「系统设置 → 隐私与安全性 → 屏幕录制」中允许浏览器录屏。

## 使用说明

- **声音**：选择「共享系统音频」可传输标签页 / 系统声音；选择「使用麦克风」可采集电脑麦克风。浏览器未提供音轨时会自动仅投屏画面。
- **状态条**：页面上的「投屏状态条」开关控制电视左上角的 FPS / 码率浮层，投屏过程中也可随时切换。
- **画质选择**：局域网带宽充足时推荐 1080p60；网络一般时可选 720p30 或较低码率。
- **重新连接**：停止投屏或断开后，电视会回到待机页，再次点击「开始投屏」即可。

## 技术架构

```
电脑 Chrome/Edge                               电视 Android App
┌──────────────────────────┐                  ┌──────────────────────────────┐
│ getDisplayMedia 屏幕采集  │                  │ CastService 前台服务          │
│   ↓ canvas 缩放           │   WSS (H.264)    │  ├─ HTTPS/WSS 服务 (TLS 1.3)  │
│ WebCodecs H.264 编码      │ ───────────────► │  ├─ MediaCodec 硬件解码       │
│ 声音 AudioWorklet → PCM   │   WSS (PCM)      │  ├─ TextureView 全屏渲染      │
└──────────────────────────┘                  │  └─ AudioTrack 音频播放       │
                                              └──────────────────────────────┘
```

- 电视端使用内置 Conscrypt 提供现代 TLS（Android 4.3 原生 TLS 无法与新版 Chrome 握手）。
- 发送端使用 WebCodecs 编码 H.264，经 WebSocket 传输；电视端硬解后直接渲染，延迟低、CPU 占用小。
- 协议细节见 [PROTOCOL.md](PROTOCOL.md)。

## 从源码构建

需要 JDK 17+、Android SDK Platform 18 与 Build-Tools（无需 Gradle）：

```bash
./tools/make-cert.sh          # 首次构建前生成自签名证书
./build.sh                    # 产物：dist/WebCast.apk
./tools/run-media-tests.sh    # 媒体工具单元测试
```

更多设计文档见 [docs/](docs/)。

## 致谢

- TLS：[Conscrypt](https://github.com/google/conscrypt)（Apache-2.0）
- 产品思路参考：[KawaiiHachimi/WebRTC_ScreenShare](https://github.com/KawaiiHachimi/WebRTC_ScreenShare)
