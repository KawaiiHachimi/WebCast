# 内部接口契约（Lead 集成层 ↔ 队友模块）

修改本文件描述的签名必须同步改集成代码。所有回调在后台线程触发，MainActivity/CastService 负责切主线程。

## 1. 媒体管线（android-media 负责，包 `app.webcast.media`）

```java
public final class CastPlayer {
  public interface Listener {
    void onVideoReady(int width, int height);       // 首帧已渲染
    void onVideoStats(int fps, int kbps, int decodeMs, int drops); // 约每秒一次
    void onNeedKeyframe(String reason);             // 需要发送端补 IDR
    void onVideoEnded(String reason);               // 解码停止/失败
  }
  public CastPlayer(Listener listener);
  public void setSurface(android.view.Surface surface); // 允许 null（surface 销毁）
  public void beginVideo(String codec, int width, int height, byte[] avccDescription);
  public void pushVideo(byte[] data, int offset, int length, boolean keyframe, long ptsMs);
  public void beginAudio(int sampleRate, int channels);
  public void pushAudio(byte[] data, int offset, int length);
  public void stopAll();   // 停止本次投屏，可再次 beginVideo
  public void release();   // 最终释放，之后不可用
}

public final class H264Util {
  public static final class SpsPps { public final byte[] csd0; public final byte[] csd1; } // Annex-B，含起始码
  public static SpsPps parseAvcC(byte[] avcc);
  public static byte[] avccToAnnexB(byte[] data, int offset, int length);
}
```

- `avccDescription` 是 WebCodecs `decoderConfig.description`（avcC box）原始字节；CastPlayer 内部调 H264Util。
- `pushVideo` 的 `data` 是 AVCC 负载（不含 8 字节协议头），即 WebServer 的 `onVideoFrame(data, 8, len-8, ...)`。
- `onVideoStats` 的 `kbps` 按收到的视频字节数计算；`drops` 累计。

## 2. Web 服务器（Lead 负责，包 `app.webcast.net`）

`WebServer.Callback` 由 CastService 实现：

```java
void onSenderConnected();
void onSenderClosed(String reason);
void onControl(JSONObject msg);                    // 文本控制消息（hello/video-config/audio-config/stop）
void onVideoFrame(byte[] data, int off, int len, boolean keyframe, long ptsMs); // len 不含协议头
void onAudioFrame(byte[] data, int off, int len);
```

`WebServer` 公开方法（CastService 用）：

```java
void start() throws Exception;        // 阻塞创建 TLS + bind，然后异步 accept
void stop();
boolean isRunning();
int getPort();
String getIp();
String url();                        // https://ip:port/
boolean isSenderOnline();
void sendText(String json);
void sendNeedKeyframe();
void refreshAddress();               // 网络变化后刷新 ip
String infoJsonString();             // /api/info 同款 JSON，供 hello-ack 复用
```

## 3. UI（android-ui 负责，包 `app.webcast.ui`）

```java
public final class IdleView extends android.view.View {
  public IdleView(android.content.Context context);
  public void setInfo(String url, String ip, String model);
  public void setStatus(String status, boolean connected);
  public void setVersion(String version);
}
```

## 4. CastService ↔ MainActivity（Lead 负责）

```java
public final class CastService extends Service {
  public final class LocalBinder extends Binder { public CastService service(); }

  public interface UiListener {
    void onServerState(boolean running, String url, String ip, String error);
    void onSenderConnected(String remote);   // 浏览器已连上（还未出画面）
    void onCastingStarting(int width, int height); // 收到 video-config，先显示 Surface 等首帧
    void onCastingStarted(int width, int height);
    void onCastingStopped(String reason);
    void onCastStats(int fps, int kbps, int decodeMs, int drops);
    void onOverlayVisibility(boolean show); // 电脑页面「投屏状态条」开关
  }

  public void setUiListener(UiListener listener);  // 传 null 注销
  public void attachSurface(android.view.Surface surface);
  public void detachSurface();
  public void stopCasting();                       // 用户按返回键/离开 App
  public String currentUrl();
  public boolean isCasting();
}
```
