package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ValueTypesTest {

  private static final String REGNUM = "РД00000000";

  @Test
  void factoriesSetTheMatchingAuthType() {
    assertEquals(AuthType.SMS_OTP, Auth.otp(REGNUM, 1).authType());
    assertEquals(AuthType.SSO_OTP, Auth.ssoOtp(REGNUM, 1).authType());
    assertEquals(AuthType.DIGITAL_SIGNATURE, Auth.signature(REGNUM, "s", "c").authType());
    assertEquals(AuthType.FINGERPRINT, Auth.fingerprint(REGNUM, new byte[] {1}).authType());
    assertEquals(AuthType.DAN_APP, Auth.danApp(REGNUM).authType());
    assertEquals(
        List.of(1, 2, 3, 4, 5), List.of(AuthType.values()).stream().map(AuthType::code).toList());
  }

  @Test
  void authKeepsADefensiveCopyOfTheFingerprint() {
    byte[] image = {1, 2};
    Auth auth = Auth.fingerprint(REGNUM, image);
    image[0] = 9;
    assertArrayEquals(new byte[] {1, 2}, auth.fingerprint());
    auth.fingerprint()[1] = 9;
    assertArrayEquals(new byte[] {1, 2}, auth.fingerprint());
    assertEquals(auth, Auth.fingerprint(REGNUM, new byte[] {1, 2}));
    assertEquals(auth.hashCode(), Auth.fingerprint(REGNUM, new byte[] {1, 2}).hashCode());
    assertNotEquals(auth, Auth.fingerprint(REGNUM, new byte[] {1, 3}));
  }

  @Test
  void printingApprovalsRevealsNoCitizenDataOrSecrets() {
    Auth everything =
        Auth.builder()
            .regnum(REGNUM)
            .civilId("123456789012")
            .otp(987654)
            .signature("signature-secret")
            .certFingerprint("cert-secret")
            .appAuthToken("app-secret")
            .authAppName("app-name")
            .authType(AuthType.SMS_OTP)
            .build();
    CallOptions options =
        CallOptions.builder()
            .citizen(everything)
            .operator(Auth.danApp(REGNUM))
            .endpoint("citizen-1.5.0")
            .build();

    for (String printed : List.of(everything.toString(), options.toString())) {
      for (String secret :
          List.of(
              REGNUM, "123456789012", "987654", "signature-secret", "cert-secret", "app-secret")) {
        assertFalse(printed.contains(secret), () -> printed + " reveals " + secret);
      }
    }
    assertEquals("Auth[authType=SMS_OTP]", everything.toString());
    assertEquals("Auth[authType=unset]", Auth.builder().build().toString());
    assertTrue(options.toString().contains("citizen-1.5.0"), options.toString());
  }

  @Test
  void callOptionsReportWhatIsSet() {
    assertEquals(Optional.empty(), CallOptions.none().citizen());
    assertEquals(Optional.empty(), CallOptions.builder().endpoint("").build().endpoint());
    CallOptions options =
        CallOptions.builder().operator(Auth.danApp(REGNUM)).endpoint("x-1.0.0").build();
    assertEquals(Optional.of(Auth.danApp(REGNUM)), options.operator());
    assertEquals(Optional.of("x-1.0.0"), options.endpoint());
  }

  @Test
  void paramsKeepTheirOrderAndPrintOnlyNames() {
    Params params = Params.builder().add("b", REGNUM).add("a", null).add("b", 2).build();

    assertEquals(List.of("b", "a", "b"), params.entries().stream().map(Map.Entry::getKey).toList());
    assertNull(params.entries().get(1).getValue());
    assertEquals("Params[b, a, b]", params.toString());
    assertEquals(3, params.size());
    assertThrows(UnsupportedOperationException.class, () -> params.entries().clear());
    assertTrue(Params.empty().isEmpty());
    assertEquals(Params.of("a", 1), Params.builder().add("a", 1).build());
    assertThrows(NullPointerException.class, () -> Params.of(null, 1));
  }

  @Test
  void aParamsBuilderCanBeReusedWithoutChangingWhatItBuilt() {
    Params.Builder builder = Params.builder().add("a", 1);
    Params first = builder.build();
    builder.add("b", 2);
    assertEquals(1, first.size());
    assertEquals(2, builder.build().size());
  }

  @Test
  void xypDateFactories() {
    OffsetDateTime moment = OffsetDateTime.of(2024, 1, 31, 12, 0, 0, 0, ZoneOffset.ofHours(8));
    assertEquals(new XypDate(null, moment), XypDate.of(moment));
    assertEquals(ZoneOffset.UTC, XypDate.of(Instant.EPOCH).time().getOffset());
    assertEquals(new XypDate("31.01.2024", null), XypDate.ofRaw("31.01.2024"));
    assertEquals(
        OffsetDateTime.of(2024, 1, 31, 0, 0, 0, 0, ZoneOffset.UTC),
        XypDate.ofRaw("2024-01-31").time());
  }

  @Test
  void everyDocumentedCodeMapsToItsReason() {
    Map<XypApiException.Reason, List<Integer>> codes =
        Map.of(
            XypApiException.Reason.NOT_FOUND, List.of(1),
            XypApiException.Reason.INTERNAL, List.of(2),
            XypApiException.Reason.INVALID_REQUEST, List.of(3),
            XypApiException.Reason.AUTH_REQUIRED, List.of(200, 201, 202),
            XypApiException.Reason.ACCESS_DENIED, List.of(203, 501),
            XypApiException.Reason.FINGERPRINT, List.of(301, 302, 303, 304),
            XypApiException.Reason.CITIZEN_DATA, List.of(401, 402),
            XypApiException.Reason.SIGNATURE, List.of(601, 602, 603, 604, 605),
            XypApiException.Reason.PROVIDER, List.of(801, 802));
    codes.forEach(
        (reason, list) ->
            list.forEach(
                code -> assertEquals(reason, XypApiException.Reason.of(code), "code " + code)));
    for (int undocumented : new int[] {0, 4, 199, 204, 500, 9999, -1}) {
      assertEquals(XypApiException.Reason.OTHER, XypApiException.Reason.of(undocumented));
    }
    XypApiException error = new XypApiException(9999, null, null);
    assertEquals("[9999] ", error.getMessage());
    assertEquals("", error.requestId());
    assertEquals(XypApiException.Reason.OTHER, error.reason());
  }

  @Test
  void originOfFindsTheSdkExceptionInACauseChain() {
    XypConfigException config = new XypConfigException("bad");
    assertEquals(Origin.CONFIG, XypException.originOf(config));
    assertEquals(
        Origin.CONFIG, XypException.originOf(new IllegalStateException("wrapped", config)));
    assertEquals(
        Origin.NETWORK,
        XypException.originOf(
            new XypConnectionException(
                "down", new UncheckedIOException(new IOException("refused")), false)));
    assertEquals(Origin.XYP, XypException.originOf(new XypResponseException("fault", 500)));
    assertEquals(Origin.SDK, XypException.originOf(new IllegalStateException()));
    assertEquals(Origin.SDK, XypException.originOf(null));

    RuntimeException first = new RuntimeException();
    RuntimeException second = new RuntimeException(first);
    first.initCause(second);
    assertEquals(Origin.SDK, XypException.originOf(first), "a cyclic chain still ends");
  }
}
