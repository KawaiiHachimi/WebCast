# 电视端媒体管线（H.264 硬解 → SurfaceView + PCM 音频）

状态：代码已落盘，`H264Util` JVM 单测通过，媒体包以 API18 bootclasspath 编译通过。
**真机集成（CastService + SurfaceView + WebServer 接线）与真机验证由 Lead 完成**，本文最后
一节列出真机上最可能踩的坑以及需要抓的日志。

目标设备：Android 4.3 电视，Android 4.3（API 18，armeabi-v7a，1920×1080，
硬解 `OMX.MS.AVC.Decoder`，1.5 GB RAM）。纯 Java、无 Gradle、无 AndroidX。

---

## 1. 文件与职责

| 文件 | 职责 |
| --- | --- |
| `app/src/app/webcast/media/CastPlayer.java` | 对外唯一 facade（CastService 只调用它），线程安全，聚合视频/音频 |
| `app/src/app/webcast/media/VideoDecoder.java` | MediaCodec 同步 API 的 H.264 解码：csd 配置、AVCC→Annex-B、输入/输出线程、Surface 生命周期、统计 |
| `app/src/app/webcast/media/AudioPlayer.java` | AudioTrack STREAM_MUSIC PCM s16le 播放，有界队列，绝不阻塞视频线程 |
| `app/src/app/webcast/media/H264Util.java` | 纯 Java：avcC 解析出 SPS/PPS（Annex-B）+ AVCC→Annex-B 转换 |
| `tests/media/H264UtilTest.java` | H264Util 的纯 JVM 单测（68 项断言，无 JUnit 依赖） |
| `tools/run-media-tests.sh` | 用系统 javac/java 跑上面的单测 |
| `docs/MEDIA.md` | 本文档 |

日志 Tag 统一为 **`WebCastMedia`**（`adb logcat -s WebCastMedia`）。

---

## 2. 公开 API（签名固定，勿改）

```java
public final class CastPlayer {
  public interface Listener {
    void onVideoReady(int w, int h);
    void onVideoStats(int fps, int kbps, int decodeMs, int drops);
    void onNeedKeyframe(String reason);
    void onVideoEnded(String reason);
  }
  public CastPlayer(Listener l);
  public void setSurface(android.view.Surface s);
  public void beginVideo(String codec, int w, int h, byte[] avccDescription);
  public void pushVideo(byte[] data, int off, int len, boolean keyframe, long ptsMs);
  public void beginAudio(int rate, int channels);
  public void pushAudio(byte[] data, int off, int len);
  public void stopAll();
  public void release();
}
```

### 2.1 Listener 语义

| 回调 | 触发时机 | 调用线程建议 |
| --- | --- | --- |
| `onVideoReady(w,h)` | 每个解码会话**首帧真正渲染后**一次（尺寸取输出格式，回退到 video-config） | 解码输出线程 → 建议 `handler.post` 切 UI 显示播放页 |
| `onVideoStats(fps,kbps,decodeMs,drops)` | 解码会话存活期间约每 1000 ms 一次。`fps`=本秒渲染帧数，`kbps`=本秒收到的视频负载字节×8，`decodeMs`=入队→渲染的平均毫秒，`drops`=**本次 beginVideo 起累计**丢帧数 | 解码输出线程；组装 `stats` JSON 时如果 `WebServer.send` 不是线程安全的，需 post/synchronize |
| `onNeedKeyframe(reason)` | 解码器启动、队列溢出、输入卡顿、坏帧、surface 重建后。500 ms 内最多一次 | 网络线程或解码线程；转发 `need-keyframe` 时注意 WebServer 写通道的线程安全 |
| `onVideoEnded(reason)` | 每个视频会话**最多一次**：EOS / 解码致命错误 / 配置非法，或显式 `stopAll()`（reason=`"stopped"`）。`release()` 不触发 | 网络线程/解码线程/调用 stopAll 的线程 |

`reason` 取值（可直接打日志/上报）：

- `stopped`：CastService 调用了 `stopAll()`。
- `bad-video-config: ...`、`unsupported-codec: ...`、`bad-video-size: WxH`、
  `missing-avcC-description`：`video-config` 非法，不会创建解码器。
