package io.github.baljinnyamday.xyp;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;

/**
 * Sends requests to XYP over one {@link HttpClient} and turns every transport failure into an
 * {@link XypConnectionException}. The client is this SDK's own: the JVM's defaults are never
 * changed.
 */
final class Transport {

  private static final String SOAP_CONTENT_TYPE = "text/xml; charset=utf-8";

  /** Only TLS 1.2 and 1.3; older versions are broken and XYP does not need them. */
  private static final String[] TLS_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

  private final HttpClient http;
  private final Duration timeout;
  private final long timeoutNanos;

  Transport(SSLContext sslContext, ProxySelector proxy, Duration timeout) {
    SSLParameters parameters = new SSLParameters();
    parameters.setProtocols(TLS_PROTOCOLS.clone());
    HttpClient.Builder builder =
        HttpClient.newBuilder()
            // SOAP over HTTP/1.1 is what XYP serves; an h2c upgrade attempt only adds a round trip.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .sslContext(sslContext)
            .sslParameters(parameters)
            .followRedirects(HttpClient.Redirect.NEVER);
    if (proxy != null) {
      builder.proxy(proxy);
    }
    this.http = builder.build();
    this.timeout = timeout;
    this.timeoutNanos = saturatedNanos(timeout);
  }

  /** The status and body of one exchange. */
  record Reply(int status, byte[] body) {}

  /** POSTs a SOAP envelope with the credential headers. */
  Reply post(URI address, String envelope, Signer.Credentials credentials) {
    HttpRequest request =
        HttpRequest.newBuilder(address)
            .timeout(timeout)
            .header("Content-Type", SOAP_CONTENT_TYPE)
            .header("SOAPAction", "\"\"")
            // The JDK keeps a header name's case as given, and XYP requires this mixed case.
            .header(Signer.ACCESS_TOKEN_HEADER, credentials.accessToken())
            .header(Signer.TIME_STAMP_HEADER, credentials.timeStamp())
            .header(Signer.SIGNATURE_HEADER, credentials.signature())
            .POST(HttpRequest.BodyPublishers.ofString(envelope, StandardCharsets.UTF_8))
            .build();
    return send(request);
  }

  /** GETs a document, such as a WSDL. */
  Reply get(URI address) {
    return send(HttpRequest.newBuilder(address).timeout(timeout).GET().build());
  }

  /**
   * Sends one request. {@link HttpRequest#timeout} stops at the response headers, so the whole
   * exchange, body included, is also bounded here.
   */
  private Reply send(HttpRequest request) {
    CompletableFuture<HttpResponse<byte[]>> exchange =
        http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
    try {
      HttpResponse<byte[]> response = exchange.get(timeoutNanos, TimeUnit.NANOSECONDS);
      return new Reply(response.statusCode(), response.body());
    } catch (TimeoutException timedOut) {
      exchange.cancel(true);
      throw connectionError(timedOut);
    } catch (InterruptedException interrupted) {
      exchange.cancel(true);
      Thread.currentThread().interrupt();
      throw connectionError(interrupted);
    } catch (ExecutionException failed) {
      throw connectionError(failed.getCause() == null ? failed : failed.getCause());
    }
  }

  /**
   * Releases the connections and threads. {@code HttpClient} can do that only from Java 21, where
   * it became {@code AutoCloseable}; on Java 17 they are released once the client is unreachable.
   */
  void close() {
    if (http instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception ignored) {
        // Closing only waits for requests in flight; there is nothing left to report.
      }
    }
  }

  static XypConnectionException connectionError(Throwable cause) {
    return new XypConnectionException(
        "could not reach XYP ("
            + failureCause(cause)
            + "). Check the VPN connection, the hosts entry for xyp.gov.mn and the TLS settings.",
        cause,
        isTimeout(cause));
  }

  private static boolean isTimeout(Throwable cause) {
    return cause instanceof TimeoutException || cause instanceof HttpTimeoutException;
  }

  /**
   * The short reason that goes in the message, in the spirit of the errno text the other SDKs
   * print. The JDK's exception messages name hosts and certificates, never request content.
   */
  private static String failureCause(Throwable cause) {
    if (isTimeout(cause)) {
      return "timeout";
    }
    if (cause instanceof InterruptedException) {
      return "interrupted";
    }
    SSLException tls = find(cause, SSLException.class);
    if (tls != null) {
      return "TLS: " + messageOf(tls);
    }
    if (find(cause, ConnectException.class) != null) {
      String message = deepestMessage(cause);
      return message == null ? "connection refused" : message;
    }
    String message = deepestMessage(cause);
    return message == null ? cause.getClass().getSimpleName() : message;
  }

  private static <T extends Throwable> T find(Throwable cause, Class<T> type) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = cause;
        current != null && seen.add(current);
        current = current.getCause()) {
      if (type.isInstance(current)) {
        return type.cast(current);
      }
    }
    return null;
  }

  private static String deepestMessage(Throwable cause) {
    String message = null;
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = cause;
        current != null && seen.add(current);
        current = current.getCause()) {
      if (current.getMessage() != null && !current.getMessage().isBlank()) {
        message = current.getMessage();
      }
    }
    return message;
  }

  private static String messageOf(IOException failure) {
    String message = deepestMessage(failure);
    return message == null ? failure.getClass().getSimpleName() : message;
  }

  private static long saturatedNanos(Duration duration) {
    try {
      return duration.toNanos();
    } catch (ArithmeticException tooLong) {
      return Long.MAX_VALUE;
    }
  }
}
