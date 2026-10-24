package app.webcast.media;

import java.io.ByteArrayOutputStream;

/**
 * Plain-JVM unit tests for {@link H264Util}. No JUnit / no Android: run with
 * tools/run-media-tests.sh (javac + java, -source 8).
 *
 * <p>Exit code 0 = all checks passed, 1 = at least one failure.</p>
 */
public final class H264UtilTest {

  private static int passed;
  private static int failed;

  private static final byte[] SPS_1 = bytes(0x67, 0x42, 0xE0, 0x28, 0xAA, 0x01);
  private static final byte[] PPS_1 = bytes(0x68, 0xCE, 0x3C, 0x80);
  private static final byte[] SPS_2 = bytes(0x67, 0x4D, 0x40, 0x28, 0xBB);
  private static final byte[] PPS_2 = bytes(0x68, 0xEE, 0x3C, 0x80, 0x11);
  private static final byte[] START_CODE = bytes(0x00, 0x00, 0x00, 0x01);

  public static void main(String[] args) {
    testParseSimple();
    testParseBoxWrapped();
    testParseWithOffsets();
    testParseMultipleSpsPps();
    testParseLengthSize();
    testParseInvalid();
    testConvertBasic();
    testConvertMultiNal();
    testConvertLengthSizes();
    testConvertBoundaries();
    testConvertInvalid();
    testAnnexBLength();
    testConvenience();
    testLooksLikeAnnexB();

    System.out.println("H264UtilTest: " + passed + " passed, " + failed + " failed");
    if (failed > 0) {
      System.out.println("RESULT: FAIL");
      System.exit(1);
    }
    System.out.println("RESULT: PASS");
  }

  // ------------------------------------------------------------------------------------
  // parseAvcC
  // ------------------------------------------------------------------------------------

  private static void testParseSimple() {
    byte[] avcc = buildAvcC(false, 3, new byte[][] {SPS_1}, new byte[][] {PPS_1});
    H264Util.SpsPps cc = H264Util.parseAvcC(avcc);
    eqInt("simple.lengthSize", 4, cc.lengthSize);
    eqInt("simple.spsCount", 1, cc.spsCount);
    eqInt("simple.ppsCount", 1, cc.ppsCount);
    eqInt("simple.profile", 0x42, cc.profileIdc);
    eqInt("simple.level", 0x28, cc.levelIdc);
    eqInt("simple.constraints", 0xE0, cc.constraints);
    eqBytes("simple.csd0", concat(START_CODE, SPS_1), cc.csd0);
    eqBytes("simple.csd1", concat(START_CODE, PPS_1), cc.csd1);
  }

  private static void testParseBoxWrapped() {
    byte[] boxed = buildAvcC(true, 3, new byte[][] {SPS_1}, new byte[][] {PPS_1});
    H264Util.SpsPps cc = H264Util.parseAvcC(boxed);
    eqBytes("box.csd0", concat(START_CODE, SPS_1), cc.csd0);
    eqBytes("box.csd1", concat(START_CODE, PPS_1), cc.csd1);
  }

  private static void testParseWithOffsets() {
    byte[] record = buildAvcC(false, 3, new byte[][] {SPS_2}, new byte[][] {PPS_2});
    byte[] padded = new byte[record.length + 12];
    System.arraycopy(record, 0, padded, 5, record.length);
    H264Util.SpsPps cc = H264Util.parseAvcC(padded, 5, record.length);
    eqBytes("offsets.csd0", concat(START_CODE, SPS_2), cc.csd0);
    eqBytes("offsets.csd1", concat(START_CODE, PPS_2), cc.csd1);
  }

  private static void testParseMultipleSpsPps() {
    byte[] avcc = buildAvcC(false, 3, new byte[][] {SPS_1, SPS_2},
        new byte[][] {PPS_1, PPS_2});
    H264Util.SpsPps cc = H264Util.parseAvcC(avcc);
    eqInt("multi.spsCount", 2, cc.spsCount);
    eqInt("multi.ppsCount", 2, cc.ppsCount);
    eqBytes("multi.csd0", concat(START_CODE, SPS_1, START_CODE, SPS_2), cc.csd0);
    eqBytes("multi.csd1", concat(START_CODE, PPS_1, START_CODE, PPS_2), cc.csd1);
  }

  private static void testParseLengthSize() {
    for (int minusOne = 0; minusOne <= 3; minusOne++) {
      byte[] avcc = buildAvcC(false, minusOne, new byte[][] {SPS_1}, new byte[][] {PPS_1});
      H264Util.SpsPps cc = H264Util.parseAvcC(avcc);
      eqInt("lengthSize[" + minusOne + "]", minusOne + 1, cc.lengthSize);
    }
  }

