package app.webcast.media;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.util.ArrayDeque;

/**
 * PCM (s16le) player for the TV side: {@link AudioTrack} in STREAM_MUSIC / MODE_STREAM.
 *
 * <p>Protocol default is 48000 Hz, 2 channels, 16 bit, ~20 ms frames (PROTOCOL.md audio
 * binary frames). Buffer budget (real device tuning, 2026-10):</p>
 * <ul>
 *   <li>AudioTrack ring buffer {@link #TRACK_BUFFER_MS} = 120 ms (device min is ~100 ms);</li>
 *   <li>software queue {@link #MAX_QUEUE_MS} = 240 ms = 6 x 7680 B frames (the sender's
 *       PCM chunk is 40 ms at 48 kHz stereo), absorbs network jitter / AudioFlinger
 *       bursts;</li>
 *   <li>steady-state queue occupancy is near zero, so expected A/V latency is dominated by
 *       the 120 ms track buffer; the 240 ms queue is a jitter reserve (worst case ~360 ms
 *       only while saturating).</li>
 * </ul>
 *
 * <p>{@link #push} is called from the WebSocket network thread and <b>never blocks</b>:
 * the PCM bytes are copied into the bounded queue; if the queue is full the <b>new</b> frame
 * is dropped (video timing is more important than an audio gap). A dedicated writer thread
 * is the only place that can block inside {@code AudioTrack.write}.</p>
 *
 * <p>Diagnostics: every 5 s the writer logs {@code audio diag: track/queued/written/drops/
 * underruns~}. API 18 has no {@code AudioTrack.getUnderrunCount()} (API 24+), so the
 * underrun figure is estimated from {@code getPlaybackHeadPosition()}: while the writer is
 * idle (queue empty) a playback head frozen for ~100 ms means the device ran dry. One count
 * per starvation episode.</p>
 */
public final class AudioPlayer {

  private static final String TAG = "WebCastMedia";

  private static final int DEFAULT_RATE = 48000;
  private static final int DEFAULT_CHANNELS = 2;

  /** AudioTrack ring buffer: ~120 ms at 48 kHz stereo (device min buffer was ~100 ms). */
  private static final int TRACK_BUFFER_MS = 120;
  /** Software queue: ~240 ms = 6 x 7680 B frames (40 ms each); absorbs jitter above the track. */
  private static final int MAX_QUEUE_MS = 240;

  /** Queue-full log every N dropped frames (plus the first drop) to avoid logcat spam. */
  private static final long DROP_LOG_EVERY_FRAMES = 200L;
  private static final long WRITER_JOIN_MS = 300L;
  private static final long DIAG_INTERVAL_MS = 5000L;

  /** Idle writer polls/queues every 50 ms, so 2 frozen samples ~= 100 ms of starvation. */
  private static final long IDLE_POLL_MS = 50L;
  private static final long HEAD_SAMPLE_MIN_MS = 40L;
  private static final long HEAD_STALL_SAMPLES = 2L;

  private final Object lock = new Object();
  private final ArrayDeque<byte[]> queue = new ArrayDeque<byte[]>(8);

  private AudioTrack track;
  private Thread writer;
  private int queuedBytes;
  private int maxQueueBytes;
  private int bytesPerSecond;
  private int trackBufferBytes;
  private volatile boolean running;
  private boolean released;

  private long droppedFrames;
  private long writtenBytes;
  private long writtenFrames;
  private long lastDiagAt;
  private long startedAtMs;

  // Underrun estimate: only touched by the writer thread while holding lock.
  private long lastHeadPosition;
  private long lastHeadSampleAt;
  private long stalledHeadSamples;
  private long underrunEpisodes;
  private boolean underrunEpisodeOpen;

