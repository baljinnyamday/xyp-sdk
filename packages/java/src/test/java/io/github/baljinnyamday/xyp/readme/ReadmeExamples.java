package io.github.baljinnyamday.xyp.readme;

import io.github.baljinnyamday.xyp.Auth;
import io.github.baljinnyamday.xyp.CallOptions;
import io.github.baljinnyamday.xyp.Decoders;
import io.github.baljinnyamday.xyp.Extras;
import io.github.baljinnyamday.xyp.Mismatch;
import io.github.baljinnyamday.xyp.Origin;
import io.github.baljinnyamday.xyp.Params;
import io.github.baljinnyamday.xyp.ResponseReader;
import io.github.baljinnyamday.xyp.XypApiException;
import io.github.baljinnyamday.xyp.XypClient;
import io.github.baljinnyamday.xyp.XypConnectionException;
import io.github.baljinnyamday.xyp.XypDate;
import io.github.baljinnyamday.xyp.XypException;
import io.github.baljinnyamday.xyp.XypKeys;
import io.github.baljinnyamday.xyp.XypTls;
import io.github.baljinnyamday.xyp.citizen.GetCitizenIDCardInfoParams;
import io.github.baljinnyamday.xyp.citizen.GetCitizenIDCardInfoResponse;
import io.github.baljinnyamday.xyp.meta.RegisterOTPRequestParams;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

/**
 * The Java snippets of packages/java/README.md, verbatim, so the build compiles them against the
 * real API. Nothing here runs; {@code ReadmeTest} checks that every snippet in the README is still
 * in this file. It lives outside the SDK's own package so package-private members cannot sneak into
 * a snippet.
 */
final class ReadmeExamples {

  /** The one method of a logging facade the README's error example uses, e.g. SLF4J's. */
  interface Log {
    void warn(String format, Object... arguments);
  }

  private final String token = "access token";
  private final String regnum = "РД00000000";
  private final String operatorRegnum = "ОП11111111";
  private final String signature = "signature";
  private final String certFingerprint = "certificate fingerprint";
  private final byte[] scan = new byte[0];
  private final byte[] pemOrDerBytes = new byte[0];
  private final char[] password = new char[0];
  private final GetCitizenIDCardInfoParams params =
      GetCitizenIDCardInfoParams.builder().regnum(regnum).build();
  private final Log log = (format, arguments) -> {};
  private XypClient xyp;

  private ReadmeExamples() {}

  void quickStart() {
    try (XypClient xyp =
        XypClient.builder()
            .accessToken(token)
            .privateKey(XypKeys.loadPrivateKey(Path.of("private.key")))
            .build()) {
      GetCitizenIDCardInfoResponse card =
          xyp.citizen()
              .getCitizenIDCardInfo(
                  GetCitizenIDCardInfoParams.builder().regnum("РД00000000").build());
      System.out.println(card.firstname() + " " + card.lastname());
    }
  }

  void credentials() {
    PrivateKey fromFile = XypKeys.loadPrivateKey(Path.of("private.key")); // PEM or DER file
    PrivateKey fromSecret = XypKeys.parsePrivateKey(pemOrDerBytes); // from your secret manager
    XypClient fromEnvironment =
        XypClient.builder().build(); // XYP_ACCESS_TOKEN + XYP_PRIVATE_KEY (path)
  }

