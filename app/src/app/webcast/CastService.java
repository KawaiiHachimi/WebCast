package app.webcast;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import org.json.JSONObject;

import app.webcast.media.CastPlayer;
import app.webcast.net.WebServer;

/**
 * Owns the LAN server and the decoder. Lives independent of the Activity so the HTTP/WS service
 * survives short pauses (e.g. the TV showing a system dialog) without dropping the session.
 */
public final class CastService extends Service implements WebServer.Callback {
  private static final String TAG = "WebCastService";
  private static final int NOTIFICATION_ID = 1001;

  public interface UiListener {
    void onServerState(boolean running, String url, String ip, String error);
    void onSenderConnected(String remote);
    void onCastingStarting(int width, int height);
    void onCastingStarted(int width, int height);
    void onCastingStopped(String reason);
    void onCastStats(int fps, int kbps, int decodeMs, int drops);
    void onOverlayVisibility(boolean show);
  }

  public final class LocalBinder extends Binder {
    public CastService service() { return CastService.this; }
  }

  private final IBinder binder = new LocalBinder();
  private final Handler ui = new Handler(Looper.getMainLooper());
  private WebServer server;
  private CastPlayer player;
  private volatile UiListener listener;
  private volatile Surface surface;
  private volatile boolean casting;
  private volatile boolean starting;
  private volatile int videoWidth;
  private volatile int videoHeight;
  private volatile String serverError;
  private volatile boolean shuttingDown;
  private volatile boolean overlayVisible = true;

  @Override public void onCreate() {
    super.onCreate();
    player = new CastPlayer(new CastPlayer.Listener() {
      @Override public void onVideoReady(final int width, final int height) {
        Log.i(TAG, "first frame " + width + "x" + height);
        starting = false;
        casting = true;
        showCastingNotification();
        ui.post(new Runnable() {
          @Override public void run() {
            UiListener l = listener;
            if (l != null) l.onCastingStarted(width, height);
          }
        });
      }

      @Override public void onVideoStats(final int fps, final int kbps, final int decodeMs, final int drops) {
        if (server != null && server.isSenderOnline()) {
          try {
            JSONObject stats = new JSONObject();
            stats.put("t", "stats");
            stats.put("fps", fps);
            stats.put("kbps", kbps);
            stats.put("decodeMs", decodeMs);
            stats.put("drops", drops);
            server.sendText(stats.toString());
          } catch (Exception ignored) {}
        }
        ui.post(new Runnable() {
          @Override public void run() {
            UiListener l = listener;
            if (l != null) l.onCastStats(fps, kbps, decodeMs, drops);
          }
        });
      }

      @Override public void onNeedKeyframe(String reason) {
        Log.i(TAG, "keyframe requested: " + reason);
        if (server != null) server.sendNeedKeyframe();
      }

      @Override public void onVideoEnded(String reason) {
        Log.i(TAG, "video ended: " + reason);
        stopSession("video ended: " + reason, false);
      }
    });
    startServer();
    ui.postDelayed(refreshTick, 5000);
  }

  private final Runnable refreshTick = new Runnable() {
    @Override public void run() {
      if (shuttingDown) return;
      try {
        if (server != null && server.isRunning()) {
          String before = server.url();
          server.refreshAddress();
          String after = server.url();
          if (before == null || !before.equals(after)) notifyServerState();
        } else if (server == null) {
          startServer();
        }
      } catch (Throwable ignored) {
      }
      ui.postDelayed(this, 5000);
    }
  };

  private void startServer() {
    if (server != null) return;
    Thread t = new Thread(new Runnable() {
      @Override public void run() {
        WebServer created = new WebServer(CastService.this, CastService.this);
        try {
          created.start();
          server = created;
          serverError = null;
          Log.i(TAG, "server ready at " + created.url());
        } catch (final Exception e) {
          serverError = String.valueOf(e);
          Log.e(TAG, "server start failed", e);
        }
        notifyServerState();
      }
    }, "webcast-server-start");
    t.start();
  }

  private void notifyServerState() {
    final WebServer s = server;
    ui.post(new Runnable() {
      @Override public void run() {
        UiListener l = listener;
        if (l == null) return;
        l.onServerState(s != null && s.isRunning(), s == null ? null : s.url(),
            s == null ? null : s.getIp(), serverError);
      }
    });
  }

  public void setUiListener(UiListener value) {
    listener = value;
    if (value != null) syncUi();
  }

  /** Replays current state to a freshly bound Activity. */
  public void syncUi() {
    final WebServer s = server;
    final UiListener l = listener;
    if (l == null) return;
    l.onServerState(s != null && s.isRunning(), s == null ? null : s.url(),
        s == null ? null : s.getIp(), serverError);
    l.onOverlayVisibility(overlayVisible);
    if (casting) {
      l.onCastingStarted(videoWidth, videoHeight);
    } else if (starting) {
      l.onCastingStarting(videoWidth, videoHeight);
    }
  }

  public String currentUrl() {
    WebServer s = server;
    return s == null ? null : s.url();
  }

  public boolean isCasting() { return casting || starting; }

  public void attachSurface(Surface value) {
    surface = value;
    if (player != null) player.setSurface(value);
  }