- `codec-init-failed`、`codec-start-failed: ...`：所有候选解码器都启动失败。
- `input-error: ...`、`output-error: ...`：MediaCodec 抛异常，会话已销毁。
- `eos`：解码器输出 EOS（当前协议不会发生，防御性处理）。

### 2.2 线程约定

- `pushVideo` / `pushAudio`：WebSocket 网络线程，**不阻塞**（只做拷贝 + 入有界队列）。
- `setSurface`：UI 线程（SurfaceHolder 回调）。surface 丢失时会在 UI 线程停一次解码器，
  正常 <20 ms；异常情况下 join 上限见 §6.4。
- `beginVideo` / `beginAudio` / `stopAll` / `release`：任意线程。`beginVideo` 内部会
  create/configure/start MediaCodec（几十到几百 ms），建议放在网络线程或后台线程。
- **所有 Listener 回调都必须快、不能阻塞**；不要在回调里同步等待网络/UI。如果在
  `onNeedKeyframe` 里直接调用 `stopAll()` 不会死锁（实现里有 self-join 保护），但会带来
  不必要的重入，建议统一 post。

---

## 3. CastService 接线示例

```java
// Service.onCreate()
CastPlayer player = new CastPlayer(new CastPlayer.Listener() {
  @Override public void onVideoReady(int w, int h) {
    // handler.post(() -> { showPlayerView(); playerView.setSize(w, h); });
  }
  @Override public void onVideoStats(int fps, int kbps, int decodeMs, int drops) {
    // 每 1000ms 一次：组装 {"t":"stats","fps":..,"kbps":..,"decodeMs":..,"drops":..} 发给发送端
  }
  @Override public void onNeedKeyframe(String reason) {
    // 发送 {"t":"need-keyframe"}；也可记录 reason
  }
  @Override public void onVideoEnded(String reason) {
    // handler.post(() -> returnToIdle(reason));
  }
});

// SurfaceHolder.Callback（全屏播放页）
public void surfaceCreated(SurfaceHolder h)  { player.setSurface(h.getSurface()); }
public void surfaceChanged(SurfaceHolder h, int f, int w, int hgt) { player.setSurface(h.getSurface()); }
public void surfaceDestroyed(SurfaceHolder h) { player.setSurface(null); }

// WebServer.Callback（网络线程）
public void onControl(org.json.JSONObject msg) {
  String t = msg.optString("t");
  if ("video-config".equals(t)) {
    player.beginVideo(msg.optString("codec"), msg.optInt("width"), msg.optInt("height"),
        android.util.Base64.decode(msg.optString("desc"), android.util.Base64.DEFAULT));
  } else if ("audio-config".equals(t)) {
    player.beginAudio(msg.optInt("rate", 48000), msg.optInt("channels", 2));
  } else if ("stop".equals(t)) {
    player.stopAll();
  }
}
public void onVideoFrame(byte[] data, int off, int len, boolean keyframe, long ptsMs) {
  player.pushVideo(data, off, len, keyframe, ptsMs); // WebServer 已去掉 8 字节头，off=8
}
public void onAudioFrame(byte[] data, int off, int len) {
  player.pushAudio(data, off, len);                  // WebServer 已去掉 4 字节头，off=4
}
public void onSenderClosed(String reason) { player.stopAll(); }

// Service.onDestroy()
player.setSurface(null);
player.release();
```

注意：

1. **先 `setSurface` 还是先 `beginVideo` 都可以**。两种顺序都支持：
   - surface 先就绪 → `beginVideo` 立刻建解码器；
   - `beginVideo` 先到（surface 还没创建）→ 只保存配置，等 `setSurface(valid)` 再建。
2. 断流 3 s / 收到 `stop` / `onSenderClosed` 都要 `stopAll()`，再回待机页并清空 Surface。
3. Activity 退到后台（`onPause`）如果继续收流，务必 `setSurface(null)`，否则解码器可能向
   已销毁的 Surface 输出（见 §9.4）。
4. 同一个连接里再次收到 `video-config` 时直接 `beginVideo`，内部会**彻底 stop/release 旧
   codec** 再建新的，不需要先 `stopAll`。

---

## 4. 协议映射（PROTOCOL.md v1）

