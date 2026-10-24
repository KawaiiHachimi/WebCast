package app.webcast.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H.264 hardware decoder for the TV side, driven by the synchronous MediaCodec API
 * (the asynchronous callback API is API 21+, the target device is API 18).
 *
 * <p>Threading model (one instance per casting session):</p>
 * <ul>
 *   <li><b>caller/network threads</b> only call {@link #push} which appends a copy of the
 *       AVCC access unit to a bounded queue (12 frames) and returns immediately;</li>
 *   <li><b>input thread</b> converts AVCC -&gt; Annex-B and calls
 *       {@code dequeueInputBuffer}/{@code queueInputBuffer} in a loop;</li>
 *   <li><b>output thread</b> calls {@code dequeueOutputBuffer} and
 *       {@code releaseOutputBuffer(index, true)} so the frame is rendered directly to the
 *       SurfaceView. It also owns the 1 Hz stats tick.</li>
 * </ul>
 *
 * <p>Lifecycle: {@link #begin} installs a csd/config and (re)starts a codec if a valid
 * surface is available. {@link #setSurface} pauses the codec when the SurfaceView surface
 * goes away and restarts it when it returns (asking the sender for a fresh IDR).
 * {@link #stop} drops the config; {@link #release} is terminal.</p>
 *
 * <p>Error semantics: codec failures are reported through
 * {@link Callback#onEnded(String)} (the session is torn down). Nothing is thrown to the
 * caller beyond {@link IllegalArgumentException} for programmer errors.</p>
 */
public final class VideoDecoder {

  private static final String TAG = "WebCastMedia";

  private static final String MIME_VIDEO_AVC = "video/avc";
  /** The Android 4.3 电视 (MStar) hardware decoder. */
  private static final String PREFERRED_CODEC = "OMX.MS.AVC.Decoder";
  /** Software fallback bundled with Android. */
  private static final String SOFTWARE_CODEC = "OMX.google.h264.decoder";

  /** Bounded queue: ~400 ms at 30 fps. Overflow flushes the backlog and requests an IDR. */
  private static final int MAX_QUEUE_FRAMES = 12;
  private static final long DEQUEUE_TIMEOUT_US = 10000L;
  private static final long INPUT_STALL_MS = 1000L;
  private static final long STATS_INTERVAL_MS = 1000L;
  private static final long NEED_KEYFRAME_THROTTLE_MS = 500L;
  private static final long THREAD_JOIN_MS = 500L;

  /**
   * Prepend SPS/PPS in-band before every IDR access unit. Harmless duplication when csd-0 /
   * csd-1 are set, but it keeps picky vendor decoders (MStar) happy if they ignore csd.
   * Set to false for debugging if a device double-configures on in-band SPS.
   */
  static final boolean INJECT_SPS_PPS_ON_IDR = true;

  /** Callbacks are invoked on the input/output/network thread; keep them fast and non-blocking. */
  public interface Callback {
    /** First frame was rendered. */
    void onReady(int width, int height);
    /** Emitted once per second while a decoder session is alive. */
    void onStats(int fps, int kbps, int decodeMs, int drops);
    /** The stream cannot continue without a new IDR (start, queue overflow, surface restart). */
    void onNeedKeyframe(String reason);
    /** Decoder session ended: EOS, fatal codec error or invalid configuration. */
    void onEnded(String reason);
  }

  private static final class Frame {
    final byte[] data; // AVCC access unit (owned by us)
    final boolean keyframe;
    final long ptsMs;

    Frame(byte[] data, boolean keyframe, long ptsMs) {
      this.data = data;
      this.keyframe = keyframe;
      this.ptsMs = ptsMs;
    }
  }

  private final Callback callback;
  private final Object lock = new Object();

  // --- configuration (guarded by lock) -------------------------------------------------
  private boolean released;
  private boolean haveConfig;
  private int configGeneration;
  private int cfgWidth;
  private int cfgHeight;
  private byte[] cfgSps;
  private byte[] cfgPps;
  private int cfgLengthSize = 4;
  private Surface surface;
  private Session session;
  private long lastNeedKeyframeAt;

  public VideoDecoder(Callback callback) {
    this.callback = callback;
  }

  // ------------------------------------------------------------------------------------
  // public API (called from CastPlayer / network threads)
  // ------------------------------------------------------------------------------------

  /** Installs (or replaces) the decoder configuration and starts a codec when possible. */
  public void begin(String codecName, int width, int height, byte[] avccDescription) {
    H264Util.SpsPps config;
    try {
      config = H264Util.parseAvcC(avccDescription);
    } catch (IllegalArgumentException e) {
      Log.e(TAG, "begin: invalid avcC description: " + e.getMessage());
      stop();
      notifyEnded("bad-video-config: " + e.getMessage());
      return;
    }
    if (width <= 0 || height <= 0) {
      Log.e(TAG, "begin: invalid video size " + width + "x" + height);
      stop();
      notifyEnded("bad-video-config: size " + width + "x" + height);
      return;
    }

    Session old;
    synchronized (lock) {
      if (released) return;
      old = session;
      session = null;
      if (old != null) old.running = false;
      cfgWidth = width;
      cfgHeight = height;
      cfgSps = config.csd0;
      cfgPps = config.csd1;
      cfgLengthSize = config.lengthSize;
      haveConfig = true;
      configGeneration++;
      lastNeedKeyframeAt = 0L;
    }
    if (old != null) {
      old.shutdown(true);
    }
    Log.i(TAG, "begin video: codec=" + codecName + " " + width + "x" + height + " " + config);
    startIfReady();
  }

  /** Binds or unbinds the rendering surface; null/invalid pauses the codec (no data loss). */
  public void setSurface(Surface newSurface) {
    Session old = null;
    boolean needStart = false;
    synchronized (lock) {
      if (released) return;
      Surface previous = surface;
      boolean newValid = newSurface != null && newSurface.isValid();
      surface = newSurface;
      if (!newValid) {
        if (session != null) {
          old = session;
          session = null;
          old.running = false;
        }
      } else if (session != null && previous != newSurface) {
        old = session;
        session = null;
        old.running = false;
      }
      needStart = newValid && session == null && haveConfig;
    }
    if (old != null) {
      Log.i(TAG, "video surface lost/changed: stopping decoder");
      old.shutdown(true);
    }
    if (needStart) {
      if (old != null) Log.i(TAG, "video surface ready: restarting decoder");
      startIfReady();
    }
  }

  /**
   * Queues one AVCC access unit (8-byte protocol header already stripped by the network
   * layer). Never blocks on MediaCodec; the data is copied so the caller can reuse its buffer.
   */
  public void push(byte[] data, int off, int len, boolean keyframe, long ptsMs) {
    if (data == null || len <= 0 || off < 0 || off > data.length - len) {
      return;
    }
    boolean askKeyframe = false;
    boolean overflow = false;
    synchronized (lock) {
      if (released) return;
      Session s = session;
      if (s == null || !s.running) {
        // No codec yet (surface not ready / decoder failed): drop. The keyframe request
        // happens when the decoder starts.
        return;
      }
      s.bytesSince += len;
      if (s.queue.size() >= MAX_QUEUE_FRAMES) {
        int queued = s.queue.size();
        s.queue.clear();
        s.drops += queued;
        overflow = true;
        if (keyframe) {
          s.queue.addLast(new Frame(copyOf(data, off, len), true, ptsMs));
          lock.notifyAll();
        } else {
          s.drops++;
          askKeyframe = true;
        }
      } else {
        s.queue.addLast(new Frame(copyOf(data, off, len), keyframe, ptsMs));
        lock.notifyAll();
      }
    }
    if (overflow) {
      Log.w(TAG, "video queue overflow (>" + MAX_QUEUE_FRAMES + " frames): flushed backlog"
          + (askKeyframe ? ", requesting IDR" : ", keeping incoming IDR"));
    }
    if (askKeyframe) {
      requestKeyframe("queue-overflow");
    }
  }

  /** Stops the decoder and clears the configuration. Idempotent. */
  public void stop() {
    Session old;
    synchronized (lock) {
      if (released) return;
      old = detachLocked();
    }
    if (old != null) old.shutdown(true);
  }

  /** Terminal: stops the decoder and refuses any further work. Idempotent. */
  public void release() {
    Session old;
    synchronized (lock) {
      if (released) return;
      released = true;
      old = detachLocked();
    }
    if (old != null) old.shutdown(true);
  }

  // ------------------------------------------------------------------------------------
  // session startup
  // ------------------------------------------------------------------------------------

  private Session detachLocked() {
    Session old = session;
    session = null;
    if (old != null) old.running = false;
    haveConfig = false;
    cfgSps = null;
    cfgPps = null;
    configGeneration++;
    return old;
  }

  private void startIfReady() {
    final int generation;
    final int width;
    final int height;
    final int lengthSize;
    final byte[] sps;
    final byte[] pps;
    final Surface target;
    synchronized (lock) {
      if (released || !haveConfig || session != null) return;
      if (surface == null || !surface.isValid()) return;
      generation = configGeneration;
      width = cfgWidth;
      height = cfgHeight;
      lengthSize = cfgLengthSize;
      sps = cfgSps;
      pps = cfgPps;
      target = surface;
    }
    if (sps == null || pps == null) return;

    MediaCodec codec = createAndStartCodec(width, height, sps, pps, target);
    if (codec == null) {
      boolean relevant;
      synchronized (lock) {
        relevant = !released && haveConfig && configGeneration == generation && session == null;
      }
      if (relevant) {
        notifyEnded("codec-init-failed");
      }
      return;
    }

    Session fresh = new Session(codec, target, width, height, lengthSize, sps, pps);
    boolean installed = false;
    synchronized (lock) {
      if (!released && session == null && haveConfig && configGeneration == generation
          && surface == target) {
        session = fresh;
        installed = true;
      }
    }
    if (!installed) {
      Log.w(TAG, "decoder session became stale while starting; releasing");
      fresh.releaseCodecOnce();
      return;
    }
    try {
      fresh.startThreads();
    } catch (RuntimeException e) {
      Log.e(TAG, "cannot start decoder worker threads", e);
      synchronized (lock) {
        if (session == fresh) session = null;
      }
      fresh.running = false;
      fresh.releaseCodecOnce();
      notifyEnded("codec-start-failed: " + e);
      return;
    }
    Log.i(TAG, "decoder running: " + width + "x" + height + " lengthSize=" + lengthSize);
    requestKeyframe("decoder-started");
  }

  /**
   * Creates + configures + starts a decoder. Tries the MStar hardware decoder first, then
   * {@code createDecoderByType("video/avc")}, then the bundled software decoder.
   *
   * @return a started MediaCodec, or null if every candidate failed
   */
  private static MediaCodec createAndStartCodec(int width, int height, byte[] sps, byte[] pps,
      Surface surface) {
    MediaFormat format = MediaFormat.createVideoFormat(MIME_VIDEO_AVC, width, height);
    format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
    format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
    String[] names = new String[] {PREFERRED_CODEC, null, SOFTWARE_CODEC};
    Throwable last = null;
    for (int i = 0; i < names.length; i++) {
      MediaCodec codec = null;
      String label = names[i] != null ? names[i] : "createDecoderByType";
      try {
        codec = names[i] != null
            ? MediaCodec.createByCodecName(names[i])
            : MediaCodec.createDecoderByType(MIME_VIDEO_AVC);
        codec.configure(format, surface, null, 0);
        codec.start();
        Log.i(TAG, "MediaCodec started: " + codec.getName() + " (" + label + ")");
        return codec;
      } catch (Throwable t) {
        last = t;
        Log.w(TAG, "decoder attempt failed [" + label + "]: " + t);
        if (codec != null) {
          try {
            codec.stop();
          } catch (Throwable ignored) {
            // configure may have failed before start(); stop() is not valid then
          }
          try {
            codec.release();
          } catch (Throwable ignored) {
            // best effort
          }
        }
      }
    }
    Log.e(TAG, "no usable H.264 decoder on this device", last);
    return null;
  }

  // ------------------------------------------------------------------------------------
  // worker threads
  // ------------------------------------------------------------------------------------

  private void inputLoop(Session s) {
    try {
      while (s.running) {
        Frame frame;
        synchronized (lock) {
          frame = s.queue.pollFirst();
          if (frame == null) {
            try {
              lock.wait(20L);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
            continue;
          }
        }
        if (!s.running) break;
        feed(s, frame);
      }
    } catch (RuntimeException e) {
      if (s.running) {
        Log.e(TAG, "video input loop failed", e);
        terminate(s, "input-error: " + e);
      }
    } finally {
      s.threadExited();
    }
  }

  private void feed(Session s, Frame frame) {
    int index = -1;
    final long deadline = System.currentTimeMillis() + INPUT_STALL_MS;
    while (s.running && index < 0) {
      try {
        index = s.codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
      } catch (RuntimeException e) {
        if (s.running) {
          Log.e(TAG, "dequeueInputBuffer failed", e);
          terminate(s, "input-error: " + e);
        }
        return;
      }
      if (index < 0 && System.currentTimeMillis() > deadline) {
        synchronized (lock) {
          s.drops++;
        }
        Log.w(TAG, "no input buffer for " + INPUT_STALL_MS + " ms, dropping frame");
        requestKeyframe("input-stall");
        return;
      }
    }
    if (!s.running || index < 0) return;

    int written = 0;
    byte[] scratch = s.convertBuf;
    try {
      boolean inject = INJECT_SPS_PPS_ON_IDR && frame.keyframe && s.sps != null && s.pps != null;
      int prefix = inject ? s.sps.length + s.pps.length : 0;
      int needed = prefix
          + H264Util.annexBLength(frame.data, 0, frame.data.length, s.lengthSize);
      if (scratch.length < needed) {
        scratch = new byte[needed + 1024];
        s.convertBuf = scratch;
      }
      if (inject) {
        System.arraycopy(s.sps, 0, scratch, 0, s.sps.length);
        System.arraycopy(s.pps, 0, scratch, s.sps.length, s.pps.length);
        written = prefix;
      }
      written += H264Util.convertAvccToAnnexB(frame.data, 0, frame.data.length, s.lengthSize,
          scratch, written);
    } catch (IllegalArgumentException e) {
      Log.w(TAG, "dropping malformed video frame: " + e.getMessage());
      synchronized (lock) {
        s.drops++;
      }
      requestKeyframe("bad-frame");
      return;
    }

    try {
      ByteBuffer input = s.inputBuffers[index];
      int capacity = input.capacity();
      if (written > capacity) {
        // The codec's input buffer is smaller than this access unit. There is no way to
        // split an H.264 access unit across buffers on API18, so hand the buffer back
        // empty and ask the sender for a (hopefully smaller) IDR.
        Log.w(TAG, "video frame too large for decoder input buffer: " + written + " > "
            + capacity);
        s.codec.queueInputBuffer(index, 0, 0, frame.ptsMs * 1000L, 0);
        synchronized (lock) {
          s.drops++;
        }
        requestKeyframe("frame-too-large");
        return;
      }
      input.clear();
      input.put(scratch, 0, written);
      s.codec.queueInputBuffer(index, 0, written, frame.ptsMs * 1000L, 0);
      if (s.inputTimes.size() > 120) {
        s.inputTimes.clear(); // never observed on output (should not happen); bound memory
      }
      s.inputTimes.put(Long.valueOf(frame.ptsMs), Long.valueOf(System.nanoTime()));
    } catch (java.nio.BufferOverflowException e) {
      // Defensive: capacity changed under us; drop instead of killing the decoder.
      Log.w(TAG, "decoder input buffer overflow, dropping frame", e);
      synchronized (lock) {
        s.drops++;
      }
      requestKeyframe("frame-too-large");
    } catch (RuntimeException e) {
      if (s.running) {
        Log.e(TAG, "queueInputBuffer failed", e);
        terminate(s, "input-error: " + e);
      }
    }
  }

  private void outputLoop(Session s) {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    try {
      while (s.running) {
        int index;
        try {
          index = s.codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
        } catch (RuntimeException e) {
          if (s.running) {
            Log.e(TAG, "dequeueOutputBuffer failed", e);
            terminate(s, "output-error: " + e);
          }
          return;
        }
        if (!s.running) break;
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          readOutputSize(s);
        } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
          // Legacy notification; sync mode addresses output buffers by index only.
        } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
          // No frame ready yet.
        } else if (index >= 0) {
          if (handleOutputBuffer(s, index, info)) {
            terminate(s, "eos");
            return;
          }
        }
        maybeEmitStats(s);
      }
    } catch (RuntimeException e) {
      if (s.running) {
        Log.e(TAG, "video output loop failed", e);
        terminate(s, "output-error: " + e);
      }
    } finally {
      s.threadExited();
    }
  }

  /** @return true if the decoder signalled end of stream */
  private boolean handleOutputBuffer(Session s, int index, MediaCodec.BufferInfo info) {
    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
    boolean render = info.size > 0 && s.running && s.surface.isValid();
    try {
      s.codec.releaseOutputBuffer(index, render);
    } catch (RuntimeException e) {
      if (s.running) {
        Log.e(TAG, "releaseOutputBuffer failed", e);
        terminate(s, "output-error: " + e);
      }
      return false;
    }
    if (render) {
      s.framesSince++;
      Long queuedAt = s.inputTimes.remove(Long.valueOf(info.presentationTimeUs / 1000L));
      if (queuedAt != null) {
        long decodeMs = (System.nanoTime() - queuedAt.longValue()) / 1000000L;
        if (decodeMs >= 0 && decodeMs < 5000) {
          s.decodeMsSince += decodeMs;
          s.decodeSamplesSince++;
        }
      }
      if (!s.readyNotified) {
        s.readyNotified = true;
        int width = s.outWidth > 0 ? s.outWidth : s.width;
        int height = s.outHeight > 0 ? s.outHeight : s.height;
        Log.i(TAG, "first video frame rendered: " + width + "x" + height);
        notifyReady(width, height);
      }
    } else if (info.size > 0) {
      synchronized (lock) {
        s.drops++;
      }
    }
    return eos;
  }

  private void readOutputSize(Session s) {
    try {
      MediaFormat format = s.codec.getOutputFormat();
      int width = format.getInteger(MediaFormat.KEY_WIDTH);
      int height = format.getInteger(MediaFormat.KEY_HEIGHT);
      if (width > 0 && height > 0) {
        s.outWidth = width;
        s.outHeight = height;
        Log.i(TAG, "decoder output format: " + width + "x" + height);
      }
    } catch (RuntimeException e) {
      Log.w(TAG, "cannot read decoder output format: " + e);
    }
  }

  private void maybeEmitStats(Session s) {
    long now = System.currentTimeMillis();
    long elapsed = now - s.lastStatsAt;
    if (elapsed < STATS_INTERVAL_MS) return;
    s.lastStatsAt = now;

    long frames = s.framesSince;
    long bytes;
    long drops;
    synchronized (lock) {
      bytes = s.bytesSince;
      s.bytesSince = 0L;
      drops = s.drops;
    }
    long decodeSamples = s.decodeSamplesSince;
    long decodeMs = decodeSamples > 0 ? s.decodeMsSince / decodeSamples : 0L;
    s.framesSince = 0L;
    s.decodeMsSince = 0L;
    s.decodeSamplesSince = 0L;

    int fps = (int) (frames * 1000L / Math.max(1L, elapsed));
    int kbps = (int) (bytes * 8L / Math.max(1L, elapsed));
    int avgDecodeMs = (int) decodeMs;
    int dropCount = drops > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) drops;
    notifyStats(fps, kbps, avgDecodeMs, dropCount);
  }

  private void terminate(Session s, String reason) {
    boolean current;
    synchronized (lock) {
      current = session == s;
      if (current) session = null;
      s.running = false;
    }
    s.interruptThreads();
    if (current) {
      Log.w(TAG, "video decoder ended: " + reason);
      notifyEnded(reason);
    }
  }

  private void requestKeyframe(String reason) {
    synchronized (lock) {
      if (released) return;
      long now = System.currentTimeMillis();
      if (now - lastNeedKeyframeAt < NEED_KEYFRAME_THROTTLE_MS) return;
      lastNeedKeyframeAt = now;
    }
    Log.w(TAG, "need keyframe: " + reason);
    if (callback != null) {
      try {
        callback.onNeedKeyframe(reason);
      } catch (Throwable t) {
        Log.e(TAG, "onNeedKeyframe listener failed", t);
      }
    }
  }

  // ------------------------------------------------------------------------------------
  // callbacks (never invoked while holding lock)
  // ------------------------------------------------------------------------------------

  private void notifyReady(int width, int height) {
    if (callback == null) return;
    try {
      callback.onReady(width, height);
    } catch (Throwable t) {
      Log.e(TAG, "onVideoReady listener failed", t);
    }
  }

  private void notifyStats(int fps, int kbps, int decodeMs, int drops) {
    if (callback == null) return;
    try {
      callback.onStats(fps, kbps, decodeMs, drops);
    } catch (Throwable t) {
      Log.e(TAG, "onVideoStats listener failed", t);
    }
  }

  private void notifyEnded(String reason) {
    if (callback == null) return;
    try {
      callback.onEnded(reason);
    } catch (Throwable t) {
      Log.e(TAG, "onVideoEnded listener failed", t);
    }
  }

  private static byte[] copyOf(byte[] data, int off, int len) {
    byte[] copy = new byte[len];
    System.arraycopy(data, off, copy, 0, len);
    return copy;
  }

  // ------------------------------------------------------------------------------------
  // one decoder session (codec + worker threads + per-session counters)
  // ------------------------------------------------------------------------------------

  private final class Session {
    final MediaCodec codec;
    final Surface surface;
    final int width;
    final int height;
    final int lengthSize;
    final byte[] sps;
    final byte[] pps;

    final ArrayDeque<Frame> queue = new ArrayDeque<Frame>(MAX_QUEUE_FRAMES + 2);
    final ConcurrentHashMap<Long, Long> inputTimes = new ConcurrentHashMap<Long, Long>();

    volatile boolean running = true;
    volatile int outWidth;
    volatile int outHeight;

    Thread inputThread;
    Thread outputThread;
    ByteBuffer[] inputBuffers;
    int inputCapacity;
    byte[] convertBuf = new byte[0];

    // Guarded by the enclosing decoder lock.
    long bytesSince;
    long drops;

    // Output-thread-only counters/state.
    long lastStatsAt = System.currentTimeMillis();
    long framesSince;
    long decodeMsSince;
    long decodeSamplesSince;
    boolean readyNotified;

    // Thread exit bookkeeping (guarded by exitLock; release-once semantics).
    private final Object exitLock = new Object();
    private int aliveThreads = 2;
    private boolean codecReleased;

    Session(MediaCodec codec, Surface surface, int width, int height, int lengthSize,
        byte[] sps, byte[] pps) {
      this.codec = codec;
      this.surface = surface;
      this.width = width;
      this.height = height;
      this.lengthSize = lengthSize;
      this.sps = sps;
      this.pps = pps;
    }

    void startThreads() {
      inputBuffers = codec.getInputBuffers();
      inputCapacity = Integer.MAX_VALUE;
      for (int i = 0; i < inputBuffers.length; i++) {
        if (inputBuffers[i] != null) {
          inputCapacity = Math.min(inputCapacity, inputBuffers[i].capacity());
        }
      }
      if (inputCapacity == Integer.MAX_VALUE) inputCapacity = 0;
      inputThread = new Thread(new Runnable() {
        @Override public void run() {
          inputLoop(Session.this);
        }
      }, "webcast-vdec-in");
      outputThread = new Thread(new Runnable() {
        @Override public void run() {
          outputLoop(Session.this);
        }
      }, "webcast-vdec-out");
      inputThread.start();
      outputThread.start();
    }

    void interruptThreads() {
      Thread in = inputThread;
      Thread out = outputThread;
      if (in != null) in.interrupt();
      if (out != null) out.interrupt();
    }

    void shutdown(boolean wait) {
      running = false;
      interruptThreads();
      if (!wait) return;
      joinQuietly(inputThread);
      joinQuietly(outputThread);
      releaseCodecOnce();
    }

    void threadExited() {
      boolean last;
      synchronized (exitLock) {
        aliveThreads--;
        last = aliveThreads <= 0;
      }
      if (last) {
        releaseCodecOnce();
      }
    }

    void releaseCodecOnce() {
      synchronized (exitLock) {
        if (codecReleased) return;
        codecReleased = true;
      }
      try {
        codec.stop();
      } catch (Throwable t) {
        Log.w(TAG, "MediaCodec.stop failed: " + t);
      }
      try {
        codec.release();
      } catch (Throwable t) {
        Log.w(TAG, "MediaCodec.release failed: " + t);
      }
    }
  }

  private static void joinQuietly(Thread thread) {
    // A listener callback may synchronously call back into stop()/release() on the same
    // worker thread; joining ourselves would deadlock.
    if (thread == null || thread == Thread.currentThread()) return;
    try {
      thread.join(THREAD_JOIN_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (thread.isAlive()) {
      Log.w(TAG, "decoder thread still alive after join: " + thread.getName());
    }
  }
}
