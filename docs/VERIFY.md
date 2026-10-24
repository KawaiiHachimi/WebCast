# 验证记录（真机）

验证设备：**一台 2013 年发布的 Android 4.3 电视**（MStar Napoli，Cortex-A9 ×4，1.5GB RAM）
系统：**Android 4.3（API18，armeabi-v7a）**，1920×1080，显示 50Hz
电视地址：`192.168.6.169`（Wi-Fi），开发机：macOS（Chrome 156），同一局域网
构建产物：`dist/WebCast.apk`（约 2.5MB，minSdk/targetSdk 18，DEX 035，仅 v1 签名）

## 1. 构建与安装

```text
$ ./build.sh
Verified using v1 scheme (JAR signing): true
package: name='app.webcast' versionCode='1' versionName='1.0.0'
sdkVersion:'18'
targetSdkVersion:'18'
Built dist/WebCast.apk

$ adb install -r dist/WebCast.apk     # userdebug 固件，需先 adb root
Success
$ adb shell am start -n app.webcast/.MainActivity
```

- DEX 035 / v1 签名，符合 4.3 安装要求；Conscrypt native 位于 `lib/armeabi-v7a/libconscrypt_jni.so`。
- 单位测试：`./tools/run-media-tests.sh` → `H264UtilTest: 68 passed, 0 failed / RESULT: PASS`。

## 2. TLS / 浏览器兼容（关键前提）

Android 4.3 原生 JSSE 无法与 Chrome 156 握手，因此服务端使用内置 Conscrypt 2.5.3：

```text
I/WebCastTls: TLS ready with Conscrypt 1.0
I/WebCastWeb: HTTPS listening on 192.168.6.169:8443
I/WebCastWeb: TLS TLSv1.3 TLS_CHACHA20_POLY1305_SHA256 from 192.168.6.156
```

```text
$ curl -sk https://192.168.6.169:8443/api/info
{"name":"WebCast","model":"Android TV","version":"1.0.0","protocol":1,
 "ip":"192.168.6.169","port":8443,"width":1920,"height":1080,"fps":60,
 "audio":true,"codecs":["avc1.42E028","avc1.4D4028"],"maxWidth":1920,"maxHeight":1080}

$ curl -s http://192.168.6.169:8080/
Moved to https://192.168.6.169:8443/
```

Chrome 156（`--ignore-certificate-errors` 模拟用户点「继续前往」后）加载发送页，页面兼容性自检输出：

```json
secure context = true；getDisplayMedia / VideoEncoder / AudioEncoder 均可用；
H.264 avc1.42E028 / avc1.4D4028 编码可用。
```

结论：自签名证书在用户点「继续前往」后仍是 **secure context**，`getDisplayMedia` 与 WebCodecs 可用；
Chrome 仅报 `ERR_CERT_AUTHORITY_INVALID`（自签，预期）。电视端只接受局域网来源（site-local / loopback）。

## 3. 视频端到端（合成画面，无需屏幕采集权限）

页面演示模式（`?demo=1&auto=1`）用 canvas 动画代替屏幕采集，其余编码/传输/解码路径与真实投屏完全一致。
开发机无头 Chrome 1080p30：

- 发送端：`status=投屏中, fps=27.0, bitrate=3.18 Mbps, encoder=avc1.42E028·1920×1080·8Mbps·30fps`
- 电视端 `adb logcat -s WebCastMedia WebCastService`：

```text
I/WebCastService: video config avc1.42E028 1920x1080 desc=34B
I/WebCastMedia: begin video: codec=avc1.42E028 1920x1080 SpsPps{sps=1,pps=1,lengthSize=4,profile=0x42,level=0x28}
I/WebCastMedia: MediaCodec started: OMX.MS.AVC.Decoder (OMX.MS.AVC.Decoder)
I/WebCastMedia: decoder running: 1920x1080 lengthSize=4
W/WebCastMedia: need keyframe: decoder-started
I/WebCastService: keyframe requested: decoder-started
I/WebCastMedia: decoder output format: 1920x1088
I/WebCastMedia: first video frame rendered: 1920x1088
```

- 发送端页面显示电视回报：`电视解码 26 fps · 接收 2978 kbps · 解码 49ms · 丢帧 0`。
- 用户手动用 Edge 实测：TextureView 版本画面正常（用户确认），真实屏幕采集通路可用。
- 分辨率/帧率/码率改为电脑页面自选（1080p/900p/720p/576p、30/50/60fps、2–20Mbps）；旧 720p 档位同样通过：`video-config 1280×720 desc=33B`，`decoder output format: 1280x720`。
- 首帧画面正常（TextureView 渲染，电视端左上角实时显示 FPS/码率/解码耗时）。
- 电视待机页（已去掉二维码，右侧改为投屏小贴士）：[tv-idle.png](screenshots/tv-idle.png)

### 渲染方式：SurfaceView → TextureView（真机黑屏修复）

最初使用 `SurfaceView` + MediaCodec 直出 HWC 视频层：解码日志正常（`first video frame rendered`），
`dumpsys SurfaceFlinger` 也能看到 HWC 图层：

```text
HWC | ... | 32315659 (YV12) | [0,0,1920,1080] | [7,0,1913,1080] SurfaceView
GLES | ... | app.webcast/app.webcast.MainActivity
```