  private static void testParseInvalid() {
    final byte[] good = buildAvcC(false, 3, new byte[][] {SPS_1}, new byte[][] {PPS_1});
    expectIAE("parse.null", new Block() {
      @Override public void run() { H264Util.parseAvcC(null); }
    });
    expectIAE("parse.empty", new Block() {
      @Override public void run() { H264Util.parseAvcC(new byte[0]); }
    });
    expectIAE("parse.short", new Block() {
      @Override public void run() { H264Util.parseAvcC(bytes(1, 0x42, 0xE0, 0x28)); }
    });
    expectIAE("parse.badRange", new Block() {
      @Override public void run() { H264Util.parseAvcC(good, 4, good.length); }
    });
    expectIAE("parse.badVersion", new Block() {
      @Override public void run() { H264Util.parseAvcC(withByte(good, 0, 2)); }
    });
    expectIAE("parse.noSps", new Block() {
      @Override public void run() { H264Util.parseAvcC(withByte(good, 5, 0xE0)); }
    });
    expectIAE("parse.emptySps", new Block() {
      @Override public void run() {
        // one SPS with declared length 0
        byte[] bad = buildAvcC(false, 3, new byte[][] {new byte[0]}, new byte[][] {PPS_1});
        H264Util.parseAvcC(bad);
      }
    });
    expectIAE("parse.truncatedSps", new Block() {
      @Override public void run() {
        H264Util.parseAvcC(good, 0, 8); // header says SPS follows, but nothing is there
      }
    });
    expectIAE("parse.noPps", new Block() {
      @Override public void run() {
        byte[] bad = buildAvcC(false, 3, new byte[][] {SPS_1}, new byte[][] {});
        H264Util.parseAvcC(bad);
      }
    });
    expectIAE("parse.truncatedPps", new Block() {
      @Override public void run() {
        byte[] bad = buildAvcC(false, 3, new byte[][] {SPS_1}, new byte[][] {PPS_1});
        H264Util.parseAvcC(bad, 0, bad.length - 2);
      }
    });
  }

  // ------------------------------------------------------------------------------------
  // convertAvccToAnnexB / annexBLength
  // ------------------------------------------------------------------------------------

  private static void testConvertBasic() {
    byte[] n1 = bytes(0x09, 0x10);
    byte[] avcc = avcc(4, n1);
    byte[] out = H264Util.avccToAnnexB(avcc, 0, avcc.length, 4);
    eqBytes("convert.basic", concat(START_CODE, n1), out);
  }

  private static void testConvertMultiNal() {
    byte[] n1 = bytes(0x67, 0x11);
    byte[] n2 = bytes(0x68, 0x22, 0x33);
    byte[] n3 = bytes(0x65, 0x44, 0x55, 0x66);
    byte[] avcc = avcc(4, n1, n2, n3);
    byte[] expected = concat(START_CODE, n1, START_CODE, n2, START_CODE, n3);
    byte[] out = H264Util.avccToAnnexB(avcc, 0, avcc.length, 4);
    eqBytes("convert.multi", expected, out);
    eqInt("annexBLength.multi", expected.length, H264Util.annexBLength(avcc, 0, avcc.length, 4));
  }

  private static void testConvertLengthSizes() {
    byte[] n1 = bytes(0x65, 0x01, 0x02);
    byte[] n2 = bytes(0x41, 0x03);
    for (int ls = 1; ls <= 4; ls++) {
      byte[] avcc = avcc(ls, n1, n2);
      byte[] expected = concat(START_CODE, n1, START_CODE, n2);
      byte[] out = H264Util.avccToAnnexB(avcc, 0, avcc.length, ls);
      eqBytes("convert.lengthSize." + ls, expected, out);
    }
  }

  private static void testConvertBoundaries() {
    // empty sample
    eqInt("convert.empty.length", 0, H264Util.avccToAnnexB(new byte[0], 0, 0, 4).length);
    eqInt("annexBLength.empty", 0, H264Util.annexBLength(new byte[0], 0, 0, 4));

    // zero-length NAL units are skipped (some encoders emit them)
    byte[] withEmpty = concat(avcc(4, new byte[] {0x09, 0x10}), bytes(0, 0, 0, 0));
    byte[] out = H264Util.avccToAnnexB(withEmpty, 0, withEmpty.length, 4);
    eqBytes("convert.emptyNalSkipped", concat(START_CODE, bytes(0x09, 0x10)), out);

    // 8 NAL units
    byte[][] nals = new byte[8][];
    for (int i = 0; i < nals.length; i++) {
      nals[i] = bytes(0x41, i, i + 1);
    }
    byte[] avcc = avcc(4, nals);
    byte[] expected = new byte[0];
    for (int i = 0; i < nals.length; i++) {
      expected = concat(expected, START_CODE, nals[i]);
    }
    eqBytes("convert.eightNals", expected, H264Util.avccToAnnexB(avcc, 0, avcc.length, 4));

    // non-zero source offset and destination offset
    byte[] framed = new byte[avcc.length + 7];
    System.arraycopy(avcc, 0, framed, 3, avcc.length);
    byte[] dst = new byte[expected.length + 5];
    int written = H264Util.convertAvccToAnnexB(framed, 3, avcc.length, 4, dst, 5);
    eqInt("convert.dstOff.written", expected.length, written);
    byte[] slice = new byte[written];
    System.arraycopy(dst, 5, slice, 0, written);
    eqBytes("convert.dstOff.bytes", expected, slice);
    for (int i = 0; i < 5; i++) {
      eqInt("convert.dstOff.prefix." + i, 0, dst[i]);
    }

    // destination exactly the required size must be accepted
    byte[] exact = new byte[H264Util.annexBLength(avcc, 0, avcc.length, 4)];
    eqInt("convert.exactSize", exact.length,
        H264Util.convertAvccToAnnexB(avcc, 0, avcc.length, 4, exact, 0));
  }

