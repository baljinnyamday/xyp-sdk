package io.github.baljinnyamday.xyp;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Just enough DER to tell a PKCS#8 key's algorithm and to wrap a PKCS#1 RSA key into PKCS#8, the
 * only encoding the JDK's {@code KeyFactory} reads. Every read is bounds-checked: the input is
 * whatever file the caller pointed at.
 */
final class Der {

  private static final int SEQUENCE = 0x30;
  private static final int INTEGER = 0x02;
  private static final int OCTET_STRING = 0x04;
  private static final int OBJECT_IDENTIFIER = 0x06;

  /** 1.2.840.113549.1.1.1, rsaEncryption. */
  private static final byte[] RSA_ENCRYPTION = {
    0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01
  };

  /** The DER of the rsaEncryption AlgorithmIdentifier, with its NULL parameters. */
  private static final byte[] RSA_ALGORITHM_IDENTIFIER = {
    0x30,
    0x0D,
    0x06,
    0x09,
    0x2A,
    (byte) 0x86,
    0x48,
    (byte) 0x86,
    (byte) 0xF7,
    0x0D,
    0x01,
    0x01,
    0x01,
    0x05,
    0x00
  };

  private Der() {}

  /** What a blob of DER holds, as far as key loading cares. */
  enum KeyShape {
    /** A PKCS#8 PrivateKeyInfo for rsaEncryption. */
    PKCS8_RSA,
    /** A PKCS#8 PrivateKeyInfo for any other algorithm. */
    PKCS8_OTHER,
    /** Not a PKCS#8 PrivateKeyInfo. */
    NOT_PKCS8
  }

  /**
   * Reads the outline of a PKCS#8 PrivateKeyInfo: SEQUENCE { INTEGER version, SEQUENCE { OID
   * algorithm, ... }, OCTET STRING privateKey, ... }.
   */
  static KeyShape pkcs8Shape(byte[] der) {
    Reader outer = new Reader(der, 0, der.length);
    Reader info = outer.enter(SEQUENCE);
    if (info == null || !outer.atEnd()) {
      return KeyShape.NOT_PKCS8;
    }
    Reader algorithm = info.skip(INTEGER) ? info.enter(SEQUENCE) : null;
    if (algorithm == null) {
      return KeyShape.NOT_PKCS8;
    }
    byte[] oid = algorithm.read(OBJECT_IDENTIFIER);
    if (oid == null || info.read(OCTET_STRING) == null) {
      return KeyShape.NOT_PKCS8;
    }
    return Arrays.equals(oid, RSA_ENCRYPTION) ? KeyShape.PKCS8_RSA : KeyShape.PKCS8_OTHER;
  }

  /** Wraps a PKCS#1 RSAPrivateKey into a PKCS#8 PrivateKeyInfo, version 0. */
  static byte[] wrapPkcs1(byte[] pkcs1) {
    ByteArrayOutputStream content = new ByteArrayOutputStream(pkcs1.length + 32);
    content.writeBytes(new byte[] {INTEGER, 0x01, 0x00});
    content.writeBytes(RSA_ALGORITHM_IDENTIFIER);
    writeElement(content, OCTET_STRING, pkcs1);
    ByteArrayOutputStream out = new ByteArrayOutputStream(content.size() + 8);
    writeElement(out, SEQUENCE, content.toByteArray());
    return out.toByteArray();
  }

  private static void writeElement(ByteArrayOutputStream out, int tag, byte[] content) {
    out.write(tag);
    int length = content.length;
    if (length < 0x80) {
      out.write(length);
    } else {
      int bytes = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
      out.write(0x80 | bytes);
      for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
        out.write(length >>> shift);
      }
    }
    out.writeBytes(content);
  }

  /** Reads consecutive elements from {@code data[position, end)}. */
  private static final class Reader {

    /** Lengths beyond four bytes would exceed any array this could be reading. */
    private static final int MAX_LENGTH_BYTES = 4;

    private final byte[] data;
    private final int end;
    private int position;

    Reader(byte[] data, int position, int end) {
      this.data = data;
      this.position = position;
      this.end = end;
    }

    boolean atEnd() {
      return position == end;
    }

    /** Reads an element with this tag and returns a reader of its content, or null. */
    Reader enter(int tag) {
      int[] span = next(tag);
      return span == null ? null : new Reader(data, span[0], span[1]);
    }

    /** Skips an element with this tag; false when the next element has another tag. */
    boolean skip(int tag) {
      return next(tag) != null;
    }

    /** Reads the content of an element with this tag, or null. */
    byte[] read(int tag) {
      int[] span = next(tag);
      return span == null ? null : Arrays.copyOfRange(data, span[0], span[1]);
    }

    /** The content span of the next element if it has this tag, advancing past it; else null. */
    private int[] next(int tag) {
      if (end - position < 2 || (data[position] & 0xFF) != tag) {
        return null;
      }
      int cursor = position + 1;
      int length = data[cursor++] & 0xFF;
      if (length >= 0x80) {
        int bytes = length & 0x7F;
        if (bytes == 0 || bytes > MAX_LENGTH_BYTES || end - cursor < bytes) {
          return null;
        }
        long longLength = 0;
        for (int index = 0; index < bytes; index++) {
          longLength = (longLength << 8) | (data[cursor++] & 0xFF);
        }
        if (longLength > end - cursor) {
          return null;
        }
        length = (int) longLength;
      }
      if (length > end - cursor) {
        return null;
      }
      position = cursor + length;
      return new int[] {cursor, cursor + length};
    }
  }
}
