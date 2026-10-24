package app.webcast.media;

import android.util.Log;
import android.view.Surface;

/**
 * Facade for the TV-side media pipeline.
 *
 * <p>Owns a {@link VideoDecoder} (MediaCodec H.264 -&gt; SurfaceView) and an
 * {@link AudioPlayer} (AudioTrack PCM). {@code CastService} talks to this class only.</p>
 *
 * <p><b>Threading contract</b></p>
 * <ul>
 *   <li>{@code pushVideo}/{@code pushAudio} are called from the WebSocket network thread and
 *       return quickly (bounded queues, no MediaCodec/AudioTrack blocking).</li>
 *   <li>{@code setSurface} is expected from the UI thread (SurfaceHolder callbacks).</li>
 *   <li>{@code beginVideo}/{@code beginAudio}/{@code stopAll}/{@code release} may be called
 *       from any thread.</li>
 *   <li>Listener callbacks run on decoder/audio/caller threads; <b>they must be fast and must
 *       not block</b>. Post to the UI thread if work is needed.</li>
 * </ul>
 *
 * <p><b>Listener semantics</b></p>
 * <ul>
 *   <li>{@code onVideoReady} once per started decoder session, on the first rendered frame.</li>
 *   <li>{@code onVideoStats} about once per second while a decoder session is alive.</li>
 *   <li>{@code onNeedKeyframe} for start, queue overflow, input stall and surface restart
 *       (throttled to one request per 500 ms).</li>
 *   <li>{@code onVideoEnded} once: EOS / fatal codec error / invalid config from the decoder,
 *       or after an explicit {@link #stopAll()} ("stopped"). Not called by {@link #release()}</li>
 * </ul>
 */
public final class CastPlayer {

  private static final String TAG = "WebCastMedia";
  private static final int MAX_DIMENSION = 4096;

  /** Implemented by CastService; see class docs for invocation threads and semantics. */
  public interface Listener {
    void onVideoReady(int width, int height);

    void onVideoStats(int fps, int kbps, int decodeMs, int drops);

    void onNeedKeyframe(String reason);

    void onVideoEnded(String reason);
  }

  private final Object stateLock = new Object();
  private final Listener listener;
  private final VideoDecoder video;
  private final AudioPlayer audio;

  private volatile boolean released;
  /** True between beginVideo() and the first ended/stop; guarded by stateLock. */
  private boolean videoActive;

  public CastPlayer(Listener listener) {
    this.listener = listener;
    this.video = new VideoDecoder(new VideoDecoder.Callback() {
      @Override public void onReady(int width, int height) {
        dispatchReady(width, height);
      }

      @Override public void onStats(int fps, int kbps, int decodeMs, int drops) {
        dispatchStats(fps, kbps, decodeMs, drops);
      }

      @Override public void onNeedKeyframe(String reason) {
        dispatchNeedKeyframe(reason);
      }

      @Override public void onEnded(String reason) {
        boolean fire;
        synchronized (stateLock) {
          fire = videoActive;
          videoActive = false;
        }
        if (fire) {
          notifyVideoEnded(reason);
        }
      }
    });
    this.audio = new AudioPlayer();
  }

  /** Binds the SurfaceView surface; null (surface destroyed) pauses video until it returns. */
  public void setSurface(Surface surface) {
    if (released) return;
    video.setSurface(surface);
  }

  /**
   * Prepares the H.264 decoder for a new stream. Replaces any previous decoder session.
   *
   * @param codec            "avc1.42E028" / "avc1.4D4028" (see PROTOCOL.md)
   * @param width            encoded width, 1..4096
   * @param height           encoded height, 1..4096
   * @param avccDescription  WebCodecs decoderConfig.description (avcC record / box)
   */
  public void beginVideo(String codec, int width, int height, byte[] avccDescription) {
    if (released) return;
    if (!isSupportedCodec(codec)) {
      failVideoConfig("unsupported-codec: " + codec);
      return;
    }
    if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
      failVideoConfig("bad-video-size: " + width + "x" + height);
      return;
    }
    if (avccDescription == null || avccDescription.length == 0) {
      failVideoConfig("missing-avcC-description");
      return;
    }
    synchronized (stateLock) {
      if (released) return;
      videoActive = true;
    }
    video.begin(codec, width, height, avccDescription);
  }

  /** Queues one AVCC access unit (protocol video payload, 8-byte header already stripped). */
  public void pushVideo(byte[] data, int off, int len, boolean keyframe, long ptsMs) {
    if (released) return;
    video.push(data, off, len, keyframe, ptsMs);
  }

  /** (Re)configures PCM playback; protocol default is 48000 Hz / 2 channels / s16le. */
  public void beginAudio(int rate, int channels) {
    if (released) return;
    audio.begin(rate, channels);
  }

  /** Queues one PCM chunk (protocol audio payload, 4-byte header already stripped). */
  public void pushAudio(byte[] data, int off, int len) {
    if (released) return;
    audio.push(data, off, len);
  }

  /** Stops video and audio. Fires onVideoEnded("stopped") if a video session was active. */
  public void stopAll() {
    boolean hadVideo;
    synchronized (stateLock) {
      if (released) return;
      hadVideo = videoActive;
      videoActive = false;
    }
    video.stop();
    audio.stop();
    if (hadVideo) {
      notifyVideoEnded("stopped");
    }
  }

  /** Terminal teardown; safe to call multiple times. Never invokes the listener. */
  public void release() {
    synchronized (stateLock) {
      if (released) return;
      released = true;
      videoActive = false;
    }
    video.release();
    audio.release();
    Log.i(TAG, "CastPlayer released");
  }

  // ------------------------------------------------------------------------------------
  // internals
  // ------------------------------------------------------------------------------------

  private static boolean isSupportedCodec(String codec) {
    if (codec == null) return false;
    return codec.startsWith("avc1") || codec.startsWith("avc3");
  }

  private void failVideoConfig(String reason) {
    Log.e(TAG, "video config rejected: " + reason);
    synchronized (stateLock) {
      videoActive = false;
    }
    video.stop();
    notifyVideoEnded(reason);
  }

  private void dispatchReady(int width, int height) {
    if (listener == null) return;
    try {
      listener.onVideoReady(width, height);
    } catch (Throwable t) {
      Log.e(TAG, "Listener.onVideoReady failed", t);
    }
  }

  private void dispatchStats(int fps, int kbps, int decodeMs, int drops) {
    if (listener == null) return;
    try {
      listener.onVideoStats(fps, kbps, decodeMs, drops);
    } catch (Throwable t) {
      Log.e(TAG, "Listener.onVideoStats failed", t);
    }
  }

  private void dispatchNeedKeyframe(String reason) {
    if (listener == null) return;
    try {
      listener.onNeedKeyframe(reason);
    } catch (Throwable t) {
      Log.e(TAG, "Listener.onNeedKeyframe failed", t);
    }
  }

  private void notifyVideoEnded(String reason) {
    if (listener == null) return;
    try {
      listener.onVideoEnded(reason);
    } catch (Throwable t) {
      Log.e(TAG, "Listener.onVideoEnded failed", t);
    }
  }
}