| 协议消息 | CastPlayer 调用 |
| --- | --- |
| `video-config`（`codec`,`width`,`height`,`desc` base64） | `beginVideo(codec, width, height, Base64.decode(desc))` |
| 二进制视频帧（8 字节头 + AVCC） | `pushVideo(data, off, len, flags&1, ptsMs)`；`off/len` 用 WebServer 回调已剥离头的值 |
| `audio-config`（`format:"s16le"`,`rate`:48000,`channels`:2） | `beginAudio(rate, channels)` |
| 二进制音频帧（4 字节头 + PCM） | `pushAudio(data, off, len)` |
| `stop` | `stopAll()` |
| 电视→电脑 `stats` | `Listener.onVideoStats` 每秒一次，直接转发 |
| 电视→电脑 `need-keyframe` | `Listener.onNeedKeyframe`（启动/溢出/卡顿时） |

`codec` 白名单：`avc1*` / `avc3*`（Protocol 约定 `avc1.42E028`、`avc1.4D4028`）。
尺寸校验：1..4096；协议层还应保证 ≤1920×1080。
`kbps` 为视频负载字节（不含 8 字节协议头）折算，`drops` 为本次 `beginVideo` 起累计值。

---

## 5. H264Util（纯 Java，可 JVM 单测）

### 5.1 `parseAvcC` → `SpsPps`（符合 docs/INTERFACES.md 契约）

- 输入可以是 **裸 AVCDecoderConfigurationRecord**（WebCodecs `decoderConfig.description`，
  首字节 `0x01`），也可以是带 4 字节 box 头的完整 `avcC` box（自动识别）。
- 校验 `configurationVersion == 1`、`numOfSequenceParameterSets > 0`、
  `numOfPictureParameterSets > 0`，所有 NAL 长度必须落在记录内；尾部扩展字节忽略。
- 支持多个 SPS/PPS：`csd0` = 所有 SPS 的 Annex-B 串接，`csd1` = 所有 PPS 的 Annex-B 串接。
- `lengthSize = (lengthSizeMinusOne & 3) + 1`，即 1..4（正常为 4）。
- 非法输入抛 `IllegalArgumentException`（message 可直接打日志）。

```java
H264Util.SpsPps cc = H264Util.parseAvcC(avccBytes); // 契约方法（docs/INTERFACES.md）
// cc.csd0 / cc.csd1 直接塞 MediaFormat "csd-0"/"csd-1"（Annex-B，含起始码）
// 额外字段：cc.lengthSize（通常 4）、spsCount/ppsCount/profileIdc/levelIdc，供日志与转换使用

byte[] annexB = H264Util.avccToAnnexB(data, offset, length); // 契约方法：按 4 字节长度前缀
// 解码器内部用带 lengthSize 的精确版本：
//   int need = H264Util.annexBLength(data, off, len, lengthSize);
//   int n = H264Util.convertAvccToAnnexB(data, off, len, lengthSize, dst, dstOff);
```

### 5.2 `convertAvccToAnnexB`

- AVCC：`u32 length + NAL` 重复；输出：`00 00 00 01 + NAL` 重复。
- `annexBLength(...)` 精确计算输出长度（同时做完整校验），解码器据此复用 scratch buffer，
  避免每帧分配。
- 长度为 0 的 NAL 会被安全跳过；长度越界/截断/`lengthSize ∉ 1..4`/目标 buffer 太小都抛
  `IllegalArgumentException`。
- `looksLikeAnnexB(...)` 辅助判断（排障用，正常协议流不走这里）。

单测覆盖：解析 normal/box/多 SPS-PPS/偏移/4 种 lengthSize/版本错误/无 SPS/无 PPS/截断/
空 NAL；转换 1~8 个 NAL/各种 lengthSize/空样本/零长 NAL/精确目标 buffer/越界/非法参数，
以及 docs/INTERFACES.md 的 3 参数 `avccToAnnexB` 契约重载。共 68 项断言。

---

## 6. VideoDecoder 实现细节

### 6.1 解码器选择

按顺序尝试，**每个候选都会 create + configure + start，失败自动下一个**：

1. `MediaCodec.createByCodecName("OMX.MS.AVC.Decoder")`（真机硬件解码）；
2. `MediaCodec.createDecoderByType("video/avc")`（系统默认）；
3. `MediaCodec.createByCodecName("OMX.google.h264.decoder")`（软解兜底）。

