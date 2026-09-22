package io.github.baljinnyamday.xyp;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;

/**
 * Signs requests the way XYP checks them: {@code signature} is base64(SHA256withRSA(UTF-8(
 * accessToken + "." + timeStamp))), and {@code timeStamp} is the current time in whole seconds
 * since the epoch.
 */
final class Signer {

  /**
   * XYP requires these three headers on every call, with their names in this mixed case on the
   * wire.
   */
  static final String ACCESS_TOKEN_HEADER = "accessToken";

  static final String TIME_STAMP_HEADER = "timeStamp";
  static final String SIGNATURE_HEADER = "signature";

  private static final String ALGORITHM = "SHA256withRSA";

  private final String accessToken;
  private final PrivateKey key;
  private final Clock clock;

  Signer(String accessToken, PrivateKey key, Clock clock) {
    this.accessToken = accessToken;
    this.key = key;
    this.clock = clock;
  }

  /** The credential headers of one request. */
  record Credentials(String accessToken, String timeStamp, String signature) {

    /** Keeps the token and the signature out of logs and assertion messages. */
    @Override
    public String toString() {
      return "Credentials[timeStamp=" + timeStamp + "]";
    }
  }

  /**
   * Signs one request. XYP rejects stale timestamps, so the result is never reused between
   * requests.
   *
   * @throws XypConfigException when the key cannot sign
   */
  Credentials sign() {
    String timeStamp = Long.toString(clock.instant().getEpochSecond());
    try {
      // A new Signature per request: instances are not thread-safe, and the provider is chosen
      // for this key when it is initialised, which is what lets an HSM (PKCS#11) key sign.
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initSign(key);
      signature.update((accessToken + "." + timeStamp).getBytes(StandardCharsets.UTF_8));
      return new Credentials(
          accessToken, timeStamp, Base64.getEncoder().encodeToString(signature.sign()));
    } catch (GeneralSecurityException | RuntimeException failure) {
      // The provider's message is not passed on: it can describe the key.
      throw new XypConfigException("the private key could not sign the request");
    }
  }
}