但**真机面板上是黑屏**（HWC 图层存在但未显示），且 `adb screencap` 同样黑。
改为 `TextureView`（GPU 在 App 窗口内合成）后，真机与截图都正常；1080p60 下仍有余量。
当前交付版本使用 TextureView，真机画面正常。

## 3.1 60fps / 分辨率 / 码率（可选参数）

发送页支持自选分辨率（1080p/900p/720p/576p）、帧率（30/50/60）、码率（2–20Mbps，默认自动推荐）。
参数在投屏中锁定；`?demo=1` 也遵守所选参数（电视端可不依赖录屏权限验证 60fps/码率）。
开发期用注入的 1080p60（12Mbps）合成码流验证电视硬解：

```text
发送端（15s）：938 帧 / 9 个关键帧 / 约 1.9MB
电视回报 stats（后 6 条）：
fps=59/65/61/61/64/56，drops=0，decodeMs=53/59/55/54/44/63，kbps≈1070–1128
```

- 结论：MStar 硬解 1080p60 稳定，0 丢帧；单帧解码 44–63ms（流水线吞吐足够 60fps）。
- 电视显示：`dumpsys display` 报告 `1920 x 1080, 60.000004 fps`（此前空闲时为 50Hz，随内容/输入切换）。
- 4K/更高帧率未支持（发送端不上采样；电视硬解上限 1080p）。

## 4. 声音（PCM 通路）

`?demo=1&auto=1&sound=tone`（440Hz 测试音，走与真实采集完全相同的 AudioWorklet→WSS→AudioTrack 路径）：

```text
I/WebCastMedia: audio started: 48000 Hz, 2 ch, trackBuffer=23040 bytes (~120 ms), queueMax=240 ms (6 x 40ms frames)
I/WebCastMedia: audio diag: track=120ms, queued=0ms, written=926KB, frames=247, drops=0 frames, underruns~=0, headFrames=230400
I/WebCastMedia: audio diag: track=120ms, queued=0ms, written=1867KB, frames=498, drops=0 frames, underruns~=0, headFrames=472320
I/WebCastMedia: audio diag: track=120ms, queued=0ms, written=2805KB, frames=748, drops=0 frames, underruns~=0, headFrames=710708
```

`dumpsys media.audio_flinger` 可见 48kHz/2ch 的 active AudioTrack（client 为 app.webcast）。
结论：传输与电视端播放通路正常（≈192KB/s，符合 48kHz 立体声 16bit），**0 丢帧、0 underrun**。

## 5. 交互

- App 启动即全屏待机页，服务在 `CastService` 中运行；Activity 退到后台服务不中断。
- 电视收到 video-config 立即显示 SurfaceView（"正在建立画面…"），首帧后切换为全屏视频与状态浮层
  （`正在投屏 · 26 FPS · 3291 kbps · 解码 47ms`）。
- 遥控器返回键：`I/WebCastService: session stopped: stopped by TV`，回到待机页；发送端随后断开/显示未连接。
- 发送端点「停止投屏」：电视 `session stopped: sender requested stop`，回到待机页。

## 6. 尚未在本机验证 / 需人工确认

1. **真实屏幕采集（getDisplayMedia）**：开发机 macOS 未给浏览器「屏幕录制」权限，自动化测试返回
   `NotAllowedError/NotReadableError（Could not start video source）`。页面会显示明确中文提示并清理，不会卡死。
   用户后续用 Edge 手动实测已确认画面正常（TextureView 版本），真实采集通路可用；
   自动化测试仅覆盖 demo 模式（编码、AVCC、WSS、硬解、渲染）。
2. macOS 共享「整个屏幕」时的系统声音采集（浏览器平台限制，通常没有；标签页音频/麦克风可用）。
3. A/V 主观同步效果（v1 无时间戳对齐，音频约晚 100–200ms）。
4. 长时间运行稳定性（本次验证为分钟级；内存/句柄释放逻辑已按 API18 约束实现并单测覆盖关键解析）。
5. 其他品牌老电视的 MediaCodec/渲染兼容性（已做硬解 → 软解回退；本机 SurfaceView 黑屏后改用 TextureView；H.265 未启用）。

## 7. 复现命令

```bash
# 构建 + 安装
./build.sh && adb install -r dist/WebCast.apk && adb shell am start -n app.webcast/.MainActivity

# 网络/TLS/接口
curl -sk https://192.168.6.169:8443/api/info
echo | openssl s_client -connect 192.168.6.169:8443 -servername webcast.local 2>&1 | grep -E "Protocol|Cipher"

# 端到端（手动）
adb logcat -c && adb logcat -s WebCastTls WebCastWeb WebCastService WebCastMedia
# 电脑浏览器打开 https://192.168.6.169:8443，点「开始投屏」
# 观察：MediaCodec started / first video frame rendered / 每秒 stats

# 声音：页面选择「共享音频」或「麦克风」
adb logcat -d -s WebCastMedia | grep -i audio
```

## 8. 投屏状态条开关（v1.1.0）

电脑页面「投屏状态条」开关通过 WebSocket 文本消息 `{"t":"overlay","show":false}` 控制电视左上角浮层：

- 默认显示：截图 [tv-casting.png](screenshots/tv-casting.png) 可见「正在投屏 · 28 FPS · 6456 kbps · 解码 117ms」。
- 关闭后同一画面只保留视频内容，浮层消失（真机截图核对，页面开关切换即时生效）。
- 该开关只影响浮层，不影响解码、统计与返回键停止。