每次尝试失败都会打 `decoder attempt failed [name]: ...`；全部失败回调
`onVideoEnded("codec-init-failed")`。

MediaFormat：
`video/avc` + `KEY_WIDTH/KEY_HEIGHT` + `csd-0`(SPS Annex-B) + `csd-1`(PPS Annex-B)，
`configure(format, surface, null, 0)`，同步模式。仅使用 API18 存在的同步 API
（`getInputBuffers()`、`dequeueInputBuffer`、`dequeueOutputBuffer`、
`releaseOutputBuffer(index, boolean)`），没有异步回调、没有 `getInputBuffer(index)`、
没有 `CodecException`（那些都是 API21+）。

> **in-band SPS/PPS**：`INJECT_SPS_PPS_ON_IDR = true` 时，每个关键帧 access unit 前会再拼一份
> `csd-0`/`csd-1`（Annex-B）再送解码器。标准路径靠 csd 即可，但 MStar 这类 TV 解码器对
> 码流内 SPS/PPS 更宽容；若真机出现「每次关键帧绿屏/花屏」可把它改成 `false` 再试。

### 6.2 线程与队列

- **输入线程**：从有界队列取 Frame → `dequeueInputBuffer(10 ms)`（最多等 1000 ms，超时丢帧并
  请求 IDR）→ AVCC 转 Annex-B 写入 input buffer → `queueInputBuffer(index,0,n,ptsMs*1000,0)`。
- **输出线程**：`dequeueOutputBuffer(info, 10 ms)` → 正常帧 `releaseOutputBuffer(index, true)`
  （直接渲染到 Surface）→ 处理 `INFO_OUTPUT_FORMAT_CHANGED`（记录真实宽高）、
  `INFO_OUTPUT_BUFFERS_CHANGED`（同步模式无需处理）、EOS、异常。
- **队列上限 12 帧**（~400 ms）：溢出时清空积压 + 丢当前帧 + `onNeedKeyframe("queue-overflow")`；
  若溢出的同时到达的是关键帧，则清空积压后保留这个 IDR，立即恢复。
- `pushVideo` 里做一次字节拷贝（`new byte[len]`），保证网络层可以复用/丢弃原 buffer。
- `onNeedKeyframe` 500 ms 节流，避免网络风暴。

### 6.3 统计

- 输出线程每 ≥1000 ms 计算并回调一次：`fps` 本秒渲染帧数；`kbps` 本秒收到的视频负载
  （`pushVideo` 的 `len` 累加，含被丢弃帧）×8；`decodeMs` 按 pts 关联
  「queueInputBuffer → releaseOutputBuffer」的平均毫秒（无 B 帧假设，pts 顺序即显示顺序）；
  `drops` 累计丢帧（队列溢出、输入超时、坏帧、超大帧、surface 无效）。
- 解码器活着但无帧输出时也会每秒回调（fps=0），方便电视端发现断流。
- 无解器会话（surface 未就绪/已结束）时无 stats；CastService 自己可以按 3 s 无帧判超时。

### 6.4 Surface 生命周期

| 事件 | 行为 |
| --- | --- |
| `setSurface(null)` / `!Surface.isValid()` | 立即 stop + release codec，**保留** video-config；之后视频帧直接丢弃 |
| `setSurface(valid)`（同一 Surface 恢复） | 用保留的 config 重建 codec + `onNeedKeyframe("decoder-started")` |
| `setSurface(另一个 Surface)` | 同上，重建 |
| `beginVideo(...)` | 先彻底 stop/release 旧 codec（包括 join 线程），再按新 config 建 |

`stop`/`shutdown` 的 join 上限每线程 500 ms；正常情况几毫秒内退出。若打印
`decoder thread still alive after join` 说明厂商 codec 卡死（见 §9.13）。

### 6.5 异常处理

- `dequeue*`/`queueInputBuffer`/`releaseOutputBuffer` 抛 `RuntimeException`：记录
  `input-error`/`output-error`，终止会话并回调 `onVideoEnded`，不会把异常抛给网络线程。
- 输入 buffer 小于一个 access unit：打 `video frame too large for decoder input buffer: n > cap`，
  丢帧并请求 IDR（API18 无法把一个 access unit 拆到多个 input buffer）。
