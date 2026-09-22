package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class XypKeysTest {

  private static final byte[] PKCS8 = TestKeys.RSA.getPrivate().getEncoded();
  private static final String SECRET = "secret-material";

  static String pem(String type, byte[] der) {
    return "-----BEGIN "
        + type
        + "-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
        + "\n-----END "
        + type
        + "-----\n";
  }

  /**
   * The PKCS#1 RSAPrivateKey inside a PKCS#8 PrivateKeyInfo: the content of its OCTET STRING, which
   * is the last element and, for a 2048-bit key, uses a two-byte length.
   */
  static byte[] pkcs1Of(byte[] pkcs8) {
    for (int index = pkcs8.length - 1; index >= 3; index--) {
      if (pkcs8[index - 3] == 0x04 && pkcs8[index - 2] == (byte) 0x82) {
        int length = ((pkcs8[index - 1] & 0xFF) << 8) | (pkcs8[index] & 0xFF);
        if (index + 1 + length == pkcs8.length) {
          return Arrays.copyOfRange(pkcs8, index + 1, pkcs8.length);
        }
      }
    }
    throw new AssertionError("no PKCS#1 key inside the PKCS#8 encoding");
  }

  static Stream<Arguments> bothEncodingsInPemAndDer() {
    byte[] pkcs1 = pkcs1Of(PKCS8);
    return Stream.of(
        Arguments.of("PKCS#8 PEM", pem("PRIVATE KEY", PKCS8).getBytes(StandardCharsets.US_ASCII)),
        Arguments.of(
            "PKCS#1 PEM", pem("RSA PRIVATE KEY", pkcs1).getBytes(StandardCharsets.US_ASCII)),
        Arguments.of(
            "PKCS#8 PEM with CRLF",
            pem("PRIVATE KEY", PKCS8).replace("\n", "\r\n").getBytes(StandardCharsets.US_ASCII)),
        Arguments.of(
            "PKCS#8 PEM after other text",
            ("Bag Attributes\n" + pem("PRIVATE KEY", PKCS8)).getBytes(StandardCharsets.US_ASCII)),
        Arguments.of("PKCS#8 DER", PKCS8),
        Arguments.of("PKCS#1 DER", pkcs1));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void bothEncodingsInPemAndDer(String name, byte[] data) {
    PrivateKey parsed = XypKeys.parsePrivateKey(data);
    assertEquals("RSA", parsed.getAlgorithm());
    RSAPrivateCrtKey want = (RSAPrivateCrtKey) TestKeys.RSA.getPrivate();
    RSAPrivateCrtKey got = (RSAPrivateCrtKey) parsed;
    assertEquals(want.getModulus(), got.getModulus(), "a different key came back");
    assertEquals(want.getPrivateExponent(), got.getPrivateExponent());
    assertArrayEquals(PKCS8, parsed.getEncoded());
  }

  @Test
  void theDerWrapperHandlesEveryLengthForm() {
    for (int size : new int[] {0, 1, 127, 128, 255, 256, 65_535, 65_536}) {
      byte[] content = new byte[size];
      byte[] wrapped = Der.wrapPkcs1(content);
      assertEquals(
          Der.KeyShape.PKCS8_RSA, Der.pkcs8Shape(wrapped), "content of " + size + " bytes");
    }
  }

  @Test
  void loadPrivateKeyReadsAFile(@TempDir Path directory) throws IOException {
    Path path = directory.resolve("private.key");
    Files.writeString(path, pem("PRIVATE KEY", PKCS8));

    assertArrayEquals(PKCS8, XypKeys.loadPrivateKey(path).getEncoded());
  }

  @Test
  void loadPrivateKeyNamesOnlyTheFailureClass(@TempDir Path directory) {
    XypConfigException missing =
        assertThrows(
            XypConfigException.class,
            () -> XypKeys.loadPrivateKey(directory.resolve("absent.key")));
    assertEquals("cannot read the private key file (no such file)", missing.getMessage());
    assertEquals(Origin.CONFIG, missing.origin());

    XypConfigException directoryInstead =
        assertThrows(XypConfigException.class, () -> XypKeys.loadPrivateKey(directory));
    assertEquals("cannot read the private key file (unreadable)", directoryInstead.getMessage());
  }

  static Stream<Arguments> refusesBadInputWithoutLeakingKeyMaterial() {
    KeyPair ec = TestKeys.generate("EC", 256);
    String legacy =
        "-----BEGIN RSA PRIVATE KEY-----\n"
            + "Proc-Type: 4,ENCRYPTED\n"
            + "DEK-Info: AES-128-CBC,00\n\n"
            + Base64.getEncoder().encodeToString(SECRET.getBytes(StandardCharsets.US_ASCII))
            + "\n-----END RSA PRIVATE KEY-----\n";
    byte[] truncated = Arrays.copyOf(PKCS8, PKCS8.length / 2);
    return Stream.of(
        Arguments.of(
            "garbage PEM",
            "-----BEGIN PRIVATE KEY-----\n" + SECRET + "\n-----END PRIVATE KEY-----",
            "privateKey is not a valid PEM or DER private key"),
        Arguments.of("garbage DER", SECRET, "privateKey is not a valid PEM or DER private key"),
        Arguments.of(
            "an unterminated PEM block",
            "-----BEGIN PRIVATE KEY-----\n" + SECRET,
            "privateKey is not a valid PEM or DER private key"),
        Arguments.of(
            "a PKCS#1 key labelled PKCS#8",
            pem("PRIVATE KEY", pkcs1Of(PKCS8)),
            "privateKey is not a valid PEM or DER private key"),
        Arguments.of(
            "a truncated key",
            pem("PRIVATE KEY", truncated),
            "privateKey is not a valid PEM or DER private key"),
        Arguments.of(
            "an EC key",
            pem("PRIVATE KEY", ec.getPrivate().getEncoded()),
            "privateKey must be an RSA key"),
        Arguments.of(
            "an EC key in DER",
            new String(ec.getPrivate().getEncoded(), StandardCharsets.ISO_8859_1),
            "privateKey must be an RSA key"),
        Arguments.of(
            "a PKCS#8 encrypted key",
            pem("ENCRYPTED PRIVATE KEY", SECRET.getBytes(StandardCharsets.US_ASCII)),
            "openssl pkey -in encrypted.key -out private.key"),
        Arguments.of(
            "a legacy encrypted key", legacy, "openssl pkey -in encrypted.key -out private.key"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void refusesBadInputWithoutLeakingKeyMaterial(String name, String data, String want) {
    XypConfigException error =
        assertThrows(
            XypConfigException.class,
            () -> XypKeys.parsePrivateKey(data.getBytes(StandardCharsets.ISO_8859_1)));
    assertTrue(error.getMessage().contains(want), error.getMessage());
    assertFalse(error.getMessage().contains(SECRET), "key material reached the message");
    assertTrue(error.getCause() == null, "a parser's exception can quote the key");
  }

  @Test
  void anEncryptedKeyPointsAtTheAlternatives() {
    XypConfigException error =
        assertThrows(
            XypConfigException.class,
            () ->
                XypKeys.parsePrivateKey(
                    pem("ENCRYPTED PRIVATE KEY", new byte[] {1})
                        .getBytes(StandardCharsets.US_ASCII)));
    assertTrue(error.getMessage().contains("PKCS#11"), error.getMessage());
    assertTrue(error.getMessage().contains("KeyStore"), error.getMessage());
  }
}
