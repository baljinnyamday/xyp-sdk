package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.baljinnyamday.xyp.citizen.GetCitizenIDCardInfoParams;
import io.github.baljinnyamday.xyp.citizen.GetCitizenIDCardInfoResponse;
import io.github.baljinnyamday.xyp.citizen.GetCitizenIDCardInfoResponse.ListAddress;
import io.github.baljinnyamday.xyp.governmentservice.TreeRegisterParams;
import io.github.baljinnyamday.xyp.meta.ListAccessResponse;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Calls through the generated group clients, against a local stand-in for XYP. */
class GeneratedCallsTest {

  /** The values the WS100101 envelope fixture was built with. */
  private static final String QUOTED = "Бат-Эрдэнэ <&> \"quoted\"";

  private static final String PRODUCTION_SHAPE = "/responses/ws100101-production-shape.xml";

  private final List<AutoCloseable> resources = new ArrayList<>();
  private final RecordingLogger logger = new RecordingLogger();

  @AfterEach
  void closeResources() throws Exception {
    for (AutoCloseable resource : resources) {
      resource.close();
    }
  }

  private FakeXyp fake(Function<FakeXyp.Recorded, FakeXyp.Reply> answer) throws IOException {
    FakeXyp fake = FakeXyp.start(answer);
    resources.add(fake);
    return fake;
  }

  private FakeXyp answering(String body) throws IOException {
    return fake(request -> FakeXyp.Reply.ok(body));
  }

  private XypClient connect(FakeXyp fake) {
    XypClient client =
        XypClient.builder()
            .accessToken("test-access-token")
            .privateKey(TestKeys.RSA.getPrivate())
            .baseUrl(fake.baseUrl())
            .logger(logger)
            .env(name -> null)
            .build();
    resources.add(client);
    return client;
  }

  private static String withListAddress(String response, String items) {
    String open = "xsi:type=\"ns2:citizenData\">";
    assertTrue(response.contains(open), "the fixture changed shape");
    return response.replace(open, open + items);
  }

  @Test
  void getCitizenIDCardInfoSendsTheZeepVerifiedEnvelopeAndDecodesTheProductionShape()
      throws IOException {
    FakeXyp fake = answering(Fixtures.resource(PRODUCTION_SHAPE));
    XypClient xyp = connect(fake);

    GetCitizenIDCardInfoResponse card =
        xyp.citizen()
            .getCitizenIDCardInfo(
                GetCitizenIDCardInfoParams.builder().regnum(QUOTED).civilId(QUOTED).build(),
                CallOptions.builder()
                    .citizen(Auth.otp("РД00000000", 123456))
                    .operator(Auth.fingerprint("ОП11111111", new byte[] {0, 1, 's', 'c', 'a', 'n'}))
                    .build());

    FakeXyp.Recorded request = fake.recorded().get(0);
    assertEquals(1, fake.recorded().size(), "citizen-1.5.0's namespace is known, no WSDL read");
    assertEquals("POST", request.method());
    assertEquals("/citizen-1.5.0/ws", request.uri());
    assertEquals(
        Fixtures.envelope("WS100101_getCitizenIDCardInfo").get("envelope").asText(),
        request.body());

    assertEquals("Тест", card.firstname());
    assertEquals("Тестийн", card.lastname());
    assertEquals("Тест", card.surname());
    assertEquals("ТЕ00000000", card.regnum());
    assertEquals("000000000000", card.civilId());
    assertEquals("Эрэгтэй", card.gender());
    assertEquals("1-р хороо, Тест гудамж, 0 тоот", card.addressDetail());
    assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, card.image());
    // A Date field keeps XYP's text and, when it is ISO 8601, the moment; no zone reads as UTC.
    assertEquals("1990-01-01 00:00:00.0", card.birthDate().raw());
    assertEquals(
        OffsetDateTime.of(1990, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC), card.birthDate().time());
    // The catalog types these as String, so they stay text.
    assertEquals("2030-01-01 00:00:00.0", card.passportExpireDate());
    // Sent empty, or not sent at all: null either way. A list is empty instead.
    assertNull(card.addressStreetName());
    assertNull(card.passportNum());
    assertNull(card.passportDate());
    assertEquals(List.of(), card.listAddress());

