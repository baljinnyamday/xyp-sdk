package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SignerTest {

  private static final String TOKEN = "test-access-token";
  private static final long FIXED_UNIX_TIME = 1_700_000_000L;
  private static final Clock FIXED =
      Clock.fixed(Instant.ofEpochSecond(FIXED_UNIX_TIME, 999_000_000), ZoneOffset.UTC);

  @Test
  void signsTokenDotTimestampWithRsaSha256() throws GeneralSecurityException {
    Signer.Credentials credentials = new Signer(TOKEN, TestKeys.RSA.getPrivate(), FIXED).sign();

    assertEquals(TOKEN, credentials.accessToken());
    assertEquals(
        Long.toString(FIXED_UNIX_TIME), credentials.timeStamp(), "whole seconds, truncated");
    byte[] signature = Base64.getDecoder().decode(credentials.signature());
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(TestKeys.RSA.getPublic());
    verifier.update((TOKEN + "." + FIXED_UNIX_TIME).getBytes(StandardCharsets.UTF_8));
    assertTrue(verifier.verify(signature), "the signature does not verify");

    // PKCS#1 v1.5 is deterministic: the same bytes as any other correct implementation.
    Signature reference = Signature.getInstance("SHA256withRSA");
    reference.initSign(TestKeys.RSA.getPrivate());
    reference.update((TOKEN + "." + FIXED_UNIX_TIME).getBytes(StandardCharsets.UTF_8));
    assertEquals(Base64.getEncoder().encodeToString(reference.sign()), credentials.signature());
  }

  @Test
  void usesWholeSecondsOfNowByDefault() {
    Signer.Credentials credentials =
        new Signer(TOKEN, TestKeys.RSA.getPrivate(), Clock.systemUTC()).sign();
    long stamp = Long.parseLong(credentials.timeStamp());
    long drift = Math.abs(Instant.now().getEpochSecond() - stamp);
    assertTrue(drift <= 5, "timeStamp is " + drift + "s away from now");
  }

  @Test
  void isFreshOnEveryRequest() {
    AtomicLong ticks = new AtomicLong();
    Clock ticking =
        new Clock() {
          @Override
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return Instant.ofEpochSecond(FIXED_UNIX_TIME)
                .plus(Duration.ofSeconds(ticks.incrementAndGet()));
          }
        };
    Signer signer = new Signer(TOKEN, TestKeys.RSA.getPrivate(), ticking);

    Signer.Credentials first = signer.sign();
    Signer.Credentials second = signer.sign();

    assertNotEquals(first.timeStamp(), second.timeStamp());
    assertNotEquals(
        first.signature(),
        second.signature(),
        "XYP rejects stale timestamps, so a signature must never be reused");
  }

  @Test
  void aKeyThatCannotSignIsAConfigErrorThatSaysNothingAboutTheKey() {
    PrivateKey broken = new UnusableKey();

    XypConfigException error =
        assertThrows(XypConfigException.class, () -> new Signer(TOKEN, broken, FIXED).sign());
    assertEquals("the private key could not sign the request", error.getMessage());
  }

  @Test
  void credentialsNeverPrintTheTokenOrTheSignature() {
    Signer.Credentials credentials = new Signer(TOKEN, TestKeys.RSA.getPrivate(), FIXED).sign();
    assertFalse(credentials.toString().contains(TOKEN), credentials.toString());
    assertFalse(credentials.toString().contains(credentials.signature()), credentials.toString());
  }

  /** Claims to be RSA, but no provider can sign with it. */
  private static final class UnusableKey implements PrivateKey {
    private static final long serialVersionUID = 1L;

    @Override
    public String getAlgorithm() {
      return "RSA";
    }

    @Override
    public String getFormat() {
      return null;
    }

    @Override
    public byte[] getEncoded() {
      return null;
    }
  }
}