  /** (Re)creates the AudioTrack. Safe to call at any time; invalid parameters fall back. */
  public void begin(int sampleRate, int channelCount) {
    int useRate = sampleRate >= 8000 && sampleRate <= 48000 ? sampleRate : DEFAULT_RATE;
    int useChannels = channelCount == 1 ? 1 : DEFAULT_CHANNELS;
    stop();
    synchronized (lock) {
      if (released) return;
    }

    int channelMask = useChannels == 1
        ? AudioFormat.CHANNEL_OUT_MONO
        : AudioFormat.CHANNEL_OUT_STEREO;
    int minBuffer = AudioTrack.getMinBufferSize(useRate, channelMask,
        AudioFormat.ENCODING_PCM_16BIT);
    if (minBuffer <= 0) {
      Log.e(TAG, "AudioTrack.getMinBufferSize failed: " + minBuffer);
      return;
    }
    int useBytesPerSecond = useRate * useChannels * 2;
    int targetBuffer = useBytesPerSecond * TRACK_BUFFER_MS / 1000;
    int bufferSize = Math.max(minBuffer, targetBuffer);
    AudioTrack newTrack;
    try {
      newTrack = new AudioTrack(AudioManager.STREAM_MUSIC, useRate, channelMask,
          AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
    } catch (Throwable t) {
      Log.e(TAG, "AudioTrack creation failed: " + t);
      return;
    }
    if (newTrack.getState() != AudioTrack.STATE_INITIALIZED) {
      Log.e(TAG, "AudioTrack not initialized (rate=" + useRate + " ch=" + useChannels
          + " buffer=" + bufferSize + ")");
      releaseTrack(newTrack);
      return;
    }
    try {
      newTrack.play();
    } catch (RuntimeException e) {
      Log.e(TAG, "AudioTrack.play failed", e);
      releaseTrack(newTrack);
      return;
    }

    synchronized (lock) {
      if (released) {
        releaseTrack(newTrack);
        return;
      }
      track = newTrack;
      bytesPerSecond = useBytesPerSecond;
      trackBufferBytes = bufferSize;
      maxQueueBytes = bytesPerSecond * MAX_QUEUE_MS / 1000;
      queue.clear();
      queuedBytes = 0;
      droppedFrames = 0;
      writtenBytes = 0;
      writtenFrames = 0;
      lastDiagAt = System.currentTimeMillis();
      startedAtMs = lastDiagAt;
      lastHeadPosition = 0L;
      lastHeadSampleAt = 0L;
      stalledHeadSamples = 0L;
      underrunEpisodes = 0L;
      underrunEpisodeOpen = false;
      running = true;
      writer = new Thread(new Runnable() {
        @Override public void run() {
          writeLoop();
        }
      }, "webcast-audio-out");
      writer.start();
    }
    int frameBytes = Math.max(1, useBytesPerSecond / 25); // 40 ms at the negotiated rate
    Log.i(TAG, "audio started: " + useRate + " Hz, " + useChannels + " ch, trackBuffer="
        + bufferSize + " bytes (~" + (bufferSize * 1000L / useBytesPerSecond) + " ms), queueMax="
        + (maxQueueBytes * 1000L / useBytesPerSecond) + " ms (" + (maxQueueBytes / frameBytes)
        + " x " + (frameBytes * 1000L / useBytesPerSecond) + "ms frames)");
  }

  /**
   * Queues one PCM frame. Returns false when the frame was dropped (player stopped or
   * queue full); it never blocks on the audio device. Queue-full keeps the already-buffered
   * frames and drops this new one.
   */
  public boolean push(byte[] data, int off, int len) {
    if (data == null || len <= 0 || off < 0 || off > data.length - len) {
      return false;
    }
    synchronized (lock) {
      if (!running || track == null || released) {
        return false;
      }
      if (queuedBytes + len > maxQueueBytes) {
        droppedFrames++;
        if (droppedFrames == 1L || droppedFrames % DROP_LOG_EVERY_FRAMES == 0L) {
          int queuedMs = bytesPerSecond > 0 ? (int) (queuedBytes * 1000L / bytesPerSecond) : 0;
          Log.w(TAG, "audio queue full, dropping new frame (dropped=" + droppedFrames
              + " frames, queued=" + queuedMs + "/" + MAX_QUEUE_MS + " ms)");
        }
        return false;
      }
      byte[] copy = new byte[len];
      System.arraycopy(data, off, copy, 0, len);
      queue.addLast(copy);
      queuedBytes += len;
      lock.notifyAll();
      return true;
    }
  }

  /** Stops playback and releases the AudioTrack. Idempotent. */
  public void stop() {
    AudioTrack oldTrack;
    Thread oldWriter;
    long dropped;
    long written;
    long underruns;
    synchronized (lock) {
      if (!running && track == null) return;
      running = false;
      oldTrack = track;
      track = null;
      oldWriter = writer;
      writer = null;
      dropped = droppedFrames;
      written = writtenBytes;
      underruns = underrunEpisodes;
      queue.clear();
      queuedBytes = 0;
      lock.notifyAll();
    }
    if (oldWriter != null) {
      oldWriter.interrupt();
      if (oldWriter != Thread.currentThread()) {
        try {
          oldWriter.join(WRITER_JOIN_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    releaseTrack(oldTrack);
    Log.i(TAG, "audio stopped: written=" + (written / 1024L) + "KB, drops=" + dropped
        + " frames, underruns~=" + underruns);
  }

  /** Terminal: stops playback and ignores all future work. Idempotent. */
  public void release() {
    synchronized (lock) {
      if (released) return;
      released = true;
    }
    stop();
  }

  // ------------------------------------------------------------------------------------
  // writer thread
  // ------------------------------------------------------------------------------------

  private void writeLoop() {
    try {
      while (true) {
        byte[] chunk = null;
        AudioTrack target;
        synchronized (lock) {
          if (running && queue.isEmpty()) {
            try {
              lock.wait(IDLE_POLL_MS);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
            // Idle because everything we have has been handed to AudioFlinger: this is the
            // only place where the device can run dry, so sample the playback head here.
            sampleIdleUnderrunLocked(System.currentTimeMillis());
          }
          if (!running) return;
          if (!queue.isEmpty()) {
            chunk = queue.pollFirst();
            queuedBytes -= chunk.length;
          }
          target = track;
        }
        maybeLogDiagnostics();
        if (chunk == null) {
          continue; // idle poll again
        }
        if (target == null) return;
        int offset = 0;
        int size = chunk.length;
        while (offset < size) {
          if (!running) return;
          int written;
          try {
            written = target.write(chunk, offset, size - offset);
          } catch (RuntimeException e) {
            Log.e(TAG, "AudioTrack.write failed, stopping audio", e);
            markDeviceDead();
            return;
          }
          if (written <= 0) {
            Log.e(TAG, "AudioTrack.write returned " + written + ", stopping audio");
            markDeviceDead();
            return;
          }
          offset += written;
          synchronized (lock) {
            writtenBytes += written;
          }
        }
        synchronized (lock) {
          writtenFrames++;
        }
      }
    } catch (Throwable t) {
      Log.e(TAG, "audio writer thread failed", t);
      markDeviceDead();
    }
  }

  /**
   * Estimates hardware underruns (API 18 has no AudioTrack.getUnderrunCount()): while the
   * writer is idle, a playback-head position that does not advance for ~100 ms means the
   * AudioFlinger buffer ran dry. Counted once per starvation episode.
   */
  private void sampleIdleUnderrunLocked(long now) {
    AudioTrack t = track;
    if (t == null || !running) return;
    // AudioFlinger needs a moment to start consuming the first frames; do not report a
    // startup "underrun" during that window.
    if (now - startedAtMs < 500L) return;
    long head;
    try {
      head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
    } catch (Throwable e) {
      return; // released / invalid state
    }
    if (lastHeadSampleAt == 0L) {
      lastHeadSampleAt = now;
      lastHeadPosition = head;
      return;
    }
    if (now - lastHeadSampleAt < HEAD_SAMPLE_MIN_MS) return;
    lastHeadSampleAt = now;
    boolean advanced = head != lastHeadPosition;
    lastHeadPosition = head;
    if (advanced) {
      stalledHeadSamples = 0L;
      underrunEpisodeOpen = false;
      return;
    }
    if (writtenBytes <= 0L) return; // nothing played yet (startup), not an underrun
    stalledHeadSamples++;
    if (stalledHeadSamples >= HEAD_STALL_SAMPLES && !underrunEpisodeOpen) {
      underrunEpisodeOpen = true;
      underrunEpisodes++;
      int queuedMs = bytesPerSecond > 0 ? (int) (queuedBytes * 1000L / bytesPerSecond) : 0;
      Log.w(TAG, "audio underrun suspected: playback head frozen ~"
          + (stalledHeadSamples * IDLE_POLL_MS) + " ms (count=" + underrunEpisodes
          + ", queued=" + queuedMs + " ms)");
    }
  }

  /** Periodic audio health line (every ~5 s) so real-device acceptance has hard numbers. */
  private void maybeLogDiagnostics() {
    long now = System.currentTimeMillis();
    long dropped;
    long written;
    long frames;
    long underruns;
    long head;
    int queued;
    int bps;
    int buffer;
    boolean active;
    synchronized (lock) {
      if (now - lastDiagAt < DIAG_INTERVAL_MS) return;
      lastDiagAt = now;
      dropped = droppedFrames;
      written = writtenBytes;
      frames = writtenFrames;
      underruns = underrunEpisodes;
      head = lastHeadPosition;
      queued = queuedBytes;
      bps = bytesPerSecond;
      buffer = trackBufferBytes;
      active = running && track != null;
    }
    if (bps <= 0) return;
    Log.i(TAG, "audio diag" + (active ? "" : " (inactive)")
        + ": track=" + (buffer * 1000L / bps) + "ms, queued=" + (queued * 1000L / bps)
        + "ms, written=" + (written / 1024L) + "KB, frames=" + frames + ", drops=" + dropped
        + " frames, underruns~=" + underruns + ", headFrames=" + head);
  }

  private void markDeviceDead() {
    synchronized (lock) {
      running = false;
      queue.clear();
      queuedBytes = 0;
      lock.notifyAll();
    }
  }

  private static void releaseTrack(AudioTrack track) {
    if (track == null) return;
    try {
      track.pause();
    } catch (Throwable ignored) {
      // already stopped / invalid state
    }
    try {
      track.flush();
    } catch (Throwable ignored) {
      // streaming buffer already gone
    }
    try {
      track.stop();
    } catch (Throwable ignored) {
      // already stopped
    }
    try {
      track.release();
    } catch (Throwable ignored) {
      // already released
    }
  }
}