- 坏 AVCC 帧：`dropping malformed video frame: ...` + 请求 IDR。

---

## 7. AudioPlayer 实现细节

- `AudioTrack(STREAM_MUSIC, 48000, CHANNEL_OUT_STEREO, ENCODING_PCM_16BIT,
  bufferSize, MODE_STREAM)`，`bufferSize = max(getMinBufferSize, 100 ms)`；
  `play()` 后由一个独立 writer 线程把队列写进 AudioTrack。
- 软件队列上限 **40 ms**（48 kHz 立体声约 7680 B），`pushAudio` 只做 `System.arraycopy` 入队；
  队列满直接丢帧并节流打日志，**绝不阻塞网络/视频线程**。总缓冲约 100–140 ms。
- `beginAudio(rate, channels)`：仅支持 1/2 声道、8000–48000 Hz（其他回退 48000/2）；
  `AudioTrack` 初始化失败/`write` 返回错误时打 `WebCastMedia` 日志并停止音频（不影响视频）；
  再次收到 `audio-config` 会重新打开。
- `stopAll()` → `stop()`：置停止标志、清队列、jail writer（join 300 ms）、
  `pause → flush → stop → release`；`release()` 后再调用任何方法均为 no-op。
- **不做 A/V 时间戳同步**：音频按实时写入，视频按到达/解码即渲染。理论上音频会比视频晚约
  一个 AudioTrack 缓冲（100 ms 左右）。若真机唇音同步明显，优先调小
  `AudioPlayer.TRACK_BUFFER_MS`（>= `getMinBufferSize` 即可）或 `MAX_QUEUE_MS`，再考虑在
  `VideoDecoder` 输出线程按 pts 做延迟渲染（未实现，见 §9.8）。

---

## 8. API18 / 老设备约束（已遵守）

- 无 `java.nio.file`、`java.time`、Stream、lambda（全部匿名类/普通循环）。
- MediaCodec 只用同步 API；无 `getInputBuffer(int)`、`releaseOutputBuffer(int,long)`、
  `CodecException`、异步回调（API21+）。
- `AudioTrack` 用 API3 起的构造器；`AudioTrack.getMinBufferSize` API3。
- 所有 public 类/方法签名固定，`CastService` 不依赖内部类。
- 构建方式不变：`build.sh` 的 `find app/src -name '*.java'` 会自动带上本包，无需改构建脚本。

---

## 9. 真机风险清单（Lead 联调重点）

按出现概率排序。所有日志都在 Tag `WebCastMedia`，建议
`adb logcat -s WebCastMedia WebCastWeb WebCastMain`。

### 9.1 `OMX.MS.AVC.Decoder` 名称/能力不符
- 症状：日志 `decoder attempt failed [OMX.MS.AVC.Decoder]: ...`，随后 by-type 成功。
- 处理：能出画面就不用管（走的系统同款硬解）；如果三个都失败 →
  `onVideoEnded("codec-init-failed")`，用 `adb shell dumpsys media.player` 或
  `media_codecs.xml` 确认真实 OMX 名，改 `VideoDecoder.PREFERRED_CODEC` 常量即可。

### 9.2 csd-0/csd-1 兼容性
- 症状：configure 成功、有 `first video frame rendered` 日志，但电视黑屏/绿屏/花屏，
  或 logcat 出现 OMX `setParameter`/`configure` 错误。
- 已做的防御：csd 用 **Annex-B 起始码**；每个 IDR 前还会 in-band 注入 SPS/PPS。
- 若怀疑重复 SPS 导致每次 IDR 抖一下：把 `VideoDecoder.INJECT_SPS_PPS_ON_IDR` 改为 `false`。
- 个别 OMX 要求 `csd-0` 同时含 SPS+PPS：可在 `createAndStartCodec` 里把 `csd-0`
  设为 `concat(sps, pps)`、去掉 `csd-1` 试一次（当前实现是标准写法，先不动）。

### 9.3 AVCC vs Annex-B
- 协议发的是 AVCC（4 字节长度前缀）；解码器输入必须是 Annex-B（起始码）。转换在输入线程完成。
- 症状排查：`dropping malformed video frame: ...` 大量出现 → 发送端 NAL 封装/description
  有问题；`bad-video-config: ...` → avcC 解析失败（重点看日志里的 message）。
