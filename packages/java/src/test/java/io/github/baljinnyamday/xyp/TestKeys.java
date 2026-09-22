package io.github.baljinnyamday.xyp;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/**
 * Throwaway keys generated for the test run. Real keys never belong in a repository, not even in
 * tests. One RSA key is shared by the tests that do not care which key signs, because generating
 * one takes a noticeable fraction of a second.
 */
final class TestKeys {

  static final KeyPair RSA = generate("RSA", 2048);

  private TestKeys() {}

  static KeyPair generate(String algorithm, int size) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
      generator.initialize(size);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException failure) {
      throw new AssertionError("cannot generate a " + algorithm + " key", failure);
    }
  }
}
