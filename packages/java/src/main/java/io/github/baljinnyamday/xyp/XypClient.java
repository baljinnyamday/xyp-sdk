package io.github.baljinnyamday.xyp;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.ProxySelector;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;

/**
 * Talks to XYP. It signs every request, builds the SOAP envelope, verifies XYP's certificate
 * against the bundled national CAs, and decodes the answer.
 *
 * <pre>{@code
 * try (XypClient xyp = XypClient.builder()
 *     .accessToken(System.getenv("XYP_ACCESS_TOKEN"))
 *     .privateKey(XypKeys.loadPrivateKey(Path.of("private.key")))
 *     .build()) {
 *   GetCitizenIDCardInfoResponse card = xyp.citizen().getCitizenIDCardInfo(
 *       GetCitizenIDCardInfoParams.builder().regnum("РД00000000").build());
 *   System.out.println(card.firstname() + " " + card.lastname());
 * }
 * }</pre>
 *
 * <p>One client is meant to be shared: it is immutable after {@link Builder#build()}, safe for
 * concurrent use, and keeps connections alive. The service groups ({@code citizen()}, {@code
 * health()}, ...) are inherited from {@link XypGroups}; {@link #call} and {@link #invoke} reach any
 * operation by its original XYP name.
 */
public final class XypClient extends XypGroups implements AutoCloseable {

  /** The environment variable the access token is read from when the builder has none. */
  public static final String ACCESS_TOKEN_ENV = "XYP_ACCESS_TOKEN";

  /** The environment variable naming the key file when the builder has no private key. */
  public static final String PRIVATE_KEY_ENV = "XYP_PRIVATE_KEY";

  /** Where XYP answers from inside the National Data Center VPN. */
  public static final String DEFAULT_BASE_URL = "https://xyp.gov.mn";

  /** How long one HTTP request to XYP may take, including reading the response body. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /** Where a response that does not fit the SDK's model should be reported. */
  public static final String ISSUES_URL = "https://github.com/baljinnyamday/xyp-sdk/issues";

  /** The one log line this SDK writes; see {@link #invoke}. */
  static final String MISMATCH_WARNING =
      "XYP's response does not fit the SDK's model. The call succeeded and nothing was lost: the "
          + "listed fields are null/empty and their raw values are in extras().mismatches(). This "
          + "is a gap in the SDK's models, generated from XYP's public catalog — not an error from "
          + "XYP or in your code. Please report it at "
          + ISSUES_URL;

  /**
   * One URL path segment such as "citizen-1.5.0". {@link CallOptions.Builder#endpoint} takes it
   * from the caller, and it must not be able to reach another path or host.
   */
  private static final Pattern ENDPOINT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

  /** Reads the namespace of an endpoint whose WSDL is not checked into spec/wsdl/. */
  private static final Pattern TARGET_NAMESPACE = Pattern.compile("targetNamespace=\"([^\"]+)\"");

  private final String baseUrl;
  private final Signer signer;
  private final Transport transport;
  private final Logger logger;

  /**
   * Endpoint to XML namespace. Seeded from the generated registry, it grows as endpoints outside
   * the registry have their WSDL read.
   */
  private final Map<String, String> namespaces = new ConcurrentHashMap<>(Registry.KNOWN_NAMESPACES);

  private final AtomicBoolean closed = new AtomicBoolean();

  private XypClient(String baseUrl, Signer signer, Transport transport, Logger logger) {
    this.baseUrl = baseUrl;
    this.signer = signer;
    this.transport = transport;
    this.logger = logger;
  }

  /**
   * Starts a client configuration. With no settings at all, the credentials come from {@value
   * #ACCESS_TOKEN_ENV} and {@value #PRIVATE_KEY_ENV}.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Runs a service by its original XYP name, without approvals, and returns the raw response.
   *
   * <pre>{@code
   * Object data = xyp.call("WS100101_getCitizenIDCardInfo", Params.of("regnum", "РД00000000"));
   * }</pre>
   *
   * @param operation the XYP operation name, e.g. {@code "WS100101_getCitizenIDCardInfo"}
   * @param params {@code null}, {@link Params}, a {@link RequestParams} or a {@code Map<String,
   *     ?>}; see the package documentation
   * @return the {@code <response>} tree: nested unmodifiable {@code Map<String, Object>} (in
   *     document order), {@code List<Object>}, {@code String} and {@code null}
   * @throws XypConfigException when the call is set up incorrectly
   * @throws XypConnectionException when XYP cannot be reached
   * @throws XypResponseException when XYP answers with something that is not a service response
   * @throws XypApiException when XYP answers with a non-zero result code
   */
  public Object call(String operation, Object params) {
    return call(operation, params, CallOptions.none());
  }

