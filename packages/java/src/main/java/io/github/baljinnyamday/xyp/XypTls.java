package io.github.baljinnyamday.xyp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The trust the SDK puts in XYP's TLS certificate.
 *
 * <p>XYP's certificate is issued by the Mongolian national PKI, which no operating system or JDK
 * trust store ships. Instead of turning verification off (what most integrations do, including the
 * official sample), the SDK trusts exactly the national root and issuing CA, and only for its own
 * requests: nothing here touches the JVM's default {@code SSLContext} or any system property. The
 * two certificates are byte-for-byte the ones published at {@code https://esign.gov.mn/MNRCA.zip}
 * and {@code https://esign.gov.mn/MNICA.zip}; see {@code docs/tls.md} in the repository.
 *
 * <p>To trust a proxy that re-terminates TLS in addition to them:
 *
 * <pre>{@code
 * List<X509Certificate> trusted = XypTls.bundledCertificates();
 * trusted.add(proxyCa);
 * XypClient xyp = XypClient.builder().trustedCertificates(trusted).build();
 * }</pre>
 */
public final class XypTls {

  /** The national root CA, valid until 2031. */
  private static final String ROOT_CA = "certs/MNRCA-2021.pem";

  /** The national issuing CA that signs xyp.gov.mn, valid until 2031. */
  private static final String ISSUING_CA = "certs/MNICA-2022.pem";

  private XypTls() {}

  /**
   * The bundled national CA certificates, MNRCA-2021 and MNICA-2022, and nothing else.
   *
   * @return a new, modifiable list, so adding to it affects no one else
   */
  public static List<X509Certificate> bundledCertificates() {
    List<X509Certificate> certificates = new ArrayList<>(2);
    certificates.add(readBundled(ROOT_CA));
    certificates.add(readBundled(ISSUING_CA));
    return certificates;
  }

  /**
   * An {@code SSLContext} that trusts exactly these certificates as anchors, and nothing from the
   * JVM's default trust store.
   *
   * @param trusted the certificates to trust; must not be empty
   * @return a new context, for {@link XypClient.Builder#sslContext(SSLContext)} or anything else
   * @throws XypConfigException when {@code trusted} is empty or the context cannot be built
   */
  public static SSLContext sslContext(Collection<? extends X509Certificate> trusted) {
    Objects.requireNonNull(trusted, "trusted");
    if (trusted.isEmpty()) {
      throw new XypConfigException("no trusted certificates: nothing could be verified");
    }
    try {
      // An in-memory store, built for this context only.
      KeyStore anchors = KeyStore.getInstance("PKCS12");
      anchors.load(null, null);
      int index = 0;
      for (X509Certificate certificate : trusted) {
        anchors.setCertificateEntry(
            "trusted-" + index++, Objects.requireNonNull(certificate, "certificate"));
      }
      TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
      factory.init(anchors);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, factory.getTrustManagers(), null);
      return context;
    } catch (GeneralSecurityException | IOException failure) {
      throw new XypConfigException(
          "cannot build a TLS context from the trusted certificates", failure);
    }
  }

  /**
   * An {@code SSLContext} that accepts any certificate for any host name. Used only when the caller
   * opts in through {@link XypClient.Builder#insecureSkipVerify(boolean)}.
   */
  static SSLContext trustAllContext() {
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {new TrustAll()}, null);
      return context;
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("the JDK has no TLS implementation", failure);
    }
  }

  private static X509Certificate readBundled(String name) {
    try (InputStream in = XypTls.class.getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException("the SDK jar is missing " + name);
      }
      return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    } catch (IOException failure) {
      throw new UncheckedIOException("cannot read the bundled " + name, failure);
    } catch (CertificateException failure) {
      throw new IllegalStateException("the bundled " + name + " is not a certificate", failure);
    }
  }

  /**
   * Trusts everything. It extends {@code X509ExtendedTrustManager} so the JDK does not wrap it in
   * its own host name check: {@code insecureSkipVerify} turns off both, like Go's option.
   */
  private static final class TrustAll extends X509ExtendedTrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

    @Override
    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }
}