  private static void testConvertInvalid() {
    final byte[] good = avcc(4, SPS_1, PPS_1);
    expectIAE("convert.nullSrc", new Block() {
      @Override public void run() { H264Util.avccToAnnexB(null, 0, 0, 4); }
    });
    expectIAE("convert.negativeOff", new Block() {
      @Override public void run() { H264Util.avccToAnnexB(good, -1, good.length, 4); }
    });
    expectIAE("convert.overrun", new Block() {
      @Override public void run() { H264Util.avccToAnnexB(good, 2, good.length, 4); }
    });
    expectIAE("convert.badLengthSize0", new Block() {
      @Override public void run() { H264Util.avccToAnnexB(good, 0, good.length, 0); }
    });
    expectIAE("convert.badLengthSize5", new Block() {
      @Override public void run() { H264Util.avccToAnnexB(good, 0, good.length, 5); }
    });
    expectIAE("convert.truncatedLength", new Block() {
      @Override public void run() {
        // declared 4-byte NAL length but only 2 bytes left
        H264Util.avccToAnnexB(bytes(0x00, 0x00), 0, 2, 4);
      }
    });
    expectIAE("convert.nalTooLong", new Block() {
      @Override public void run() {
        H264Util.avccToAnnexB(bytes(0x00, 0x00, 0x00, 0x10, 0x65, 0x01), 0, 6, 4);
      }
    });
    expectIAE("convert.dstNull", new Block() {
      @Override public void run() { H264Util.convertAvccToAnnexB(good, 0, good.length, 4, null, 0); }
    });
    expectIAE("convert.dstTooSmall", new Block() {
      @Override public void run() {
        H264Util.convertAvccToAnnexB(good, 0, good.length, 4, new byte[3], 0);
      }
    });
    expectIAE("convert.dstBadOff", new Block() {
      @Override public void run() {
        H264Util.convertAvccToAnnexB(good, 0, good.length, 4, new byte[64], 65);
      }
    });
  }

  private static void testAnnexBLength() {
    byte[] avcc = avcc(4, bytes(0x65, 0x01), bytes(0x41, 0x02, 0x03), bytes(0x06));
    int expected = (4 + 2) + (4 + 3) + (4 + 1);
    eqInt("annexBLength.basic", expected, H264Util.annexBLength(avcc, 0, avcc.length, 4));
    eqInt("avccToAnnexB.basic.length", expected,
        H264Util.avccToAnnexB(avcc, 0, avcc.length, 4).length);
  }

  private static void testConvenience() {
    byte[] avcc = avcc(2, bytes(0x67, 0x42), bytes(0x68, 0xCE));
    byte[] expected = concat(START_CODE, bytes(0x67, 0x42), START_CODE, bytes(0x68, 0xCE));
    eqBytes("convenience.bytes", expected, H264Util.avccToAnnexB(avcc, 0, avcc.length, 2));

    // contract API from docs/INTERFACES.md: avccToAnnexB(data, offset, length), 4-byte prefixes
    byte[] avcc4 = avcc(4, bytes(0x65, 0x01, 0x02), bytes(0x41, 0x03));
    byte[] expected4 = concat(START_CODE, bytes(0x65, 0x01, 0x02), START_CODE, bytes(0x41, 0x03));
    eqBytes("convenience.contract3args", expected4,
        H264Util.avccToAnnexB(avcc4, 0, avcc4.length));
  }

