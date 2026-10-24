package app.webcast.media;

import java.io.ByteArrayOutputStream;

/**
 * H.264 bitstream helpers for the WebCast AVCC -> Annex-B pipeline.
 *
 * <p>Pure Java on purpose: no android.* import, so it can be exercised by a plain
 * JVM unit test (tests/media/H264UtilTest.java, tools/run-media-tests.sh).</p>
 *
 * <p>Two jobs:</p>
 * <ol>
 *   <li>{@link #parseAvcC(byte[])} parses the WebCodecs {@code decoderConfig.description}
 *       (an AVCDecoderConfigurationRecord, optionally wrapped in an {@code avcC} box)
 *       and produces Annex-B {@code csd-0} (SPS) / {@code csd-1} (PPS) buffers.</li>
 *   <li>{@link #convertAvccToAnnexB(byte[], int, int, int, byte[], int)} converts one
 *       AVCC access unit ({@code u32 length + NAL} repeated) into an Annex-B access unit
 *       ({@code 00 00 00 01 + NAL} repeated).</li>
 * </ol>
 *
 * <p>All errors are reported as {@link IllegalArgumentException} with a human readable
 * message; the decoder turns those into "drop frame / request keyframe" behaviour instead
 * of crashing.</p>
 */
public final class H264Util {

  /** Annex-B start code used by {@code csd-0}/{@code csd-1} and converted samples. */
  public static final int START_CODE_LEN = 4;

  private static final int MIN_LENGTH_SIZE = 1;
  private static final int MAX_LENGTH_SIZE = 4;

  private H264Util() {
    // static helpers only
  }

  /**
   * Parsed AVCDecoderConfigurationRecord, ready to feed MediaCodec.
   *
   * <p>Matches the contract in docs/INTERFACES.md ({@code csd0}/{@code csd1} are Annex-B with
   * start codes); the extra fields are diagnostics used by the decoder and tests.</p>
   */
  public static final class SpsPps {
    /** SPS NAL units in Annex-B form (start code included; several SPS are concatenated). */
    public final byte[] csd0;
    /** PPS NAL units in Annex-B form (start code included; several PPS are concatenated). */
    public final byte[] csd1;
    /** Number of bytes of the length prefix used by AVCC samples (1..4, normally 4). */
    public final int lengthSize;
    /** Number of SPS found in the record. */
    public final int spsCount;
    /** Number of PPS found in the record. */
    public final int ppsCount;
    /** SPS profile_idc (e.g. 0x42 = Baseline). */
    public final int profileIdc;
    /** SPS level_idc (e.g. 0x28 = level 4.0). */
    public final int levelIdc;
    /** SPS constraint flags byte, kept for logging. */
    public final int constraints;

    SpsPps(byte[] csd0, byte[] csd1, int lengthSize, int spsCount, int ppsCount,
        int profileIdc, int levelIdc, int constraints) {
      this.csd0 = csd0;
      this.csd1 = csd1;
      this.lengthSize = lengthSize;
      this.spsCount = spsCount;
      this.ppsCount = ppsCount;
      this.profileIdc = profileIdc;
      this.levelIdc = levelIdc;
      this.constraints = constraints;
    }

    @Override public String toString() {
      return "SpsPps{sps=" + spsCount + ",pps=" + ppsCount + ",lengthSize=" + lengthSize
          + ",profile=0x" + Integer.toHexString(profileIdc)
          + ",level=0x" + Integer.toHexString(levelIdc) + "}";
    }
  }

  /**
   * Parses an AVCDecoderConfigurationRecord (raw WebCodecs description, or the payload of
   * a complete {@code avcC} box).
   *
   * @throws IllegalArgumentException if the buffer is null, truncated or structurally invalid
   */
  public static SpsPps parseAvcC(byte[] avcC) {
    if (avcC == null) {
      throw new IllegalArgumentException("avcC is null");
    }
    return parseAvcC(avcC, 0, avcC.length);
  }

