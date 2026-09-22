package io.github.baljinnyamday.xyp;

import static io.github.baljinnyamday.xyp.FakeXyp.ID_CARD;
import static io.github.baljinnyamday.xyp.FakeXyp.soapResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class XypTlsTest {

  /**
   * The fingerprints published in docs/tls.md. If one of these ever changes, the bundled file
   * changed with it, and that has to be a deliberate act.
   */
  private static final String ROOT_FINGERPRINT =
      "CBE7F3FE1F048037C215DA321E58CAA4F363DE9E54BBC442A3BFD62FAD834482";

  private static final String ISSUING_FINGERPRINT =
      "9423640DD74561D1AF1EA8A093860BDA7DF5B5620BB617921395DC0D1A1F980D";

  private static final char[] PASSWORD = "throwaway".toCharArray();

  /** Where the tests run: packages/java. */
  private static final Path REPOSITORY = Path.of("..", "..");

  @TempDir static Path keystoreDirectory;

  private static X509Certificate serverCertificate;
  private static FakeXyp server;

  /**
   * A fake XYP behind a self-signed certificate for 127.0.0.1 only. The key pair is made by the
   * JDK's own keytool for this run, so no key material is ever committed.
   */
  @BeforeAll
  static void startTlsServer() throws Exception {
    Path keystore = keystoreDirectory.resolve("fake-xyp.p12");
    Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
    Process process =
        new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias",
                "fake-xyp",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=fake-xyp",
                "-ext",
                "SAN=ip:127.0.0.1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore.toString(),
                "-storepass",
                new String(PASSWORD),
                "-keypass",
                new String(PASSWORD),
                "-noprompt")
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "keytool did not finish");
    assertEquals(0, process.exitValue(), output);

    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(keystore)) {
      store.load(in, PASSWORD);
    }
    serverCertificate = (X509Certificate) store.getCertificate("fake-xyp");
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, PASSWORD);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), null, null);
    server = FakeXyp.startTls(context, request -> FakeXyp.Reply.ok(soapResponse(ID_CARD, 0, "ok")));
  }

  @AfterAll
  static void stopTlsServer() {
    if (server != null) {
      server.close();
    }
  }

  private static XypClient.Builder client(String baseUrl) {
    return XypClient.builder()
        .accessToken("test-access-token")
        .privateKey(TestKeys.RSA.getPrivate())
        .baseUrl(baseUrl)
        .timeout(Duration.ofSeconds(10))
        .env(name -> null);
  }

  private static Object call(XypClient.Builder builder) {
    try (XypClient client = builder.build()) {
      return client.call("WS100101_getCitizenIDCardInfo", null);
    }
  }

  private static String localhost() {
    return server.baseUrl().replace("127.0.0.1", "localhost");
  }

  @Test
  void aCertificateOutsideTheNationalPkiIsRejectedByDefault() {
    XypConnectionException error =
        assertThrows(XypConnectionException.class, () -> call(client(server.baseUrl())));

    assertEquals(Origin.NETWORK, error.origin());
    assertFalse(error.isTimeout());
    assertTrue(error.getMessage().startsWith("could not reach XYP (TLS: "), error.getMessage());
  }

  @Test
  void trustedCertificatesReplaceTheBundledOnes() {
    Object data = call(client(server.baseUrl()).trustedCertificates(List.of(serverCertificate)));
    assertEquals(Map.of("firstname", "Бат", "regnum", FakeXyp.REGNUM), data);
  }

  @Test
  void aTrustedCertificateStillHasToNameTheHost() {
    XypConnectionException error =
        assertThrows(
            XypConnectionException.class,
            () -> call(client(localhost()).trustedCertificates(List.of(serverCertificate))));
    assertTrue(error.getMessage().contains("TLS"), error.getMessage());
  }

  @Test
  void anSslContextGivesFullControl() {
    Object data =
        call(client(server.baseUrl()).sslContext(XypTls.sslContext(List.of(serverCertificate))));
    assertEquals(Map.of("firstname", "Бат", "regnum", FakeXyp.REGNUM), data);
  }

  @Test
  void insecureSkipVerifyAcceptsAnyCertificateAndHost() {
    assertEquals(
        Map.of("firstname", "Бат", "regnum", FakeXyp.REGNUM),
        call(client(localhost()).insecureSkipVerify(true)));
  }

  @Test
  void bundledCertificatesAreExactlyTheTwoNationalCas() {
    List<X509Certificate> certificates = XypTls.bundledCertificates();

    assertEquals(2, certificates.size());
    assertTrue(
        certificates
            .get(0)
            .getSubjectX500Principal()
            .getName()
            .contains("CN=Mongolian National Root CA"));
    assertTrue(
        certificates
            .get(1)
            .getSubjectX500Principal()
            .getName()
            .contains("CN=Mongolian National Issuing CA"));
    assertEquals(ROOT_FINGERPRINT, fingerprint(certificates.get(0)));
    assertEquals(ISSUING_FINGERPRINT, fingerprint(certificates.get(1)));
  }

  @Test
  void bundledCertificatesAreAFreshList() {
    // Callers add their own proxy CA to the list; that must not reach anyone else.
    List<X509Certificate> first = XypTls.bundledCertificates();
    first.add(serverCertificate);
    assertEquals(2, XypTls.bundledCertificates().size());
  }

  @Test
  void theIssuingCaChainsToTheRoot() throws GeneralSecurityException {
    List<X509Certificate> certificates = XypTls.bundledCertificates();
    X509Certificate root = certificates.get(0);
    X509Certificate issuing = certificates.get(1);
    root.verify(root.getPublicKey());
    issuing.verify(root.getPublicKey());
    assertEquals(root.getSubjectX500Principal(), issuing.getIssuerX500Principal());
  }

  @Test
  void theFingerprintsMatchTheDocumentation() throws IOException {
    Path docs = REPOSITORY.resolve("docs").resolve("tls.md");
    // A downloaded source jar holds only this package's own files.
    assumeTrue(Files.exists(docs), docs + " is not part of this checkout");
    String text = Files.readString(docs);
    for (String fingerprint : List.of(ROOT_FINGERPRINT, ISSUING_FINGERPRINT)) {
      String withColons = fingerprint.replaceAll("(..)(?!$)", "$1:");
      assertTrue(text.contains(withColons), "docs/tls.md does not publish " + withColons);
    }
  }

  @Test
  void theBundledFilesAreTheSameAsTheOtherSdks() throws IOException {
    for (String name : List.of("MNRCA-2021.pem", "MNICA-2022.pem")) {
      byte[] bundled;
      try (InputStream in = XypTls.class.getResourceAsStream("certs/" + name)) {
        bundled = in.readAllBytes();
      }
      for (Path copy :
          List.of(
              REPOSITORY.resolve(Path.of("packages", "go", "xyp", "certs", name)),
              REPOSITORY.resolve(Path.of("packages", "python", "src", "xyp", "certs", name)))) {
        if (Files.exists(copy)) {
          assertEquals(
              new String(Files.readAllBytes(copy), StandardCharsets.ISO_8859_1),
              new String(bundled, StandardCharsets.ISO_8859_1),
              name + " differs from " + copy);
        }
      }
    }
  }

  @Test
  void anSslContextNeedsAtLeastOneCertificate() {
    XypConfigException error =
        assertThrows(XypConfigException.class, () -> XypTls.sslContext(List.of()));
    assertEquals(Origin.CONFIG, error.origin());
  }

  private static String fingerprint(X509Certificate certificate) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
      return HexFormat.of().formatHex(digest).toUpperCase(Locale.ROOT);
    } catch (GeneralSecurityException failure) {
      throw new AssertionError(failure);
    }
  }
}