  private static void testLooksLikeAnnexB() {
    ok("annexb.fourByte", H264Util.looksLikeAnnexB(bytes(0, 0, 0, 1, 0x65), 0, 5));
    ok("annexb.threeByte", H264Util.looksLikeAnnexB(bytes(0, 0, 1, 0x65), 0, 4));
    ok("annexb.avccFalse", !H264Util.looksLikeAnnexB(bytes(0, 0, 0, 5, 0x65), 0, 5));
    ok("annexb.shortFalse", !H264Util.looksLikeAnnexB(bytes(0, 0), 0, 2));
    ok("annexb.nullFalse", !H264Util.looksLikeAnnexB(null, 0, 0));
  }

  // ------------------------------------------------------------------------------------
  // helpers
  // ------------------------------------------------------------------------------------

  private interface Block {
    void run();
  }

  private static void expectIAE(String name, Block block) {
    try {
      block.run();
      fail(name + " (expected IllegalArgumentException, none thrown)");
    } catch (IllegalArgumentException expected) {
      pass(name);
    } catch (Throwable t) {
      fail(name + " (expected IllegalArgumentException, got " + t + ")");
    }
  }

  private static byte[] avcc(int lengthSize, byte[]... nals) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < nals.length; i++) {
      int len = nals[i].length;
      for (int shift = (lengthSize - 1) * 8; shift >= 0; shift -= 8) {
        out.write((len >>> shift) & 0xff);
      }
      out.write(nals[i], 0, len);
    }
    return out.toByteArray();
  }

  private static byte[] buildAvcC(boolean boxHeader, int lengthSizeMinusOne, byte[][] sps,
      byte[][] pps) {
    ByteArrayOutputStream record = new ByteArrayOutputStream();
    record.write(1); // configurationVersion
    record.write(0x42); // AVCProfileIndication (Baseline)
    record.write(0xE0); // profile_compatibility
    record.write(0x28); // AVCLevelIndication (4.0)
    record.write(0xFC | (lengthSizeMinusOne & 0x03)); // reserved + lengthSizeMinusOne
    record.write(0xE0 | (sps.length & 0x1f)); // reserved + numOfSequenceParameterSets
    for (int i = 0; i < sps.length; i++) {
      record.write((sps[i].length >>> 8) & 0xff);
      record.write(sps[i].length & 0xff);
      record.write(sps[i], 0, sps[i].length);
    }
    record.write(pps.length & 0xff);
    for (int i = 0; i < pps.length; i++) {
      record.write((pps[i].length >>> 8) & 0xff);
      record.write(pps[i].length & 0xff);
      record.write(pps[i], 0, pps[i].length);
    }
    byte[] body = record.toByteArray();
    if (!boxHeader) {
      return body;
    }
    ByteArrayOutputStream box = new ByteArrayOutputStream();
    int boxSize = body.length + 8;
    box.write((boxSize >>> 24) & 0xff);
    box.write((boxSize >>> 16) & 0xff);
    box.write((boxSize >>> 8) & 0xff);
    box.write(boxSize & 0xff);
    box.write('a');
    box.write('v');
    box.write('c');
    box.write('C');
    box.write(body, 0, body.length);
    return box.toByteArray();
  }

  private static byte[] withByte(byte[] src, int index, int value) {
    byte[] out = new byte[src.length];
    System.arraycopy(src, 0, out, 0, src.length);
    out[index] = (byte) value;
    return out;
  }

  private static byte[] bytes(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }

  private static byte[] concat(byte[]... parts) {
    int total = 0;
    for (int i = 0; i < parts.length; i++) {
      total += parts[i].length;
    }
    byte[] out = new byte[total];
    int pos = 0;
    for (int i = 0; i < parts.length; i++) {
      System.arraycopy(parts[i], 0, out, pos, parts[i].length);
      pos += parts[i].length;
    }
    return out;
  }

  private static void eqInt(String name, int expected, int actual) {
    if (expected == actual) {
      pass(name);
    } else {
      fail(name + " (expected " + expected + ", got " + actual + ")");
    }
  }

  private static void eqBytes(String name, byte[] expected, byte[] actual) {
    if (expected == null || actual == null) {
      fail(name + " (null array: expected=" + expected + ", actual=" + actual + ")");
      return;
    }
    if (expected.length != actual.length) {
      fail(name + " (length expected " + expected.length + ", got " + actual.length + ")");
      return;
    }
    for (int i = 0; i < expected.length; i++) {
      if (expected[i] != actual[i]) {
        fail(name + " (byte " + i + ": expected 0x" + Integer.toHexString(expected[i] & 0xff)
            + ", got 0x" + Integer.toHexString(actual[i] & 0xff) + ")");
        return;
      }
    }
    pass(name);
  }

  private static void ok(String name, boolean condition) {
    if (condition) {
      pass(name);
    } else {
      fail(name);
    }
  }

  private static void pass(String name) {
    passed++;
    System.out.println("  ok   " + name);
  }

  private static void fail(String message) {
    failed++;
    System.out.println("  FAIL " + message);
  }
}