    assertEquals(List.of(), card.extras().mismatches());
    Map<?, ?> raw = (Map<?, ?>) card.extras().raw();
    assertEquals("Тест", raw.get("firstname"));
    assertFalse(card.extras().toString().contains("Тест"), "extras never print the response");
    assertEquals(List.of(), logger.entries());
  }

  @Test
  void aListOfObjectsIsDecodedInOrder() throws IOException {
    String items =
        "<listAddress><aimagCityName>Улаанбаатар</aimagCityName>"
            + "<addressDoor>12</addressDoor>"
            + "<registerDate>2015-03-04T09:30:00+0800</registerDate></listAddress>"
            + "<listAddress><aimagCityName>Дархан-Уул</aimagCityName>"
            + "<registerDate>2001.02.03</registerDate></listAddress>";
    XypClient xyp = connect(answering(withListAddress(Fixtures.resource(PRODUCTION_SHAPE), items)));

    GetCitizenIDCardInfoResponse card =
        xyp.citizen()
            .getCitizenIDCardInfo(
                GetCitizenIDCardInfoParams.builder().regnum("РД00000000").build());

    assertEquals(2, card.listAddress().size());
    ListAddress first = card.listAddress().get(0);
    assertEquals("Улаанбаатар", first.aimagCityName());
    assertEquals("12", first.addressDoor());
    assertNull(first.addressStreetName());
    assertEquals(
        OffsetDateTime.of(2015, 3, 4, 9, 30, 0, 0, ZoneOffset.ofHours(8)),
        first.registerDate().time());
    ListAddress second = card.listAddress().get(1);
    assertEquals("Дархан-Уул", second.aimagCityName());
    // Not ISO 8601: kept as text, never rejected.
    assertEquals("2001.02.03", second.registerDate().raw());
    assertNull(second.registerDate().time());
    assertThrows(UnsupportedOperationException.class, () -> card.listAddress().clear());
    assertEquals(List.of(), card.extras().mismatches());
  }

  @Test
  void aSingleListItemIsStillAList() throws IOException {
    String item = "<listAddress><aimagCityName>Улаанбаатар</aimagCityName></listAddress>";
    XypClient xyp = connect(answering(withListAddress(Fixtures.resource(PRODUCTION_SHAPE), item)));

    GetCitizenIDCardInfoResponse card =
        xyp.citizen()
            .getCitizenIDCardInfo(
                GetCitizenIDCardInfoParams.builder().regnum("РД00000000").build());

    assertEquals(1, card.listAddress().size());
    assertEquals("Улаанбаатар", card.listAddress().get(0).aimagCityName());
  }

  @Test
  void aServiceWithoutInputsSendsAnEmptyRequest() throws IOException {
    String access =
        "<id>7</id><orgTitle>Тест байгууллага</orgTitle><registered>true</registered>"
            + "<accessToken>never printed</accessToken>"
            + "<approvedServices><ws>WS100101_getCitizenIDCardInfo</ws></approvedServices>"
            + "<approvedServices><ws>WS100103_citizenSalaryInfo</ws></approvedServices>"
            + "<expireDate>2027-01-01</expireDate>";
    FakeXyp fake = answering(FakeXyp.soapResponse(access, 0, "амжилттай"));
    XypClient xyp = connect(fake);

    ListAccessResponse response = xyp.meta().listAccess();

    FakeXyp.Recorded request = fake.recorded().get(0);
    assertEquals("/meta-1.5.0/ws", request.uri());
    assertEquals(
        Envelope.build("WS100001_listAccess", "http://meta.xyp.gov.mn/", null, null, null),
        request.body());
    assertTrue(request.body().contains("<tns:WS100001_listAccess><request"), request.body());
    assertFalse(request.body().contains("<auth>"), "no approvals were given");

    assertEquals(7L, response.id());
    assertEquals("Тест байгууллага", response.orgTitle());
    assertEquals(Boolean.TRUE, response.registered());
    assertEquals(2, response.approvedServices().size());
    assertEquals(Map.of("ws", "WS100101_getCitizenIDCardInfo"), response.approvedServices().get(0));
    assertEquals("2027-01-01", response.expireDate().raw());
    assertNull(response.orgId());
    assertEquals(List.of(), response.extras().mismatches());
  }

  @Test
  void aBadIntIsAMismatchANullFieldAndOneWarning() throws IOException {
    String access = "<id>12x</id><orgId>34.0</orgId><orgTitle>Тест байгууллага</orgTitle>";
    XypClient xyp = connect(answering(FakeXyp.soapResponse(access, 0, "ok")));

    ListAccessResponse response = xyp.meta().listAccess(CallOptions.none());

    assertNull(response.id());
    assertEquals(34L, response.orgId(), "an integer written by a spreadsheet still fits");
    assertEquals("Тест байгууллага", response.orgTitle(), "the rest of the response is intact");
    assertEquals(1, response.extras().mismatches().size());
    Mismatch mismatch = response.extras().mismatches().get(0);
    assertEquals("id", mismatch.path());
    assertEquals("not a valid int", mismatch.problem());
    assertEquals("12x", mismatch.value());
    assertEquals("id (not a valid int)", mismatch.toString());

    assertEquals(1, logger.entries().size());
    RecordingLogger.Entry warning = logger.entries().get(0);
    assertEquals(Level.WARNING, warning.level());
    assertTrue(warning.message().contains("operation=WS100001_listAccess"), warning.message());
    assertTrue(warning.message().contains("id (not a valid int)"), warning.message());
    assertFalse(warning.message().contains("12x"), "values never reach the log");
  }

  @Test
  void aServiceWithoutOutputsReturnsTheRawTree() throws IOException {
    FakeXyp fake =
        fake(
            request ->
                request.method().equals("GET")
                    ? FakeXyp.Reply.ok(
                        "<definitions targetNamespace=\"http://government-service.xyp.gov.mn/\"/>")
                    : FakeXyp.Reply.ok(
                        FakeXyp.soapResponse("<status>saved</status><id>9</id>", 0, "ok")));
    XypClient xyp = connect(fake);

    Object result =
        xyp.governmentService()
            .treeRegister(
                TreeRegisterParams.builder()
                    .confirmed(true)
                    .locationLat(47.918)
                    .regnum("РД00000000")
                    .build());

    assertEquals(Map.of("status", "saved", "id", "9"), result);
    assertEquals(2, fake.recorded().size());
    assertEquals("GET", fake.recorded().get(0).method());
    assertEquals("/government-service-1.5.0/ws?WSDL", fake.recorded().get(0).uri());
    FakeXyp.Recorded post = fake.recorded().get(1);
    assertEquals("/government-service-1.5.0/ws", post.uri());
    assertTrue(
        post.body()
            .contains(
                "xmlns:tns=\"http://government-service.xyp.gov.mn/\"><soap:Body>"
                    + "<tns:GS10052_TreeRegister><request><regnum>РД00000000</regnum>"
                    + "<locationLat>47.918</locationLat><confirmed>1</confirmed></request>"),
        post.body());
  }

  @Test
  void anApiErrorFromAGeneratedMethodSaysWhoseSideItIsOn() throws IOException {
    XypClient xyp = connect(answering(FakeXyp.soapResponse("", 1, "Бүртгэл олдсонгүй")));

    XypApiException error =
        assertThrows(
            XypApiException.class,
            () ->
                xyp.citizen()
                    .getCitizenIDCardInfo(
                        GetCitizenIDCardInfoParams.builder().regnum("РД00000000").build()));

    assertEquals(XypApiException.Reason.NOT_FOUND, error.reason());
    assertEquals(Origin.XYP, error.origin());
    assertEquals("4fd9aa5f-1984-4b61-b379-13c1bcbd29c7", error.requestId());
  }
}
