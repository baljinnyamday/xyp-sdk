package io.github.baljinnyamday.xyp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javax.net.ssl.SSLContext;

/** A local stand-in for xyp.gov.mn; {@code answer} decides what each request gets. */
final class FakeXyp implements AutoCloseable {

  static final String REGNUM = "РД00000000";
  static final String ID_CARD = "<firstname>Бат</firstname><regnum>" + REGNUM + "</regnum>";
  static final String XSI = "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"";

  /** One request as the server saw it. Header names are as com.sun.net.httpserver keys them. */
  record Recorded(String method, String uri, Map<String, List<String>> headers, String body) {

    String header(String name) {
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
          return entry.getValue().get(0);
        }
      }
      return null;
    }
  }

  /** What the server answers with. */
  record Reply(int status, String body, Duration delay) {

    static Reply ok(String body) {
      return new Reply(200, body, Duration.ZERO);
    }

    static Reply status(int status, String body) {
      return new Reply(status, body, Duration.ZERO);
    }

    Reply delayed(Duration delay) {
      return new Reply(status, body, delay);
    }
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final List<Recorded> requests = new ArrayList<>();

  private FakeXyp(HttpServer server, Function<Recorded, Reply> answer) {
    this.server = server;
    server.createContext("/", exchange -> handle(exchange, answer));
    server.setExecutor(executor);
    server.start();
  }

  static FakeXyp start(Function<Recorded, Reply> answer) throws IOException {
    return new FakeXyp(HttpServer.create(loopback(), 0), answer);
  }

  static FakeXyp startTls(SSLContext context, Function<Recorded, Reply> answer) throws IOException {
    HttpsServer server = HttpsServer.create(loopback(), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(context));
    return new FakeXyp(server, answer);
  }

  private static InetSocketAddress loopback() {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
  }

  String baseUrl() {
    String scheme = server instanceof HttpsServer ? "https" : "http";
    return scheme + "://127.0.0.1:" + server.getAddress().getPort();
  }

  synchronized List<Recorded> recorded() {
    return List.copyOf(requests);
  }

  private void handle(HttpExchange exchange, Function<Recorded, Reply> answer) throws IOException {
    try {
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      Recorded request =
          new Recorded(
              exchange.getRequestMethod(),
              exchange.getRequestURI().toString(),
              Map.copyOf(exchange.getRequestHeaders()),
              body);
      synchronized (this) {
        requests.add(request);
      }
      Reply reply = answer.apply(request);
      if (!reply.delay().isZero()) {
        try {
          Thread.sleep(reply.delay().toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
      byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/xml; charset=utf-8");
      exchange.sendResponseHeaders(reply.status(), payload.length == 0 ? -1 : payload.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(payload);
      }
    } catch (IOException clientWentAway) {
      // A client that timed out closes the connection; there is nobody left to answer.
    } finally {
      exchange.close();
    }
  }

  /** Shaped like the sample on developer.xyp.gov.mn/docs/result-code. */
  static String soapResponse(String inner, int code, String message) {
    return "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">\n"
        + "  <soap:Body>\n"
        + "    <ns2:WS100101_getCitizenIDCardInfoResponse xmlns:ns2=\"http://citizen.xyp.gov.mn/\">\n"
        + "      <return>\n"
        + "        <request "
        + XSI
        + " xsi:type=\"ns2:citizenRequestData\"/>\n"
        + "        <requestId>4fd9aa5f-1984-4b61-b379-13c1bcbd29c7</requestId>\n"
        + "        <response "
        + XSI
        + " xsi:type=\"ns2:citizenData\">"
        + inner
        + "</response>\n"
        + "        <resultCode>"
        + code
        + "</resultCode>\n"
        + "        <resultMessage>"
        + message
        + "</resultMessage>\n"
        + "      </return>\n"
        + "    </ns2:WS100101_getCitizenIDCardInfoResponse>\n"
        + "  </soap:Body>\n"
        + "</soap:Envelope>";
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