  /**
   * Runs a service by its original XYP name and returns the raw response. This is how to reach a
   * service newer than this SDK version:
   *
   * <pre>{@code
   * Object data = xyp.call("WS109999_brandNew", Params.of("regnum", "РД00000000"),
   *     CallOptions.builder().endpoint("citizen-1.5.0").build());
   * }</pre>
   *
   * @param operation the XYP operation name, e.g. {@code "WS100101_getCitizenIDCardInfo"}
   * @param params {@code null}, {@link Params}, a {@link RequestParams} or a {@code Map<String,
   *     ?>}; see the package documentation
   * @param options the approvals and the endpoint for this call
   * @return the {@code <response>} tree: nested unmodifiable {@code Map<String, Object>} (in
   *     document order), {@code List<Object>}, {@code String} and {@code null}
   * @throws XypConfigException when the call is set up incorrectly
   * @throws XypConnectionException when XYP cannot be reached
   * @throws XypResponseException when XYP answers with something that is not a service response
   * @throws XypApiException when XYP answers with a non-zero result code
   */
  public Object call(String operation, Object params, CallOptions options) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(options, "options");
    return send(operation, params, options);
  }

  /**
   * Runs a service and decodes the response with {@code decoder}. The generated service methods are
   * built on it; use it directly for a type of your own (see {@link ResponseDecoder}).
   *
   * <p>A field the response does not fit is decoded as {@code null} (an empty list for a list) and
   * recorded in the {@link Extras} the decoder can read; the call itself still succeeds, because
   * the SDK's models come from a hand-typed catalog and real data sometimes disagrees with them.
   * When that happens, one {@code WARNING} is logged, naming the operation and the fields' paths,
   * never their values.
   *
   * @param <T> the type of the decoded response
   * @param operation the XYP operation name, e.g. {@code "WS100101_getCitizenIDCardInfo"}
   * @param params {@code null}, {@link Params}, a {@link RequestParams} or a {@code Map<String,
   *     ?>}; see the package documentation
   * @param options the approvals and the endpoint for this call
   * @param decoder the decoder of the response object
   * @return the decoded response
   * @throws XypConfigException when the call is set up incorrectly
   * @throws XypConnectionException when XYP cannot be reached
   * @throws XypResponseException when XYP answers with something that is not a service response
   * @throws XypApiException when XYP answers with a non-zero result code
   */
  public <T> T invoke(
      String operation, Object params, CallOptions options, ResponseDecoder<T> decoder) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(decoder, "decoder");
    Object data = send(operation, params, options);
    DecodeSession.Decoded<T> decoded = DecodeSession.decode(data, decoder);
    if (!decoded.mismatches().isEmpty()) {
      warnMismatches(operation, decoded.mismatches());
    }
    return decoded.value();
  }

  /**
   * Releases the kept-alive connections and the client's threads. On Java 21 and later this waits
   * for requests in flight to finish; on Java 17 the JDK's HTTP client has no way to close, and its
   * resources are released once this client is unreachable. Calls made after {@code close()} throw
   * {@link XypConfigException}. Closing twice does nothing.
   */
  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      transport.close();
    }
  }

  /**
   * Names the base URL only, never the access token or the key.
   *
   * @return e.g. {@code "XypClient[baseUrl=https://xyp.gov.mn]"}
   */
  @Override
  public String toString() {
    return "XypClient[baseUrl=" + baseUrl + "]";
  }

  private Object send(String operation, Object params, CallOptions options) {
    if (closed.get()) {
      throw new XypConfigException("this XypClient is closed");
    }
    String endpoint = options.endpoint().orElse(null);
    if (endpoint == null) {
      endpoint = Registry.OPERATION_ENDPOINTS.get(operation);
      if (endpoint == null) {
        throw new XypConfigException(
            "unknown operation "
                + Encoder.quote(operation)
                + ". Pass CallOptions.builder().endpoint(\"<name>-<version>\") to call a service "
                + "this SDK version does not know about.");
      }
    }
    if (!ENDPOINT_NAME.matcher(endpoint).matches()) {
      throw new XypConfigException(
          "endpoint " + Encoder.quote(endpoint) + " is not a name like \"citizen-1.5.0\"");
    }
    // Everything the caller supplied is checked before the first byte goes out.
    String requestBody =
        Envelope.requestBody(
            operation, params, options.citizen().orElse(null), options.operator().orElse(null));
    URI address = URI.create(baseUrl + "/" + endpoint + "/ws");
    String envelope = Envelope.wrap(operation, namespaceOf(endpoint, address), requestBody);
    Transport.Reply reply = transport.post(address, envelope, signer.sign());
    return SoapResponse.unwrap(reply.status(), reply.body());
  }

  /**
   * Returns the endpoint's XML namespace, reading it from the live WSDL the first time an endpoint
   * outside the generated registry is used.
   */
  private String namespaceOf(String endpoint, URI address) {
    String cached = namespaces.get(endpoint);
    if (cached != null) {
      return cached;
    }
    // The WSDL is fetched without a lock: two callers racing here cost one extra GET, holding a
    // lock across a network call would cost every caller.
    Transport.Reply reply = transport.get(URI.create(address + "?WSDL"));
    Matcher match = TARGET_NAMESPACE.matcher(new String(reply.body(), StandardCharsets.UTF_8));
    if (reply.status() >= SoapResponse.HTTP_ERROR_STATUS || !match.find()) {
      throw new XypResponseException(
          "could not read the WSDL of endpoint " + Encoder.quote(endpoint), reply.status());
    }
    String namespace = match.group(1);
    String raced = namespaces.putIfAbsent(endpoint, namespace);
    return raced == null ? namespace : raced;
  }

  /**
   * Writes the one log line this SDK produces. Field paths and problems only: the values are
   * citizen data and stay out of logs.
   */
  private void warnMismatches(String operation, List<Mismatch> mismatches) {
    if (!logger.isLoggable(Level.WARNING)) {
      return;
    }
    String fields = mismatches.stream().map(Mismatch::toString).collect(Collectors.joining("; "));
    // log(Level, String) takes the text as it stands; the format overloads would treat the
    // apostrophes in the message as MessageFormat quotes.
    logger.log(
        Level.WARNING, MISMATCH_WARNING + " [operation=" + operation + ", fields=" + fields + "]");
  }

  /**
   * Configures an {@link XypClient}. Every setting is optional as long as {@value
   * XypClient#ACCESS_TOKEN_ENV} and {@value XypClient#PRIVATE_KEY_ENV} are set. A builder is not
   * thread-safe; the client it builds is.
   */
  public static final class Builder {

    private String accessToken;
    private PrivateKey privateKey;
    private String baseUrl;
    private Duration timeout;
    private List<X509Certificate> trustedCertificates;
    private SSLContext sslContext;
    private boolean insecureSkipVerify;
    private ProxySelector proxy;
    private Logger logger;
    private Function<String, String> env = System::getenv;
    private Clock clock = Clock.systemUTC();

    private Builder() {}

    /**
     * Sets the access token issued by the National Data Center. Without one, {@value
     * XypClient#ACCESS_TOKEN_ENV} is read.
     *
     * @param accessToken the token, or {@code null} to read the environment
     * @return this builder
     */
    public Builder accessToken(String accessToken) {
      this.accessToken = accessToken;
      return this;
    }

    /**
     * Sets the key that signs every request; it must be RSA. It may be a key that never leaves an
     * HSM (through PKCS#11) or a {@code KeyStore}: the JDK picks the provider that can use it.
     * Without one, the key is read from the file named by {@value XypClient#PRIVATE_KEY_ENV}, with
     * {@link XypKeys#loadPrivateKey(Path)}.
     *
     * @param privateKey the RSA key, or {@code null} to read the environment
     * @return this builder
     */
    public Builder privateKey(PrivateKey privateKey) {
      this.privateKey = privateKey;
      return this;
    }

    /**
     * Sets where XYP is reached. Change it only when you reach XYP through your own proxy; it must
     * start with {@code https://} (or {@code http://}), and a trailing {@code /} is dropped.
     *
     * @param baseUrl the base URL, or {@code null} for {@value XypClient#DEFAULT_BASE_URL}
     * @return this builder
     */
    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * Sets how long one HTTP request may take, from connecting to reading the whole response.
     *
     * @param timeout the limit, or {@code null}, zero or negative for 30 seconds
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      this.timeout = timeout;
      return this;
    }

    /**
     * Sets the certificates XYP's certificate must chain to, instead of {@link
     * XypTls#bundledCertificates()}: the Mongolian national CAs and nothing else. Use it for a
     * proxy that re-terminates TLS, or a national CA newer than this SDK version.
     *
     * @param trustedCertificates the trust anchors, copied; or {@code null} for the bundled ones
     * @return this builder
     */
    public Builder trustedCertificates(Collection<? extends X509Certificate> trustedCertificates) {
      this.trustedCertificates =
          trustedCertificates == null ? null : new ArrayList<>(trustedCertificates);
      return this;
    }

    /**
     * Sets the TLS context outright, for full control (client certificates, a custom provider). It
     * takes precedence over {@link #trustedCertificates}. The client still allows only TLS 1.2 and
     * 1.3.
     *
     * @param sslContext the context, or {@code null} to build one from the trusted certificates
     * @return this builder
     */
    public Builder sslContext(SSLContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    /**
     * Turns certificate and host name verification off, for this client only. Read {@code
     * docs/tls.md} in the repository first: on XYP the certificate is the only thing proving you
     * reached XYP, because {@code xyp.gov.mn} is reached through a hosts-file entry. It cannot be
     * combined with {@link #trustedCertificates} or {@link #sslContext}.
     *
     * @param insecureSkipVerify {@code true} to accept any certificate
     * @return this builder
     */
    public Builder insecureSkipVerify(boolean insecureSkipVerify) {
      this.insecureSkipVerify = insecureSkipVerify;
      return this;
    }

    /**
     * Sets the proxy selector for this client's connections.
     *
     * @param proxy the selector, or {@code null} to connect directly
     * @return this builder
     */
    public Builder proxy(ProxySelector proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Sets the logger that receives the one warning this SDK writes, when a response does not fit
     * the SDK's model.
     *
     * @param logger the logger, or {@code null} for {@code
     *     System.getLogger("io.github.baljinnyamday.xyp")}
     * @return this builder
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /** Replaces the environment, so tests never read or change the real one. */
    Builder env(Function<String, String> env) {
      this.env = Objects.requireNonNull(env, "env");
      return this;
    }

    /** Replaces the clock the request timestamp comes from, so tests can pin it. */
    Builder clock(Clock clock) {
      this.clock = Objects.requireNonNull(clock, "clock");
      return this;
    }

    /**
     * Validates the settings, loads the credentials and builds the client. No request is sent to
     * XYP.
     *
     * @return a new client
     * @throws XypConfigException when a setting is missing or invalid
     */
    public XypClient build() {
      String token = firstNonEmpty(accessToken, env.apply(ACCESS_TOKEN_ENV));
      if (token == null) {
        throw new XypConfigException(
            "set XypClient.builder().accessToken(...) or the "
                + ACCESS_TOKEN_ENV
                + " environment variable");
      }
      PrivateKey key = resolveKey();
      String base = resolveBaseUrl();
      Duration limit =
          timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
      Logger log = logger != null ? logger : System.getLogger("io.github.baljinnyamday.xyp");
      Transport transport = new Transport(resolveSslContext(), proxy, limit);
      return new XypClient(base, new Signer(token, key, clock), transport, log);
    }

    private PrivateKey resolveKey() {
      if (privateKey != null) {
        // A caller-supplied key can be anything; XYP only accepts RSA signatures.
        if (!"RSA".equals(privateKey.getAlgorithm())) {
          throw XypKeys.notAnRsaKey();
        }
        return privateKey;
      }
      String path = firstNonEmpty(env.apply(PRIVATE_KEY_ENV));
      if (path == null) {
        throw new XypConfigException(
            "set XypClient.builder().privateKey(...) or point "
                + PRIVATE_KEY_ENV
                + " at the key file");
      }
      try {
        return XypKeys.loadPrivateKey(Path.of(path));
      } catch (InvalidPathException invalid) {
        throw new XypConfigException("cannot read the private key file (not a valid path)");
      }
    }

    private String resolveBaseUrl() {
      String base = firstNonEmpty(baseUrl);
      if (base == null) {
        return DEFAULT_BASE_URL;
      }
      String scheme = base.toLowerCase(Locale.ROOT);
      if (!scheme.startsWith("https://") && !scheme.startsWith("http://")) {
        throw new XypConfigException(
            "baseUrl must start with https:// (or http://), got " + Encoder.quote(base));
      }
      // A trailing slash would otherwise produce "https://xyp.gov.mn//citizen-1.5.0/ws".
      int end = base.length();
      while (end > 0 && base.charAt(end - 1) == '/') {
        end--;
      }
      String trimmed = base.substring(0, end);
      try {
        if (URI.create(trimmed + "/").getHost() == null) {
          throw new XypConfigException("baseUrl has no host, got " + Encoder.quote(base));
        }
      } catch (IllegalArgumentException invalid) {
        throw new XypConfigException("baseUrl is not a valid URL, got " + Encoder.quote(base));
      }
      return trimmed;
    }

    private SSLContext resolveSslContext() {
      if (insecureSkipVerify) {
        if (sslContext != null || trustedCertificates != null) {
          throw new XypConfigException(
              "insecureSkipVerify(true) cannot be combined with sslContext or "
                  + "trustedCertificates: choose whether to verify XYP's certificate");
        }
        return XypTls.trustAllContext();
      }
      if (sslContext != null) {
        return sslContext;
      }
      return XypTls.sslContext(
          trustedCertificates != null ? trustedCertificates : XypTls.bundledCertificates());
    }

    private static String firstNonEmpty(String... values) {
      for (String value : values) {
        if (value != null && !value.isEmpty()) {
          return value;
        }
      }
      return null;
    }
  }
}