  /**
   * Parses an AVCDecoderConfigurationRecord from {@code avcC[off .. off+len)}.
   *
   * @throws IllegalArgumentException if the slice is invalid or truncated
   */
  public static SpsPps parseAvcC(byte[] avcC, int off, int len) {
    checkSlice(avcC, off, len, "avcC");

    int start = off;
    int end = off + len;

    // Some producers hand us the full "avcC" box instead of just the record. Detect the
    // box header and unwrap it. (The raw record starts with version == 1, so the first
    // four bytes would be 0x01xxxxxx and can never be mistaken for a sane box size.)
    if (len >= 8 && avcC[off + 4] == 'a' && avcC[off + 5] == 'v'
        && avcC[off + 6] == 'c' && avcC[off + 7] == 'C') {
      int boxSize = readLength(avcC, off, 4);
      if (boxSize == 0) {
        boxSize = len;
      }
      if (boxSize >= 8 && boxSize <= len) {
        start = off + 8;
        end = off + boxSize;
      }
    }

    if (end - start < 6) {
      throw new IllegalArgumentException("avcC too short: " + (end - start) + " bytes");
    }

    int version = avcC[start] & 0xff;
    if (version != 1) {
      throw new IllegalArgumentException("unsupported avcC version " + version);
    }
    int profile = avcC[start + 1] & 0xff;
    int constraints = avcC[start + 2] & 0xff;
    int level = avcC[start + 3] & 0xff;
    int lengthSize = (avcC[start + 4] & 0x03) + 1; // 2-bit lengthSizeMinusOne, always 1..4
    int spsCount = avcC[start + 5] & 0x1f;
    if (spsCount == 0) {
      throw new IllegalArgumentException("avcC contains no SPS");
    }

    ByteArrayOutputStream spsOut = new ByteArrayOutputStream(64);
    int p = start + 6;
    for (int i = 0; i < spsCount; i++) {
      if (end - p < 2) {
        throw new IllegalArgumentException("avcC truncated before SPS #" + i + " length");
      }
      int nalLen = readU16(avcC, p);
      p += 2;
      if (nalLen <= 0) {
        throw new IllegalArgumentException("avcC SPS #" + i + " is empty");
      }
      if (nalLen > end - p) {
        throw new IllegalArgumentException("avcC SPS #" + i + " length " + nalLen
            + " exceeds remaining " + (end - p));
      }
      writeStartCode(spsOut);
      spsOut.write(avcC, p, nalLen);
      p += nalLen;
    }

    if (p >= end) {
      throw new IllegalArgumentException("avcC missing PPS count");
    }
    int ppsCount = avcC[p] & 0xff;
    p++;
    if (ppsCount == 0) {
      throw new IllegalArgumentException("avcC contains no PPS");
    }

    ByteArrayOutputStream ppsOut = new ByteArrayOutputStream(32);
    for (int i = 0; i < ppsCount; i++) {
      if (end - p < 2) {
        throw new IllegalArgumentException("avcC truncated before PPS #" + i + " length");
      }
      int nalLen = readU16(avcC, p);
      p += 2;
      if (nalLen <= 0) {
        throw new IllegalArgumentException("avcC PPS #" + i + " is empty");
      }
      if (nalLen > end - p) {
        throw new IllegalArgumentException("avcC PPS #" + i + " length " + nalLen
            + " exceeds remaining " + (end - p));
      }
      writeStartCode(ppsOut);
      ppsOut.write(avcC, p, nalLen);
      p += nalLen;
    }
    // Trailing bytes (e.g. profile extensions) are intentionally ignored: decoders do not
    // need them and some encoders pad the record.

    return new SpsPps(spsOut.toByteArray(), ppsOut.toByteArray(), lengthSize,
        spsCount, ppsCount, profile, level, constraints);
  }

  /**
   * Computes the exact Annex-B size of the AVCC access unit in
   * {@code src[off .. off+len)}, validating every length prefix on the way.
   *
   * @throws IllegalArgumentException on malformed input
   */
  public static int annexBLength(byte[] src, int off, int len, int lengthSize) {
    checkSlice(src, off, len, "AVCC sample");
    checkLengthSize(lengthSize);
    int p = off;
    int end = off + len;
    int total = 0;
    while (p < end) {
      if (end - p < lengthSize) {
        throw new IllegalArgumentException("truncated NAL length at byte " + p);
      }
      int nalLen = readLength(src, p, lengthSize);
      p += lengthSize;
      if (nalLen == 0) {
        continue; // empty NAL units carry no payload; skip them
      }
      if (nalLen > end - p) {
        throw new IllegalArgumentException("NAL length " + nalLen + " exceeds remaining "
            + (end - p));
      }
      total += START_CODE_LEN + nalLen;
      p += nalLen;
    }
    return total;
  }

