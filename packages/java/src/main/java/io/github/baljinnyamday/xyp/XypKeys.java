package io.github.baljinnyamday.xyp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the RSA private key that signs XYP requests, from PEM or DER, in PKCS#8 or PKCS#1 form.
 *
 * <pre>{@code
 * PrivateKey key = XypKeys.loadPrivateKey(Path.of("private.key"));
 * }</pre>
 *
 * An encrypted key is refused with the command that decrypts it; a key kept in a {@code KeyStore}
 * or an HSM is passed to {@link XypClient.Builder#privateKey(PrivateKey)} directly instead. The
 * exception messages never contain key material, parser messages or file contents.
 */
public final class XypKeys {

  private static final String PEM_PRIVATE_KEY = "PRIVATE KEY";
  private static final String PEM_RSA_PRIVATE_KEY = "RSA PRIVATE KEY";
  private static final String PEM_ENCRYPTED_PRIVATE_KEY = "ENCRYPTED PRIVATE KEY";

  /** Marks a legacy OpenSSL key encrypted with a passphrase. */
  private static final String PROC_TYPE_HEADER = "Proc-Type";

  private static final String PEM_MARKER = "-----BEGIN";

  private static final Pattern PEM_BLOCK =
      Pattern.compile("-----BEGIN ([^\\r\\n-]+)-----\\r?\\n(.*?)-----END \\1-----", Pattern.DOTALL);

  private static final Pattern WHITESPACE = Pattern.compile("\\s");

  private XypKeys() {}

  /**
   * Reads an RSA private key from a PEM or DER file.
   *
   * @param path the key file
   * @return the key
   * @throws XypConfigException when the file cannot be read, or does not hold an unencrypted RSA
   *     private key; the message names only the failure class
   */
  public static PrivateKey loadPrivateKey(Path path) {
    Objects.requireNonNull(path, "path");
    byte[] data;
    try {
      data = Files.readAllBytes(path);
    } catch (IOException | SecurityException failure) {
      // Only the failure class, never the path's contents or the key itself.
      throw new XypConfigException(
          "cannot read the private key file (" + fileProblem(failure) + ")");
    }
    return parsePrivateKey(data);
  }

  /**
   * Reads an RSA private key from PEM text or DER bytes.
   *
   * @param data the PEM text (UTF-8 or ASCII) or the DER bytes
   * @return the key
   * @throws XypConfigException when the data is not an unencrypted RSA private key
   */
  public static PrivateKey parsePrivateKey(byte[] data) {
    Objects.requireNonNull(data, "data");
    String text = new String(data, StandardCharsets.ISO_8859_1);
    if (text.contains(PEM_MARKER)) {
      return parsePem(text);
    }
    // DER names no format, so both common RSA encodings are tried.
    return parseDer(data);
  }

  private static PrivateKey parsePem(String text) {
    Matcher block = PEM_BLOCK.matcher(text);
    if (!block.find()) {
      throw notAPrivateKey();
    }
    String type = block.group(1);
    String[] lines = block.group(2).split("\\r?\\n", -1);
    Map<String, String> headers = new HashMap<>();
    int line = 0;
    while (line < lines.length && lines[line].indexOf(':') >= 0) {
      String header = lines[line++];
      int colon = header.indexOf(':');
      headers.put(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
    }
    if (type.equals(PEM_ENCRYPTED_PRIVATE_KEY) || headers.containsKey(PROC_TYPE_HEADER)) {
      throw new XypConfigException(
          "the private key is encrypted, which this SDK cannot decrypt. Decrypt it once with "
              + "`openssl pkey -in encrypted.key -out private.key`, or pass a PrivateKey of your "
              + "own (from a KeyStore, or an HSM through PKCS#11) to "
              + "XypClient.Builder.privateKey");
    }
    StringBuilder body = new StringBuilder();
    for (; line < lines.length; line++) {
      body.append(lines[line]);
    }
    byte[] der;
    try {
      der = Base64.getDecoder().decode(WHITESPACE.matcher(body).replaceAll(""));
    } catch (IllegalArgumentException notBase64) {
      throw notAPrivateKey();
    }
    return switch (type) {
      case PEM_PRIVATE_KEY -> parsePkcs8(der);
      case PEM_RSA_PRIVATE_KEY -> rsaKey(Der.wrapPkcs1(der));
      default -> parseDer(der);
    };
  }

  private static PrivateKey parseDer(byte[] der) {
    if (Der.pkcs8Shape(der) != Der.KeyShape.NOT_PKCS8) {
      return parsePkcs8(der);
    }
    return rsaKey(Der.wrapPkcs1(der));
  }

  private static PrivateKey parsePkcs8(byte[] der) {
    return switch (Der.pkcs8Shape(der)) {
      case PKCS8_RSA -> rsaKey(der);
      case PKCS8_OTHER -> throw notAnRsaKey();
      case NOT_PKCS8 -> throw notAPrivateKey();
    };
  }

  /**
   * Reads a PKCS#8 RSA key. The underlying exception is deliberately dropped: its message can echo
   * key material into a log.
   */
  private static PrivateKey rsaKey(byte[] pkcs8) {
    try {
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    } catch (GeneralSecurityException | RuntimeException invalid) {
      throw notAPrivateKey();
    }
  }

  static XypConfigException notAPrivateKey() {
    return new XypConfigException("privateKey is not a valid PEM or DER private key");
  }

  static XypConfigException notAnRsaKey() {
    return new XypConfigException("privateKey must be an RSA key");
  }

  private static String fileProblem(Exception failure) {
    if (failure instanceof NoSuchFileException) {
      return "no such file";
    }
    if (failure instanceof AccessDeniedException || failure instanceof SecurityException) {
      return "permission denied";
    }
    return "unreadable";
  }
}