- 若发送端错误地直接发 Annex-B，本实现会按 AVCC 解析并报错丢帧（协议要求 AVCC），
  需要修发送端；`H264Util.looksLikeAnnexB` 可用于以后加兼容开关。

### 9.4 Surface 生命周期（黑屏/崩溃高发区）
- SurfaceView 的 `surfaceCreated/surfaceDestroyed` 必须分别调用
  `setSurface(holder.getSurface())` / `setSurface(null)`。
- 如果在 `surfaceDestroyed` 之后仍让 MediaCodec 持有旧 Surface 输出，MStar 上常见
  `native_window` 报错、`releaseOutputBuffer failed` 或进程崩溃；本实现收到 `setSurface(null)`
  会立刻停 codec，等新 surface 再重建（并请求 IDR）。
- Activity 切换（待机页 ↔ 播放页）时建议先 `setSurface(null)` 再销毁 View；不要在
  `surfaceDestroyed` 里同步做重活（内部会 join 解码线程，正常几 ms）。

### 9.5 硬解实例耗尽
- 症状：第二次 `beginVideo` 后 configure/start 失败、`codec-init-failed`；
  logcat 里 `media.codec` 报 `Failed to allocate component instance`。
- 本实现重配前会 stop+release 旧 codec；但若上一个 Activity 泄漏了另一个 MediaCodec
  （例如 Lead 自己的探针代码），仍会耗尽。确保全进程只有一个 CastPlayer。
- 仍然失败时：`adb shell kill <pid>` 重开 App 观察是否恢复，可确认是实例泄漏。

### 9.6 解码输入 buffer 太小
- 症状：`video frame too large for decoder input buffer: n > cap`（尤其 1080p 高码率 IDR）。
- 缓解：发送端把 1080p 码率从 8 Mbps 降到 6 Mbps，或切 720p（PROTOCOL 允许 3–4 Mbps）；
  也可尝试 `MediaFormat.setInteger("max-input-size", 2*1024*1024)` 看厂商是否认（默认没设）。
- 该情况只会丢帧+请求 IDR，不会崩。

### 9.7 首帧/启动时序
- 解码器启动后立即请求 IDR（`need-keyframe`, reason `decoder-started`），发送端应在下一帧
  补 IDR。若发送端不响应 need-keyframe，首帧要等它 2 s 周期关键帧才出来（黑屏 2 s）。
- `onVideoReady` 是首帧真正 `releaseOutputBuffer(true)` 之后才回调，CastService 可以据此
  切播放页；如果切页后 SurfaceView 才创建，会走 §6.4 的重建路径，属正常。

### 9.8 A/V 同步
- 当前不做时间戳同步：音频 ≈ 实时 + 100 ms AudioTrack 缓冲；视频 ≈ 到达即解码渲染。
  若真机听感「声音慢半拍」：
  1) 调小 `AudioPlayer.TRACK_BUFFER_MS`（100 → 用 `getMinBufferSize` 允许的最小值）；
  2) 或给视频渲染加固定延迟（在输出线程 release 前按 pts 相对首帧 sleep 到目标时刻）。
- 若「声音快」/断续：看 `audio queue full, dropping frames`，说明网络抖动或发送端音频
  发送过快；调大 `MAX_QUEUE_MS`（会增大延迟）。

### 9.9 AudioTrack 初始化/写失败
- 日志 `AudioTrack not initialized (...)`：检查电视音量/静音、是否被其他 App 占用
  STREAM_MUSIC；`beginAudio` 只在收到 `audio-config` 时调用，可让发送端重发。
- 日志 `AudioTrack.write failed/returned ...`：音频线程停止，视频继续；重连/重发
  `audio-config` 会重建。需要的话后续可加自动重开。
- 无声音但没错误日志：先确认发送端真的发 `audio-config`（WebServer 的 onControl），
  以及音频帧 `type=0x02`。

### 9.10 性能预算（1080p30）
- `decodeMs > 33` 或 `fps < 25` 持续几秒 → 硬解跟不上：优先 720p/30 或降码率；
  也看 `drops` 是否主要来自 `input-stall`（送给解码器的输入跟不上）还是 `output-error`。