  /**
   * Converts AVCC ({@code u32/u24/u16/u8 length + NAL}) to Annex-B ({@code 00 00 00 01 + NAL})
   * writing into {@code dst}.
   *
   * @return number of bytes written (always {@code annexBLength(src, off, len, lengthSize)})
   * @throws IllegalArgumentException on malformed input or if {@code dst} is too small
   */
  public static int convertAvccToAnnexB(byte[] src, int off, int len, int lengthSize,
      byte[] dst, int dstOff) {
    checkSlice(src, off, len, "AVCC sample");
    checkLengthSize(lengthSize);
    if (dst == null) {
      throw new IllegalArgumentException("dst is null");
    }
    if (dstOff < 0 || dstOff > dst.length) {
      throw new IllegalArgumentException("bad dstOff " + dstOff + " for dst length " + dst.length);
    }

    int p = off;
    int end = off + len;
    int out = dstOff;
    while (p < end) {
      if (end - p < lengthSize) {
        throw new IllegalArgumentException("truncated NAL length at byte " + p);
      }
      int nalLen = readLength(src, p, lengthSize);
      p += lengthSize;
      if (nalLen == 0) {
        continue; // skip empty NAL units
      }
      if (nalLen > end - p) {
        throw new IllegalArgumentException("NAL length " + nalLen + " exceeds remaining "
            + (end - p));
      }
      if (out + START_CODE_LEN + nalLen > dst.length) {
        throw new IllegalArgumentException("dst too small: need " + (out + START_CODE_LEN + nalLen)
            + " bytes, have " + dst.length);
      }
      dst[out] = 0;
      dst[out + 1] = 0;
      dst[out + 2] = 0;
      dst[out + 3] = 1;
      out += START_CODE_LEN;
      System.arraycopy(src, p, dst, out, nalLen);
      out += nalLen;
      p += nalLen;
    }
    return out - dstOff;
  }

  /**
   * Contract convenience variant (docs/INTERFACES.md): 4-byte AVCC length prefixes.
   * Allocates a correctly sized Annex-B array.
   */
  public static byte[] avccToAnnexB(byte[] data, int offset, int length) {
    return avccToAnnexB(data, offset, length, 4);
  }

  /** Convenience variant that allocates a correctly sized Annex-B array. */
  public static byte[] avccToAnnexB(byte[] src, int off, int len, int lengthSize) {
    int need = annexBLength(src, off, len, lengthSize);
    byte[] out = new byte[need];
    convertAvccToAnnexB(src, off, len, lengthSize, out, 0);
    return out;
  }

  /** True if the buffer starts like an Annex-B access unit ({@code 00 00 01} or {@code 00 00 00 01}). */
  public static boolean looksLikeAnnexB(byte[] data, int off, int len) {
    if (data == null || off < 0 || len <= 0 || off + len > data.length) {
      return false;
    }
    if (len >= 4 && data[off] == 0 && data[off + 1] == 0 && data[off + 2] == 0
        && data[off + 3] == 1) {
      return true;
    }
    return len >= 3 && data[off] == 0 && data[off + 1] == 0 && data[off + 2] == 1;
  }

  // ------------------------------------------------------------------------------------
  // internals
  // ------------------------------------------------------------------------------------

  private static void checkSlice(byte[] data, int off, int len, String what) {
    if (data == null) {
      throw new IllegalArgumentException(what + " is null");
    }
    if (off < 0 || len < 0 || off > data.length - len) {
      throw new IllegalArgumentException("bad " + what + " slice off=" + off + " len=" + len
          + " arrayLength=" + data.length);
    }
  }

  private static void checkLengthSize(int lengthSize) {
    if (lengthSize < MIN_LENGTH_SIZE || lengthSize > MAX_LENGTH_SIZE) {
      throw new IllegalArgumentException("bad AVCC lengthSize " + lengthSize
          + " (expected 1..4)");
    }
  }

  private static int readU16(byte[] b, int off) {
    return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
  }

  /** Reads a big-endian unsigned prefix of {@code lengthSize} (<= 4) bytes. */
  private static int readLength(byte[] b, int off, int lengthSize) {
    int value = 0;
    for (int i = 0; i < lengthSize; i++) {
      value = (value << 8) | (b[off + i] & 0xff);
    }
    return value;
  }

  private static void writeStartCode(ByteArrayOutputStream out) {
    out.write(0);
    out.write(0);
    out.write(0);
    out.write(1);
  }
}