  public void detachSurface() {
    surface = null;
    if (player != null) player.setSurface(null);
  }

  /** Called by the UI (back key / leaving the app): stop both the decoder and the PC sender. */
  public void stopCasting() {
    WebServer s = server;
    if (s != null && s.isSenderOnline()) s.sendText("{\"t\":\"stop\"}");
    stopSession("stopped by TV", false);
  }

  private void stopSession(String reason, boolean notifySender) {
    boolean wasCasting = casting || starting;
    casting = false;
    starting = false;
    try {
      if (player != null) player.stopAll();
    } catch (Throwable t) {
      Log.w(TAG, "player stop failed", t);
    }
    WebServer s = server;
    if (notifySender && s != null && s.isSenderOnline()) s.sendText("{\"t\":\"stop\"}");
    stopForeground(true);
    if (wasCasting) {
      Log.i(TAG, "session stopped: " + reason);
      ui.post(new Runnable() {
        @Override public void run() {
          UiListener l = listener;
          if (l != null) l.onCastingStopped("disconnected");
        }
      });
    }
  }

  private void showCastingNotification() {
    try {
      PendingIntent intent = PendingIntent.getActivity(this, 0,
          new Intent(this, MainActivity.class), 0);
      Notification notification = new Notification.Builder(this)
          .setSmallIcon(R.drawable.app_icon)
          .setContentTitle("正在投屏")
          .setContentText("电脑屏幕正在这台电视上播放")
          .setContentIntent(intent)
          .setOngoing(true)
          .getNotification();
      startForeground(NOTIFICATION_ID, notification);
    } catch (Throwable t) {
      Log.w(TAG, "foreground notification failed", t);
    }
  }

  // ------------------------------------------------------------ WebServer.Callback

  @Override public void onSenderConnected() {
    casting = false;
    ui.post(new Runnable() {
      @Override public void run() {
        UiListener l = listener;
        if (l != null) l.onSenderConnected("");
      }
    });
  }

  @Override public void onSenderClosed(final String reason) {
    stopSession("sender closed: " + reason, false);
  }

  @Override public void onControl(JSONObject msg) {
    String type = msg.optString("t");
    if ("hello".equals(type)) {
      Log.i(TAG, "hello from sender: " + msg.optString("ua", "?"));
      WebServer s = server;
      if (s == null) return;
      try {
        JSONObject ack = new JSONObject(s.infoJsonString());
        ack.put("t", "hello-ack");
        ack.put("v", 1);
        s.sendText(ack.toString());
      } catch (Exception ignored) {}
    } else if ("video-config".equals(type)) {
      handleVideoConfig(msg);
    } else if ("audio-config".equals(type)) {
      int rate = msg.optInt("rate", 48000);
      int channels = msg.optInt("channels", 2);
      if (player != null) player.beginAudio(rate, channels);
    } else if ("overlay".equals(type)) {
      final boolean show = msg.optBoolean("show", true);
      overlayVisible = show;
      ui.post(new Runnable() {
        @Override public void run() {
          UiListener l = listener;
          if (l != null) l.onOverlayVisibility(show);
        }
      });
    } else if ("stop".equals(type)) {
      stopSession("sender requested stop", false);
    }
  }

  private void handleVideoConfig(JSONObject msg) {
    String codec = msg.optString("codec", "");
    int width = msg.optInt("width", 0);
    int height = msg.optInt("height", 0);
    String desc = msg.optString("desc", "");
    if (width <= 0 || height <= 0 || width > 1920 || height > 1080) {
      Log.w(TAG, "reject video config " + width + "x" + height);
      if (server != null) server.sendText("{\"t\":\"error\",\"message\":\"unsupported video size\"}");
      return;
    }
    byte[] avcc;
    try {
      avcc = desc.length() == 0 ? new byte[0] : Base64.decode(desc, Base64.DEFAULT);
    } catch (Exception e) {
      Log.w(TAG, "bad avcC description", e);
      return;
    }
    videoWidth = width;
    videoHeight = height;
    Log.i(TAG, "video config " + codec + " " + width + "x" + height + " desc=" + avcc.length + "B");
    if (player != null) player.beginVideo(codec, width, height, avcc);
    starting = true;
    ui.post(new Runnable() {
      @Override public void run() {
        UiListener l = listener;
        if (l != null) l.onCastingStarting(videoWidth, videoHeight);
      }
    });
  }

  @Override public void onVideoFrame(byte[] data, int off, int len, boolean keyframe, long ptsMs) {
    if (player != null) player.pushVideo(data, off, len, keyframe, ptsMs);
  }

  @Override public void onAudioFrame(byte[] data, int off, int len) {
    if (player != null) player.pushAudio(data, off, len);
  }

  // ------------------------------------------------------------ lifecycle

  @Override public IBinder onBind(Intent intent) { return binder; }

  @Override public void onDestroy() {
    shuttingDown = true;
    ui.removeCallbacksAndMessages(null);
    if (player != null) {
      try { player.release(); } catch (Throwable ignored) {}
      player = null;
    }
    if (server != null) {
      server.stop();
      server = null;
    }
    super.onDestroy();
  }
}
