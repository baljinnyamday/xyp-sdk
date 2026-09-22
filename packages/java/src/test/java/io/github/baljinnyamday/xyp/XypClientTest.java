package io.github.baljinnyamday.xyp;

import static io.github.baljinnyamday.xyp.FakeXyp.ID_CARD;
import static io.github.baljinnyamday.xyp.FakeXyp.REGNUM;
import static io.github.baljinnyamday.xyp.FakeXyp.soapResponse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XypClientTest {

  private static final String TOKEN = "test-access-token";
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);
  private static final String ID_CARD_OPERATION = "WS100101_getCitizenIDCardInfo";

  /** Registered as insurance-1.5.0, whose WSDL is not in the repository. */
  private static final String PENSION_OPERATION = "WS100502_getCitizenPensionInquiry";

  private final List<AutoCloseable> resources = new ArrayList<>();

  @AfterEach
  void closeResources() throws Exception {
    for (AutoCloseable resource : resources) {
      resource.close();
    }
  }

  record IdCard(String firstname, String regnum, Extras extras) {
    static IdCard decode(ResponseReader r) {
      return new IdCard(
          r.get("firstname", Decoders.STRING), r.get("regnum", Decoders.STRING), r.extras());
    }
  }

  /** What a generated *Params class looks like: it knows the schema order. */
  record IdCardParams(String regnum, String civilId) implements RequestParams {
    @Override
    public Params toParams() {
      return Params.builder().add("civilId", civilId).add("regnum", regnum).build();
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

  private XypClient connect(String baseUrl, Duration timeout, System.Logger logger) {
    XypClient client =
        XypClient.builder()
            .accessToken(TOKEN)
            .privateKey(TestKeys.RSA.getPrivate())
            .baseUrl(baseUrl)
            .timeout(timeout)
            .logger(logger)
            .env(name -> null)
            .build();
    resources.add(client);
    return client;
  }

  private XypClient connect(FakeXyp fake) {
    return connect(fake.baseUrl(), TEST_TIMEOUT, new RecordingLogger());
  }

  @Test
  void invokesAServiceEndToEnd() throws IOException {
    FakeXyp fake = answering(soapResponse(ID_CARD, 0, "ok"));
    XypClient client = connect(fake);

    IdCard card =
        client.invoke(
            ID_CARD_OPERATION,
            new IdCardParams(REGNUM, null),
            CallOptions.builder().citizen(Auth.otp(REGNUM, 1234)).build(),
            IdCard::decode);

    assertEquals("Бат", card.firstname());
    assertEquals(List.of(), card.extras().mismatches());
    assertEquals(Map.of("firstname", "Бат", "regnum", REGNUM), card.extras().raw());

    List<FakeXyp.Recorded> requests = fake.recorded();
    assertEquals(1, requests.size());
    FakeXyp.Recorded request = requests.get(0);
    assertEquals("POST", request.method());
    assertEquals("/citizen-1.5.0/ws", request.uri());
    // com.sun.net.httpserver normalises incoming header names, so only the values can be
    // asserted here; sendsTheCredentialHeadersInXypsMixedCase checks the names on the wire.
    assertEquals(TOKEN, request.header("accessToken"));
    assertTrue(request.header("timeStamp").matches("\\d+"), request.header("timeStamp"));
    assertNotNull(request.header("signature"));
    assertEquals("text/xml; charset=utf-8", request.header("Content-Type"));
    assertEquals("\"\"", request.header("SOAPAction"));
    for (String want :
        List.of(
            "xmlns:tns=\"http://citizen.xyp.gov.mn/\"",
            "<tns:WS100101_getCitizenIDCardInfo><request><auth><citizen>",
            "<otp>1234</otp><regnum>" + REGNUM + "</regnum>")) {
      assertTrue(request.body().contains(want), () -> request.body() + " does not contain " + want);
    }
  }

  @Test
  void sendsInputsInSchemaOrderWhateverOrderTheCallerUsed() throws IOException {
    FakeXyp fake = answering(soapResponse(ID_CARD, 0, "ok"));

    connect(fake).call(ID_CARD_OPERATION, new IdCardParams(REGNUM, "1"));

    String body = fake.recorded().get(0).body();
    assertTrue(body.contains("<civilId>1</civilId><regnum>" + REGNUM + "</regnum>"), body);
  }

  @Test
  void callByOriginalNameReturnsRawData() throws IOException {
    XypClient client = connect(answering(soapResponse(ID_CARD, 0, "ok")));

    Object data = client.call(ID_CARD_OPERATION, Params.of("regnum", REGNUM));

    assertEquals(Map.of("firstname", "Бат", "regnum", REGNUM), data);
  }

  @Test
  void anUnknownOperationPointsAtCallOptionsEndpoint() throws IOException {
    FakeXyp fake = answering(soapResponse(ID_CARD, 0, "ok"));

    XypConfigException error =
        assertThrows(
            XypConfigException.class, () -> connect(fake).call("WS999999_doesNotExist", null));

    assertTrue(
        error.getMessage().contains("unknown operation \"WS999999_doesNotExist\""),
        error.getMessage());
    assertTrue(
        error.getMessage().contains("CallOptions.builder().endpoint(\"<name>-<version>\")"),
        error.getMessage());
    assertEquals(List.of(), fake.recorded());
  }

  @Test
  void callAcceptsAnEndpointTheRegistryDoesNotKnow() throws IOException {
    FakeXyp fake =
        fake(
            request ->
                request.method().equals("GET")
                    ? FakeXyp.Reply.ok("<wsdl:definitions targetNamespace=\"http://brand.new/\">")
                    : FakeXyp.Reply.ok(soapResponse(ID_CARD, 0, "ok")));

    connect(fake)
        .call("WS109999_brandNew", null, CallOptions.builder().endpoint("citizen-9.9.9").build());

    List<FakeXyp.Recorded> requests = fake.recorded();
    assertEquals("/citizen-9.9.9/ws?WSDL", requests.get(0).uri());
    assertNull(requests.get(0).header("accessToken"), "a WSDL read carries no credentials");
    assertEquals("/citizen-9.9.9/ws", requests.get(1).uri());
    assertTrue(
        requests.get(1).body().contains("xmlns:tns=\"http://brand.new/\""), requests.get(1).body());
  }

  private FakeXyp pensionFake() throws IOException {
    return fake(
        request ->
            request.method().equals("GET")
                ? FakeXyp.Reply.ok(
                    "<wsdl:definitions targetNamespace=\"http://insurance.example/\">")
                : FakeXyp.Reply.ok(soapResponse("<isPensioner>true</isPensioner>", 0, "ok")));
  }

  private static List<String> wsdlReads(FakeXyp fake) {
    return fake.recorded().stream()
        .filter(request -> request.method().equals("GET"))
        .map(FakeXyp.Recorded::uri)
        .collect(Collectors.toList());
  }

  @Test
  void readsAnUnverifiedNamespaceFromTheWsdlOnce() throws IOException {
    FakeXyp fake = pensionFake();
    XypClient client = connect(fake);
    Params params = Params.of("regnum", REGNUM);

    Boolean first =
        client.invoke(
            PENSION_OPERATION,
            params,
            CallOptions.none(),
            r -> r.get("isPensioner", Decoders.BOOL));
    client.call(PENSION_OPERATION, params);

    assertEquals(Boolean.TRUE, first);
    assertEquals(List.of("/insurance-1.5.0/ws?WSDL"), wsdlReads(fake));
    List<FakeXyp.Recorded> requests = fake.recorded();
    String last = requests.get(requests.size() - 1).body();
    assertTrue(
        last.contains("xmlns:tns=\"http://insurance.example/\""),
        "the learned namespace was not reused");
  }

  @Test
  void theNamespaceCacheIsSafeForConcurrentUse() throws Exception {
    FakeXyp fake = pensionFake();
    XypClient client = connect(fake);
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      List<Future<Object>> calls = new ArrayList<>();
      for (int index = 0; index < 16; index++) {
        calls.add(pool.submit(() -> client.call(PENSION_OPERATION, Params.of("regnum", REGNUM))));
      }
      for (Future<Object> call : calls) {
        assertEquals(Map.of("isPensioner", "true"), call.get(10, TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdownNow();
    }
    assertTrue(wsdlReads(fake).size() <= 16);
  }

  @Test
  void aWsdlThatCannotBeReadIsAResponseError() throws IOException {
    FakeXyp fake = fake(request -> FakeXyp.Reply.status(404, "<html>not here</html>"));

    XypResponseException error =
        assertThrows(XypResponseException.class, () -> connect(fake).call(PENSION_OPERATION, null));

    assertEquals("could not read the WSDL of endpoint \"insurance-1.5.0\"", error.getMessage());
    assertEquals(404, error.statusCode());
    assertEquals(1, fake.recorded().size(), "nothing is posted without a namespace");
  }

  @Test
  void paramsAreCheckedBeforeAnythingIsSent() throws IOException {
    FakeXyp fake = pensionFake();

    assertThrows(
        XypConfigException.class,
        () -> connect(fake).call(PENSION_OPERATION, Params.of("bad name", "x")));

    assertEquals(
        List.of(), fake.recorded(), "not even the WSDL is read for a request that cannot be sent");
  }

  @Test
  void resultCodesBecomeErrorsThatSayWhoseSideTheyAreOn() throws IOException {
    XypClient client = connect(answering(soapResponse("", 1, "олдсонгүй")));

    XypApiException error =
        assertThrows(
            XypApiException.class,
            () -> client.invoke(ID_CARD_OPERATION, null, CallOptions.none(), IdCard::decode));

    assertEquals(1, error.resultCode());
    assertEquals("олдсонгүй", error.resultMessage());
    assertEquals("4fd9aa5f-1984-4b61-b379-13c1bcbd29c7", error.requestId());
    assertEquals("[1] олдсонгүй", error.getMessage());
    assertEquals(XypApiException.Reason.NOT_FOUND, error.reason());
    assertEquals(Origin.XYP, XypException.originOf(error));
  }

  @Test
  void unreachableXypIsAConnectionError() {
    // Port 1 refuses connections on every platform CI runs on.
    XypClient client = connect("http://127.0.0.1:1", TEST_TIMEOUT, new RecordingLogger());

    XypConnectionException error =
        assertThrows(XypConnectionException.class, () -> client.call(ID_CARD_OPERATION, null));

    assertEquals(Origin.NETWORK, XypException.originOf(error));
    assertTrue(error.getMessage().contains("VPN"), error.getMessage());
    assertFalse(error.isTimeout(), "a refused connection is not a timeout");
    assertNotNull(error.getCause());
  }

  @Test
  void aTimeoutIsAConnectionErrorThatSaysSo() throws IOException {
    FakeXyp fake =
        fake(
            request ->
                FakeXyp.Reply.ok(soapResponse(ID_CARD, 0, "ok")).delayed(Duration.ofMillis(500)));
    XypClient client = connect(fake.baseUrl(), Duration.ofMillis(50), new RecordingLogger());

    XypConnectionException error =
        assertThrows(XypConnectionException.class, () -> client.call(ID_CARD_OPERATION, null));

    assertTrue(error.isTimeout());
    assertTrue(error.getMessage().startsWith("could not reach XYP (timeout)"), error.getMessage());
    assertNotNull(error.getCause(), "the cause must stay reachable");
  }

  @Test
  void anInterruptedCallKeepsTheInterruptAndItsCause() throws IOException {
    FakeXyp fake =
        fake(
            request ->
                FakeXyp.Reply.ok(soapResponse(ID_CARD, 0, "ok")).delayed(Duration.ofMillis(500)));
    XypClient client = connect(fake);
    Thread caller = Thread.currentThread();
    CompletableFuture<Void> interrupter =
        CompletableFuture.runAsync(
            () -> {
              try {
                Thread.sleep(50);
              } catch (InterruptedException ignored) {
                return;
              }
              caller.interrupt();
            });

    XypConnectionException error =
        assertThrows(XypConnectionException.class, () -> client.call(ID_CARD_OPERATION, null));

    interrupter.join();
    assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
    assertInstanceOf(InterruptedException.class, error.getCause());
    assertFalse(error.isTimeout(), "an interruption is not a timeout");
  }

  @Test
  void aGatewayErrorPageIsReportedWithItsStatus() throws IOException {
    FakeXyp fake = fake(request -> FakeXyp.Reply.status(502, "<html>Bad Gateway</html>"));

    XypResponseException error =
        assertThrows(XypResponseException.class, () -> connect(fake).call(ID_CARD_OPERATION, null));

    assertEquals(502, error.statusCode());
    assertEquals(
        "XYP answered with HTTP 502: XYP response has no <return> element", error.getMessage());
    assertEquals(Origin.XYP, XypException.originOf(error));
  }

  @Test
  void aSoapFaultWithHttp500KeepsItsText() throws IOException {
    String fault =
        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>"
            + "<soap:Fault><faultcode>soap:Client</faultcode><faultstring>Unmarshalling Error: "
            + "unexpected element</faultstring></soap:Fault></soap:Body></soap:Envelope>";
    FakeXyp fake = fake(request -> FakeXyp.Reply.status(500, fault));

    XypResponseException error =
        assertThrows(XypResponseException.class, () -> connect(fake).call(ID_CARD_OPERATION, null));

    assertEquals(500, error.statusCode());
    assertTrue(error.getMessage().contains("HTTP 500"), error.getMessage());
    assertTrue(
        error.getMessage().contains("Unmarshalling Error: unexpected element"), error.getMessage());
  }

  @Test
  void sendsTheCredentialHeadersInXypsMixedCase() throws Exception {
    byte[] reply = soapResponse(ID_CARD, 0, "ok").getBytes(StandardCharsets.UTF_8);
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      CompletableFuture<String> head =
          CompletableFuture.supplyAsync(() -> serveOnce(server, reply));
      XypClient client =
          XypClient.builder()
              .accessToken(TOKEN)
              .privateKey(TestKeys.RSA.getPrivate())
              .baseUrl("http://127.0.0.1:" + server.getLocalPort())
              .clock(Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC))
              .env(name -> null)
              .build();
      resources.add(client);

      client.call(ID_CARD_OPERATION, null);

      List<String> lines = List.of(head.get(10, TimeUnit.SECONDS).split("\r\n"));
      assertEquals("POST /citizen-1.5.0/ws HTTP/1.1", lines.get(0));
      assertTrue(lines.contains("accessToken: " + TOKEN), () -> String.join("\n", lines));
      assertTrue(lines.contains("timeStamp: 1700000000"), () -> String.join("\n", lines));
      assertTrue(
          lines.stream().anyMatch(line -> line.startsWith("signature: ")),
          () -> String.join("\n", lines));
      assertTrue(lines.contains("SOAPAction: \"\""), () -> String.join("\n", lines));
      assertTrue(
          lines.contains("Content-Type: text/xml; charset=utf-8"), () -> String.join("\n", lines));
    }
  }

  /** Reads one request's head and body from a raw socket, answers, and returns the head. */
  private static String serveOnce(ServerSocket server, byte[] reply) {
    try (Socket socket = server.accept()) {
      InputStream in = socket.getInputStream();
      ByteArrayOutputStream head = new ByteArrayOutputStream();
      while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
        int next = in.read();
        if (next < 0) {
          throw new IOException("the client closed the connection mid-request");
        }
        head.write(next);
      }
      String text = head.toString(StandardCharsets.UTF_8);
      Matcher length = Pattern.compile("(?i)\r\ncontent-length: *(\\d+)").matcher(text);
      if (length.find()) {
        in.readNBytes(Integer.parseInt(length.group(1)));
      }
      OutputStream out = socket.getOutputStream();
      out.write(
          ("HTTP/1.1 200 OK\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: "
                  + reply.length
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.write(reply);
      out.flush();
      return text;
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @Test
  void logsOneWarningWithPathsButNeverValues() throws IOException {
    FakeXyp fake =
        answering(
            soapResponse(
                "<age>" + REGNUM + "</age><photo>N/A</photo><firstname>Бат</firstname>", 0, "ok"));
    RecordingLogger logger = new RecordingLogger();
    XypClient logging = connect(fake.baseUrl(), TEST_TIMEOUT, logger);

    record Sample(Long age, byte[] photo, String firstname, Extras extras) {}
    Sample sample =
        logging.invoke(
            ID_CARD_OPERATION,
            null,
            CallOptions.none(),
            r ->
                new Sample(
                    r.get("age", Decoders.INT),
                    r.get("photo", Decoders.BYTES),
                    r.get("firstname", Decoders.STRING),
                    r.extras()));

    assertEquals("Бат", sample.firstname());
    assertEquals(2, sample.extras().mismatches().size());
    List<RecordingLogger.Entry> entries = logger.entries();
    assertEquals(1, entries.size(), "exactly one warning");
    assertEquals(System.Logger.Level.WARNING, entries.get(0).level());
    String output = entries.get(0).message();
    for (String want :
        List.of(
            ID_CARD_OPERATION,
            "age (not a valid int)",
            "photo (not a valid bytes)",
            "gap in the SDK's models",
            "XYP's response",
            XypClient.ISSUES_URL)) {
      assertTrue(output.contains(want), () -> output + " does not mention " + want);
    }
    assertFalse(output.contains(REGNUM), "citizen data reached the log");
    assertFalse(output.contains("N/A"), "citizen data reached the log");
  }

  @Test
  void aResponseThatFitsLogsNothing() throws IOException {
    RecordingLogger logger = new RecordingLogger();
    XypClient client =
        connect(answering(soapResponse(ID_CARD, 0, "ok")).baseUrl(), TEST_TIMEOUT, logger);

    client.invoke(ID_CARD_OPERATION, null, CallOptions.none(), IdCard::decode);

    assertEquals(List.of(), logger.entries());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"../admin", "citizen-1.5.0/ws?x=", "a b", "..", ".hidden", "citizen-1.5.0#x"})
  void rejectsAnEndpointThatIsNotAPathSegment(String endpoint) throws IOException {
    FakeXyp fake = answering(soapResponse("", 0, "ok"));

    XypConfigException error =
        assertThrows(
            XypConfigException.class,
            () ->
                connect(fake)
                    .call(
                        ID_CARD_OPERATION, null, CallOptions.builder().endpoint(endpoint).build()));

    assertTrue(
        error.getMessage().contains("is not a name like \"citizen-1.5.0\""), error.getMessage());
    assertEquals(List.of(), fake.recorded());
  }

  @Test
  void aTrailingSlashAndAnUpperCaseSchemeAreFine() throws IOException {
    FakeXyp fake = answering(soapResponse(ID_CARD, 0, "ok"));
    String base = fake.baseUrl().replace("http://", "HTTP://") + "//";

    connect(base, TEST_TIMEOUT, new RecordingLogger()).call(ID_CARD_OPERATION, null);

    assertEquals("/citizen-1.5.0/ws", fake.recorded().get(0).uri());
  }

  @ParameterizedTest
  @ValueSource(strings = {"xyp.gov.mn", "ftp://xyp.gov.mn", "https://", "https://xyp gov mn"})
  void rejectsABaseUrlThatIsNotHttp(String baseUrl) {
    XypConfigException error =
        assertThrows(
            XypConfigException.class, () -> connect(baseUrl, TEST_TIMEOUT, new RecordingLogger()));
    assertTrue(error.getMessage().startsWith("baseUrl "), error.getMessage());
  }

  @Test
  void readsCredentialsFromTheEnvironment(@TempDir Path directory) throws IOException {
    XypConfigException noToken =
        assertThrows(
            XypConfigException.class,
            () ->
                XypClient.builder()
                    .privateKey(TestKeys.RSA.getPrivate())
                    .env(name -> null)
                    .build());
    assertTrue(noToken.getMessage().contains(XypClient.ACCESS_TOKEN_ENV), noToken.getMessage());

    XypConfigException noKey =
        assertThrows(
            XypConfigException.class,
            () -> XypClient.builder().accessToken(TOKEN).env(name -> "").build());
    assertTrue(noKey.getMessage().contains(XypClient.PRIVATE_KEY_ENV), noKey.getMessage());

    Path keyFile = directory.resolve("private.key");
    Files.writeString(
        keyFile,
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(TestKeys.RSA.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n");
    Map<String, String> env =
        Map.of(XypClient.ACCESS_TOKEN_ENV, TOKEN, XypClient.PRIVATE_KEY_ENV, keyFile.toString());
    assertDoesNotThrow(() -> XypClient.builder().env(env::get).build().close());

    Map<String, String> missing =
        Map.of(
            XypClient.ACCESS_TOKEN_ENV,
            TOKEN,
            XypClient.PRIVATE_KEY_ENV,
            directory.resolve("absent").toString());
    XypConfigException absent =
        assertThrows(XypConfigException.class, () -> XypClient.builder().env(missing::get).build());
    assertEquals("cannot read the private key file (no such file)", absent.getMessage());
  }

  @Test
  void anExplicitCredentialWinsOverTheEnvironment() throws IOException {
    FakeXyp fake = answering(soapResponse(ID_CARD, 0, "ok"));
    XypClient client =
        XypClient.builder()
            .accessToken(TOKEN)
            .privateKey(TestKeys.RSA.getPrivate())
            .baseUrl(fake.baseUrl())
            .env(
                Map.of(
                        XypClient.ACCESS_TOKEN_ENV,
                        "from-env",
                        XypClient.PRIVATE_KEY_ENV,
                        "/nonexistent")
                    ::get)
            .build();
    resources.add(client);

    client.call(ID_CARD_OPERATION, null);

    assertEquals(TOKEN, fake.recorded().get(0).header("accessToken"));
  }

  @Test
  void rejectsAKeyThatIsNotRsa() {
    XypConfigException error =
        assertThrows(
            XypConfigException.class,
            () ->
                XypClient.builder()
                    .accessToken(TOKEN)
                    .privateKey(TestKeys.generate("EC", 256).getPrivate())
                    .env(name -> null)
                    .build());
    assertEquals("privateKey must be an RSA key", error.getMessage());
  }

  @Test
  void insecureSkipVerifyCannotBeCombinedWithAnotherTrust() {
    XypClient.Builder builder =
        XypClient.builder()
            .accessToken(TOKEN)
            .privateKey(TestKeys.RSA.getPrivate())
            .insecureSkipVerify(true)
            .trustedCertificates(XypTls.bundledCertificates())
            .env(name -> null);
    XypConfigException error = assertThrows(XypConfigException.class, builder::build);
    assertTrue(error.getMessage().contains("insecureSkipVerify"), error.getMessage());
  }

  @Test
  void printingTheClientNeverRevealsCredentials() throws IOException {
    FakeXyp fake = answering(soapResponse(ID_CARD, 0, "ok"));
    XypClient client = connect(fake);

    assertEquals("XypClient[baseUrl=" + fake.baseUrl() + "]", client.toString());
    assertFalse(client.toString().contains(TOKEN));
  }

  @Test
  void aClosedClientRefusesCalls() throws IOException {
    XypClient client = connect(answering(soapResponse(ID_CARD, 0, "ok")));
    client.call(ID_CARD_OPERATION, null);

    client.close();
    client.close();

    XypConfigException error =
        assertThrows(XypConfigException.class, () -> client.call(ID_CARD_OPERATION, null));
    assertEquals("this XypClient is closed", error.getMessage());
  }

  @Test
  void nullArgumentsAreProgrammingErrors() throws IOException {
    XypClient client = connect(answering(soapResponse(ID_CARD, 0, "ok")));
    assertThrows(NullPointerException.class, () -> client.call(null, null));
    assertThrows(NullPointerException.class, () -> client.call(ID_CARD_OPERATION, null, null));
    assertThrows(
        NullPointerException.class,
        () ->
            client.invoke(
                ID_CARD_OPERATION, null, CallOptions.none(), (ResponseDecoder<Object>) null));
  }
}