- MStar 老 SoC 同时解 1080p30 + 渲染 + TLS 可能吃紧；`kbps` 正常但 fps 低说明瓶颈在解码/显示。
- `fps` 略高于 30 属于统计窗口抖动，不用处理。

### 9.11 线程卡死 / ANR
- `decoder thread still alive after join: webcast-vdec-in/out`：厂商 codec 卡在
  `dequeue*Buffer`。本实现 join 超时后仍会尝试 release（可能抛异常，已 catch）。
- `stopAll`/`setSurface(null)` 会 join 解码线程，极端情况可阻塞 ~1 s；建议 CastService 在
  网络线程或后台线程调用 `stopAll`，不要在 UI 线程做复杂清理。

### 9.12 断流判定
- 媒体层不判超时（协议要求断流 3 s 回待机页由 CastService 做）：推荐用
  `onVideoStats` 的 fps/kbps 或自己计时最后一条 `onVideoFrame`；超过 3 s 无帧 → `stopAll()`。

### 9.13 重复配置/重连
- 同一连接再次 `video-config`：`beginVideo` 会先彻底释放旧 codec。
- 发送端断开：`onSenderClosed` → `stopAll()`，回到待机页；下次连接重新 `video-config` 即可，
  不需要 `release()` CastPlayer（Service 生命周期内复用）。
- 只有 Service 销毁时 `release()`；release 之后对象不可再用。

---

## 10. 验证记录

在 `webcast/` 项目根目录：

```bash
# 1) API18 编译（JDK 25 JBR + android-18 bootclasspath）
javac -source 8 -target 8 -nowarn \
  -bootclasspath "$HOME/Library/Android/sdk/platforms/android-18/android.jar" \
  -d work/check-media $(find app/src/app/webcast/media -name '*.java')
# => exit 0（生成 AudioPlayer/VideoDecoder/CastPlayer/H264Util 及内部类 class）

# 2) H264Util 纯 JVM 单测
./tools/run-media-tests.sh
# => H264UtilTest: 68 passed, 0 failed / RESULT: PASS
```

说明：

- 额外做了**全量源码 javac 检查**（仅编译，不产 APK）：`app/src/**/*.java` +
  临时 `R.java` stub + `third_party/conscrypt/classes.jar`，exit 0 —— 即 CastService
  已按本 API 接线且类型检查通过。
- 本机没有独立 JDK，脚本自动选用 Android Studio 自带 JBR（`JAVA_HOME` 或 PATH 有可用
  JDK 时优先用它们）；`-source 8 -target 8` 均通过。
- 未运行 `./build.sh`（由 Lead 负责整体 APK）；未跑 adb/真机（由 Lead 集成验证）。
- 未修改 `build.sh`、`AndroidManifest.xml`、`res`、其他包/目录。

---

## 11. 待 Lead 集成/确认项

1. `docs/INTERFACES.md` 已由 Lead 补充；`CastPlayer` 公开 API 与之一致，
   `H264Util` 也按契约提供 `SpsPps parseAvcC(byte[])` 与
   `avccToAnnexB(byte[], int, int)`（内部另用带 `lengthSize` 的精确重载）。
   `CastService` 已接线，全量源码 javac 检查通过。
2. CastService 按 §3 接线：SurfaceHolder 三个回调、`video-config`/`audio-config`/`stop`、
   `onVideoFrame`/`onAudioFrame`、断流 3 s → `stopAll`。
3. `stats` 与 `need-keyframe` 的发送：注意回调线程（解码输出线程/网络线程），
   若 `WebServer.send` 非线程安全需自行 post/synchronize。
4. 真机跑起来后请把 `adb logcat -s WebCastMedia` 的相关段贴回来，特别是：
   - `MediaCodec started: ...` 用的是哪个 codec；
   - `decoder output format: WxH`、`first video frame rendered` 是否出现；
   - 每秒 stats 的 fps/kbps/decodeMs/drops；
   - 任何 `decoder attempt failed` / `video frame too large` / `input-error` / `output-error`。
5. 若出现黑屏、绿屏、花屏、唇音不同步、启动慢，按 §9 对应条目调参（大多是常量级改动）。
