package app.webcast;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.webcast.ui.IdleView;

/**
 * Full-screen TV UI: idle page with LAN URL + QR code, video view while casting.
 * All heavy lifting (HTTPS/WS + MediaCodec) lives in CastService.
 *
 * <p>Rendering uses a {@link TextureView}, not a SurfaceView: on this MStar TV the
 * SurfaceView/video-plane path decoded correctly but the panel stayed black, while the
 * TextureView path is GPU-composited inside the app window and verified visible.</p>
 */
public final class MainActivity extends Activity implements CastService.UiListener {
  private CastService service;
  private boolean bound;
  private boolean casting;
  private IdleView idleView;
  private TextureView videoView;
  private Surface videoSurface;
  private TextView overlay;
  private boolean overlayEnabled = true;

  private final ServiceConnection connection = new ServiceConnection() {
    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
      service = ((CastService.LocalBinder) binder).service();
      bound = true;
      service.setUiListener(MainActivity.this);
      // Only bind once the video layer is actually shown: a Surface owned by a hidden
      // TextureView can fail MediaCodec.configure on this TV (first-cast race).
      if (videoView.getVisibility() == View.VISIBLE && videoView.isAvailable()) {
        bindSurfaceTexture(videoView.getSurfaceTexture());
      }
    }

    @Override public void onServiceDisconnected(ComponentName name) {
      bound = false;
      service = null;
    }
  };

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        | WindowManager.LayoutParams.FLAG_FULLSCREEN);
    hideSystemUi();

    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);

    idleView = new IdleView(this);
    idleView.setVersion("1.1.0");
    idleView.setInfo(null, null, "Android TV");
    idleView.setStatus("正在启动投屏服务…", false);
    root.addView(idleView, new FrameLayout.LayoutParams(-1, -1));

    videoView = new TextureView(this);
    videoView.setBackgroundColor(Color.BLACK);
    videoView.setVisibility(View.GONE);
    FrameLayout.LayoutParams videoParams =
        new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
    root.addView(videoView, videoParams);
    videoView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
      @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        if (videoView.getVisibility() == View.VISIBLE) bindSurfaceTexture(texture);
      }

      @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
      }

      @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        releaseVideoSurface();
        return true;
      }

      @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {
      }
    });

    overlay = new TextView(this);
    overlay.setTextColor(Color.WHITE);
    overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    overlay.setBackgroundColor(0x77000000);
    overlay.setPadding(18, 10, 18, 10);
    overlay.setVisibility(View.GONE);
    FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    overlayParams.setMargins(40, 32, 0, 0);
    root.addView(overlay, overlayParams);

    setContentView(root);
    startService(new Intent(this, CastService.class));
  }

  @Override protected void onStart() {
    super.onStart();
    bindService(new Intent(this, CastService.class), connection, Context.BIND_AUTO_CREATE);
    hideSystemUi();
  }

  @Override protected void onResume() {
    super.onResume();
    hideSystemUi();
  }

  @Override protected void onStop() {
    if (bound) {
      CastService s = service;
      if (s != null) {
        if (s.isCasting()) s.stopCasting();
        s.setUiListener(null);
      }
      unbindService(connection);
      bound = false;
    }
    super.onStop();
  }

  @Override protected void onDestroy() {
    releaseVideoSurface();
    if (isFinishing()) stopService(new Intent(this, CastService.class));
    super.onDestroy();
  }

  @Override public void onBackPressed() {
    if (casting && service != null) {
      service.stopCasting();
      return;
    }
    super.onBackPressed();
  }

  private void hideSystemUi() {
    getWindow().getDecorView().setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LOW_PROFILE);
  }

  // ------------------------------------------------------------ surface plumbing

  private void bindSurfaceTexture(SurfaceTexture texture) {
    CastService s = service;
    if (texture == null || s == null) return;
    if (videoView.getVisibility() != View.VISIBLE) return;
    releaseVideoSurface();
    videoSurface = new Surface(texture);
    s.attachSurface(videoSurface);
  }

  private void releaseVideoSurface() {
    CastService s = service;
    if (s != null) s.detachSurface();
    if (videoSurface != null) {
      videoSurface.release();
      videoSurface = null;
    }
  }

  private void setVideoVisible(boolean visible) {
    videoView.setVisibility(visible ? View.VISIBLE : View.GONE);
    if (visible && videoSurface == null && videoView.isAvailable()) {
      bindSurfaceTexture(videoView.getSurfaceTexture());
    }
  }

  // ------------------------------------------------------------ CastService.UiListener

  @Override public void onServerState(boolean running, String url, String ip, String error) {
    if (idleView == null) return;
    idleView.setInfo(url, ip, "Android TV");
    if (running) {
      idleView.setStatus("已就绪 · 等待电脑连接", false);
    } else {
      idleView.setStatus(error == null ? "服务启动失败" : "服务启动失败：" + error, false);
    }
  }

  @Override public void onSenderConnected(String remote) {
    if (idleView == null) return;
    idleView.setStatus("电脑已连接，正在建立画面…", true);
    if (overlay != null) {
      overlay.setText("电脑已连接，等待画面…");
      overlay.setVisibility(overlayEnabled ? View.VISIBLE : View.GONE);
    }
  }

  @Override public void onCastingStarting(int width, int height) {
    // Switch to the video layer as soon as a stream is announced: MediaCodec needs a real
    // Surface before it can render the first frame.
    casting = true;
    layoutVideo(width, height);
    idleView.setVisibility(View.GONE);
    setVideoVisible(true);
    overlay.setVisibility(overlayEnabled ? View.VISIBLE : View.GONE);
    overlay.setText("正在建立画面…");
  }

  @Override public void onCastingStarted(int width, int height) {
    casting = true;
    layoutVideo(width, height);
    idleView.setVisibility(View.GONE);
    setVideoVisible(true);
    overlay.setVisibility(overlayEnabled ? View.VISIBLE : View.GONE);
    overlay.setText("正在投屏 " + width + "×" + height + " · 按返回键停止");
  }

  @Override public void onCastingStopped(String reason) {
    casting = false;
    idleView.setVisibility(View.VISIBLE);
    setVideoVisible(false);
    releaseVideoSurface();
    overlay.setVisibility(View.GONE);
    idleView.setStatus("投屏已结束 · 等待电脑连接", false);
  }

  @Override public void onOverlayVisibility(boolean show) {
    overlayEnabled = show;
    if (overlay != null) overlay.setVisibility(show && casting ? View.VISIBLE : View.GONE);
  }

  @Override public void onCastStats(int fps, int kbps, int decodeMs, int drops) {
    if (!casting || overlay == null || !overlayEnabled) return;
    String text = "正在投屏 · " + fps + " FPS · " + kbps + " kbps";
    if (decodeMs > 0) text += " · 解码 " + decodeMs + "ms";
    if (drops > 0) text += " · 丢帧 " + drops;
    overlay.setText(text);
  }

  private void layoutVideo(int width, int height) {
    if (width <= 0 || height <= 0) return;
    int screenWidth = getResources().getDisplayMetrics().widthPixels;
    int screenHeight = getResources().getDisplayMetrics().heightPixels;
    float scale = Math.min((float) screenWidth / width, (float) screenHeight / height);
    int viewWidth = Math.max(2, Math.round(width * scale));
    int viewHeight = Math.max(2, Math.round(height * scale));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(viewWidth, viewHeight, Gravity.CENTER);
    videoView.setLayoutParams(params);
  }
}