  void keyStore() throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(Path.of("xyp.p12"))) {
      store.load(in, password);
    }
    PrivateKey key = (PrivateKey) store.getKey("xyp", password);
    XypClient xyp = XypClient.builder().accessToken(token).privateKey(key).build();
  }

  void callByName() {
    Object data =
        xyp.call(
            "WS100101_getCitizenIDCardInfo",
            Params.of("regnum", "РД00000000")); // the response tree

    Object fresh =
        xyp.call(
            "WS109999_brandNew",
            Params.of("regnum", "РД00000000"),
            CallOptions.builder().endpoint("citizen-1.5.0").build());
  }

  void invokeWithYourOwnType() {
    record IdCard(String firstname, XypDate birthDate, Extras extras) {
      static IdCard decode(ResponseReader r) {
        return new IdCard(
            r.get("firstname", Decoders.STRING), r.get("birthDate", Decoders.DATE), r.extras());
      }
    }

    IdCard card =
        xyp.invoke(
            "WS100101_getCitizenIDCardInfo",
            Params.of("regnum", regnum),
            CallOptions.none(),
            IdCard::decode);
  }

  void citizenApproval() {
    // 1. ask XYP to text the citizen a code for the services you are going to call
    xyp.meta()
        .registerOTPRequest(
            RegisterOTPRequestParams.builder()
                .regnum(regnum)
                .jsonWSList("[{\"ws\": \"WS100101_getCitizenIDCardInfo\"}]")
                .isSms(1L)
                .build(),
            CallOptions.builder().citizen(Auth.builder().regnum(regnum).build()).build());

    // 2. call the service with the code they read out to you
    GetCitizenIDCardInfoResponse card =
        xyp.citizen()
            .getCitizenIDCardInfo(
                GetCitizenIDCardInfoParams.builder().regnum(regnum).build(),
                CallOptions.builder().citizen(Auth.otp(regnum, 123456)).build());
  }

  void otherApprovals() {
    Auth sso = Auth.ssoOtp(regnum, 123456);
    Auth signed = Auth.signature(regnum, signature, certFingerprint);
    Auth scanned = Auth.fingerprint(regnum, scan); // the scanner's image bytes
    Auth dan = Auth.danApp(regnum);

    CallOptions options =
        CallOptions.builder()
            .citizen(Auth.otp(regnum, 123456))
            .operator(Auth.fingerprint(operatorRegnum, scan))
            .build();
  }

  GetCitizenIDCardInfoResponse errors() {
    try {
      return xyp.citizen()
          .getCitizenIDCardInfo(GetCitizenIDCardInfoParams.builder().regnum(regnum).build());
    } catch (XypApiException e) {
      switch (e.reason()) {
        case NOT_FOUND -> {
          return null; // resultCode 1: the provider has no record
        }
        case ACCESS_DENIED -> throw new IllegalStateException("ask the NDC for access", e);
        default -> {
          log.warn("XYP said {} (request {})", e.getMessage(), e.requestId());
          throw e;
        }
      }
    } catch (XypConnectionException e) {
      if (e.isTimeout()) {
        throw new IllegalStateException("XYP is slow, try again later", e);
      }
      throw e; // VPN down, hosts entry missing, TLS
    }
  }

  void origin(Throwable failure) {
    Origin origin = XypException.originOf(failure); // CONFIG, NETWORK, XYP or SDK
  }

  void mismatches() {
    GetCitizenIDCardInfoResponse card = xyp.citizen().getCitizenIDCardInfo(params); // succeeds
    String firstname = card.firstname(); // everything that fits is typed as usual
    byte[] photo = card.image(); // null: this field did not fit
    for (Mismatch mismatch : card.extras().mismatches()) {
      System.out.println(mismatch); // "image (not a valid bytes)", never the value
      Object sent = mismatch.value(); // the raw value, when you do want it
    }
    Object raw = card.extras().raw(); // the whole response, undeclared fields included
  }

  void testFixture() {
    Map<String, Object> tree =
        Map.of("regnum", "РД00000000", "firstname", "Бат", "lastname", "Дорж");
    GetCitizenIDCardInfoResponse card =
        ResponseReader.decode(tree, GetCitizenIDCardInfoResponse::decode);
  }

  void tls() throws Exception {
    X509Certificate proxyCa;
    try (InputStream in = Files.newInputStream(Path.of("proxy-ca.pem"))) {
      proxyCa = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
    }
    List<X509Certificate> trusted = XypTls.bundledCertificates();
    trusted.add(proxyCa);

    XypClient xyp =
        XypClient.builder()
            .baseUrl("https://xyp-proxy.example.internal") // only if you go through a proxy
            .trustedCertificates(trusted) // instead of the bundled national CAs
            // .sslContext(context) // or: full control
            // .insecureSkipVerify(true) // or: off — read docs/tls.md first
            .build();
  }
}
