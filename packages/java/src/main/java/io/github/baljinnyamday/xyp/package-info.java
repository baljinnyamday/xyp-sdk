/**
 * A Java SDK for XYP (ХУР), Mongolia's government data exchange system. It signs every request,
 * builds the SOAP envelope, verifies XYP's certificate against the bundled national CAs, and
 * decodes the XML into records.
 *
 * <h2>Quick start</h2>
 *
 * <pre>{@code
 * try (XypClient xyp = XypClient.builder()
 *     .accessToken(System.getenv("XYP_ACCESS_TOKEN"))
 *     .privateKey(XypKeys.loadPrivateKey(Path.of("private.key")))
 *     .build()) {
 *   GetCitizenIDCardInfoResponse card = xyp.citizen().getCitizenIDCardInfo(
 *       GetCitizenIDCardInfoParams.builder().regnum("РД00000000").build(),
 *       CallOptions.builder().citizen(Auth.otp("РД00000000", 123456)).build());
 *   System.out.println(card.firstname() + " " + card.lastname());
 * }
 * }</pre>
 *
 * <p>Services are grouped by XYP endpoint, one package and one accessor each ({@code citizen()},
 * {@code health()}, {@code insurance()}, ...), and keep XYP's own names minus the code: {@code
 * WS100101_getCitizenIDCardInfo} is {@code xyp.citizen().getCitizenIDCardInfo(...)}.
 *
 * <p>One {@link io.github.baljinnyamday.xyp.XypClient} is meant to be shared: it is immutable, safe
 * for concurrent use and keeps connections alive. With no settings at all, the builder reads the
 * access token from {@code XYP_ACCESS_TOKEN} and the key file from {@code XYP_PRIVATE_KEY}.
 *
 * <h2>Request parameters</h2>
 *
 * <p>The generated methods take a generated {@code *Params} class, which knows the schema's field
 * order. {@link io.github.baljinnyamday.xyp.XypClient#call(String, Object) call} and {@link
 * io.github.baljinnyamday.xyp.XypClient#invoke invoke} take params in any of these shapes:
 *
 * <ul>
 *   <li>{@code null}, for a service without inputs;
 *   <li>a {@link io.github.baljinnyamday.xyp.RequestParams}, such as a generated {@code *Params}
 *       class, sent in the order it lists its fields;
 *   <li>{@link io.github.baljinnyamday.xyp.Params}, when you want to choose the order yourself;
 *   <li>a {@code Map} with {@code String} keys. A {@code LinkedHashMap} or a {@code SortedMap} is
 *       sent in its iteration order; any other map ({@code HashMap}, {@code Map.of}, an
 *       unmodifiable view) has its keys sorted, because its iteration order is not specified. Use
 *       {@code Params} when the schema order matters.
 * </ul>
 *
 * <p>A value is left out of the request when it is {@code null}, an empty string or an empty {@code
 * Optional}. Otherwise:
 *
 * <ul>
 *   <li>a {@code Boolean} becomes {@code 1} or {@code 0};
 *   <li>a {@code Byte}, {@code Short}, {@code Integer}, {@code Long} or {@code BigInteger} becomes
 *       its decimal form, and a {@code BigDecimal} its plain form (no exponent);
 *   <li>a {@code Float} or {@code Double} becomes the shortest decimal that reads back as the same
 *       number, without an exponent; NaN and infinity are refused;
 *   <li>a {@code byte[]} becomes standard base64;
 *   <li>an {@code Instant}, {@code OffsetDateTime} or {@code ZonedDateTime}, and a {@link
 *       io.github.baljinnyamday.xyp.XypDate} without {@code raw}, become UTC RFC 3339 such as
 *       {@code 2024-01-31T12:00:00Z}; an {@code XypDate} with {@code raw} is sent as it stands;
 *   <li>a {@code Collection} or an object array repeats the element once per item;
 *   <li>a nested {@code Map}, {@code Params} or {@code RequestParams} becomes a nested element.
 * </ul>
 *
 * Anything else is an {@link io.github.baljinnyamday.xyp.XypConfigException}, and so is an element
 * name that is not a valid XML name: names cannot be escaped, so they are checked instead. Text is
 * escaped, so no value can change the shape of the request.
 *
 * <h2>Responses</h2>
 *
 * <p>A response field that does not fit the SDK's model never fails a call: it is decoded as {@code
 * null} (an empty list for a list), and its raw value is listed in the response's {@link
 * io.github.baljinnyamday.xyp.Extras#mismatches() extras().mismatches()}, with one warning logged
 * through {@code System.Logger} that names the field but never its value. The models are generated
 * from XYP's hand-typed public catalog, so this is a gap in the SDK, not in XYP's answer or in your
 * code; please report one at {@value io.github.baljinnyamday.xyp.XypClient#ISSUES_URL}.
 *
 * <p>Every field of a generated response record is nullable, because XML cannot tell an empty value
 * from a missing one, except lists, which are empty instead. Numbers are {@code Long}, {@code
 * Double} or {@code BigDecimal}, so 0 is a real answer and {@code null} means absent. Dates are
 * {@link io.github.baljinnyamday.xyp.XypDate}, which keeps the text XYP sent even when it is not
 * ISO 8601. {@link io.github.baljinnyamday.xyp.Extras#raw() extras().raw()} holds the whole
 * response, including fields the model does not declare.
 *
 * <p>For a service the generated code does not cover, write a {@link
 * io.github.baljinnyamday.xyp.ResponseDecoder} with {@link
 * io.github.baljinnyamday.xyp.ResponseReader} and {@link io.github.baljinnyamday.xyp.Decoders}, or
 * take the raw tree from {@code call}.
 *
 * <h2>Errors</h2>
 *
 * <p>Every exception is unchecked, extends {@link io.github.baljinnyamday.xyp.XypException} and
 * carries an {@link io.github.baljinnyamday.xyp.Origin}: {@link
 * io.github.baljinnyamday.xyp.XypConfigException} (the setup, {@code CONFIG}), {@link
 * io.github.baljinnyamday.xyp.XypConnectionException} (XYP unreachable, {@code NETWORK}), {@link
 * io.github.baljinnyamday.xyp.XypResponseException} (not a service response, {@code XYP}) and
 * {@link io.github.baljinnyamday.xyp.XypApiException} (a non-zero result code, {@code XYP}).
 *
 * <pre>{@code
 * try {
 *   return xyp.citizen().getCitizenIDCardInfo(params);
 * } catch (XypApiException e) {
 *   if (e.reason() == XypApiException.Reason.NOT_FOUND) return null;
 *   log.warn("XYP said {} (request {})", e.getMessage(), e.requestId());
 *   throw e;
 * }
 * }</pre>
 *
 * <p>No exception message or log line of the SDK, and no {@code toString()} of its own types
 * ({@code XypClient}, {@code Auth}, {@code Mismatch}, {@code Extras}, the exceptions), contains the
 * access token, the key, a one-time code, a registration number or a response value. Generated
 * response records, and values such as {@link io.github.baljinnyamday.xyp.XypDate}, are plain data
 * carriers: their {@code toString()} prints what they hold, citizen data included ({@code
 * ListAccessResponse} even holds your access token), so log the fields you need, never a whole
 * record.
 *
 * <h2>TLS</h2>
 *
 * <p>XYP's certificate is issued by the Mongolian national PKI, which no operating system or JDK
 * trusts by default. The SDK trusts exactly the two bundled national CAs, for its own requests
 * only: it never changes the JVM's default {@code SSLContext} or a system property. See {@link
 * io.github.baljinnyamday.xyp.XypTls} and {@code docs/tls.md} in the repository.
 */
package io.github.baljinnyamday.xyp;
