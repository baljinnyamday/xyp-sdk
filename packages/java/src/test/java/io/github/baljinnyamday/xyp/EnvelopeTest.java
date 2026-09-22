package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EnvelopeTest {

  private static final String NAMESPACE = "http://citizen.xyp.gov.mn/";
  private static final String OPERATION = "WS100101_getCitizenIDCardInfo";
  private static final OffsetDateTime MOMENT =
      OffsetDateTime.of(2024, 1, 31, 12, 0, 0, 0, ZoneOffset.UTC);

  private static String envelope(Object params) {
    return Envelope.build(OPERATION, NAMESPACE, params, null, null);
  }

  /** What a generated *Params class looks like. */
  private record Nested(String code, String empty) implements RequestParams {
    @Override
    public Params toParams() {
      return Params.builder().add("code", code).add("empty", empty).build();
    }
  }

  @Test
  void omitsEmptyValuesRepeatsListsAndNestsObjects() {
    String envelope =
        envelope(
            Params.builder()
                .add("skipped", null)
                .add("flag", false)
                .add("ids", List.of(1L, 2L))
                .add("nested", new Nested("A", ""))
                .add("photo", new byte[] {0, 1})
                .build());

    assertTrue(
        envelope.contains(
            "<request><flag>0</flag><ids>1</ids><ids>2</ids>"
                + "<nested><code>A</code></nested><photo>AAE=</photo></request>"),
        envelope);
  }

  static Stream<Arguments> everyParamShape() {
    Map<String, Object> hashMap = new HashMap<>();
    hashMap.put("b", "2");
    hashMap.put("a", "1");
    Map<String, Object> linked = new LinkedHashMap<>();
    linked.put("b", "2");
    linked.put("a", "1");
    Map<String, Object> sorted = new TreeMap<>(Map.of("b", "2", "a", "1"));
    return Stream.of(
        Arguments.of("null", null, "<request />"),
        Arguments.of("empty params", Params.empty(), "<request />"),
        Arguments.of(
            "ordered params",
            Params.builder().add("b", "2").add("a", "1").build(),
            "<request><b>2</b><a>1</a></request>"),
        Arguments.of("a hash map is sorted", hashMap, "<request><a>1</a><b>2</b></request>"),
        Arguments.of(
            "Map.of is sorted", Map.of("b", "2", "a", "1"), "<request><a>1</a><b>2</b></request>"),
        Arguments.of("a linked map keeps its order", linked, "<request><b>2</b><a>1</a></request>"),
        Arguments.of("a sorted map keeps its order", sorted, "<request><a>1</a><b>2</b></request>"),
        Arguments.of("request params", new Nested("A", null), "<request><code>A</code></request>"),
        Arguments.of(
            "an OffsetDateTime is UTC RFC 3339",
            Params.of("at", MOMENT.withOffsetSameInstant(ZoneOffset.ofHours(8))),
            "<request><at>2024-01-31T12:00:00Z</at></request>"),
        Arguments.of(
            "an Instant is UTC RFC 3339",
            Params.of("at", MOMENT.toInstant()),
            "<request><at>2024-01-31T12:00:00Z</at></request>"),
        Arguments.of(
            "a ZonedDateTime is UTC RFC 3339",
            Params.of("at", MOMENT.atZoneSameInstant(ZoneId.of("Asia/Ulaanbaatar"))),
            "<request><at>2024-01-31T12:00:00Z</at></request>"),
        Arguments.of(
            "a fraction keeps no trailing zeros",
            Params.of("at", Instant.parse("2024-01-31T12:00:00.120Z")),
            "<request><at>2024-01-31T12:00:00.12Z</at></request>"),
        Arguments.of(
            "a raw date is sent as it stands",
            Params.of("at", XypDate.ofRaw("31.01.2024 <x>")),
            "<request><at>31.01.2024 &lt;x&gt;</at></request>"),
        Arguments.of(
            "a date with only a time is formatted",
            Params.of("at", XypDate.of(MOMENT)),
            "<request><at>2024-01-31T12:00:00Z</at></request>"),
        Arguments.of(
            "an empty date is left out", Params.of("at", new XypDate(null, null)), "<request />"),
        Arguments.of("an empty string is left out", Params.of("s", ""), "<request />"),
        Arguments.of(
            "any CharSequence is text",
            Params.of("s", new StringBuilder("a&b")),
            "<request><s>a&amp;b</s></request>"),
        Arguments.of(
            "an empty Optional is left out", Params.of("n", Optional.empty()), "<request />"),
        Arguments.of(
            "an Optional is followed",
            Params.of("n", Optional.of(7L)),
            "<request><n>7</n></request>"),
        Arguments.of("true is 1", Params.of("b", true), "<request><b>1</b></request>"),
        Arguments.of(
            "every integer type",
            Params.builder()
                .add("a", (byte) 1)
                .add("b", (short) 2)
                .add("c", 3)
                .add("d", -4L)
                .add("e", new BigInteger("123456789012345678901234567890"))
                .build(),
            "<request><a>1</a><b>2</b><c>3</c><d>-4</d><e>123456789012345678901234567890</e></request>"),
        Arguments.of(
            "a decimal has no exponent",
            Params.of("n", new BigDecimal("1E+3")),
            "<request><n>1000</n></request>"),
        Arguments.of(
            "floats keep their shortest form",
            Params.of("n", 0.5),
            "<request><n>0.5</n></request>"),
        Arguments.of("a float too", Params.of("n", 0.1f), "<request><n>0.1</n></request>"),
        Arguments.of(
            "an object array repeats",
            Params.of("n", new Object[] {"a", null, "b"}),
            "<request><n>a</n><n>b</n></request>"),
        Arguments.of(
            "a list skips null items",
            Params.of("n", Arrays.asList("a", null, "b")),
            "<request><n>a</n><n>b</n></request>"),
        Arguments.of("an empty list is left out", Params.of("n", List.of()), "<request />"),
        Arguments.of(
            "an empty nested map is an empty element",
            Params.of("n", Map.of()),
            "<request><n /></request>"),
        Arguments.of(
            "a nested map",
            Params.of("n", Map.of("b", 2L, "a", 1L)),
            "<request><n><a>1</a><b>2</b></n></request>"),
        Arguments.of(
            "empty bytes are an empty element",
            Params.of("n", new byte[0]),
            "<request><n /></request>"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void everyParamShape(String name, Object params, String want) {
    String envelope = envelope(params);
    assertTrue(envelope.contains(want), () -> envelope + " does not contain " + want);
  }

  @ParameterizedTest
  @MethodSource
  void doublesAreWrittenLikeGoFormatFloat(double value, String want) {
    assertEquals(want, Scalars.formatDouble(value));
  }

  static Stream<Arguments> doublesAreWrittenLikeGoFormatFloat() {
    return Stream.of(
        Arguments.of(0.5, "0.5"),
        Arguments.of(0.1, "0.1"),
        Arguments.of(7.0, "7"),
        Arguments.of(-0.0, "-0"),
        Arguments.of(0.0, "0"),
        Arguments.of(1e21, "1000000000000000000000"),
        Arguments.of(123456789.125, "123456789.125"),
        Arguments.of(1.0 / 3, "0.3333333333333333"),
        Arguments.of(2.82879384806159e17, "282879384806159000"),
        Arguments.of(5e-324, "0." + "0".repeat(323) + "5"),
        Arguments.of(Double.MAX_VALUE, "17976931348623157" + "0".repeat(292)),
        Arguments.of(-1.5e-7, "-0.00000015"));
  }

  @Test
  void floatsUseTheirOwnPrecision() {
    assertEquals("0.1", Scalars.formatFloat(0.1f));
    assertEquals("16777216", Scalars.formatFloat(16777216f));
    // The float nearest 3.4028235 is 3.40282344..., so eight digits suffice and the nearer wins.
    assertEquals("3.4028234", Scalars.formatFloat(3.4028235f));
  }

  @ParameterizedTest
  @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
  void refusesNanAndInfinity(double value) {
    XypConfigException error =
        assertThrows(XypConfigException.class, () -> envelope(Params.of("n", value)));
    assertTrue(error.getMessage().contains("\"n\""), error.getMessage());
    assertThrows(XypConfigException.class, () -> envelope(Params.of("n", (float) value)));
  }

  @Test
  void refusesAValueItCannotSend() {
    XypConfigException error =
        assertThrows(XypConfigException.class, () -> envelope(Params.of("bad", new Object())));
    assertEquals(Origin.CONFIG, error.origin());
    assertEquals("cannot send field \"bad\" of type java.lang.Object to XYP", error.getMessage());
    assertThrows(XypConfigException.class, () -> envelope(Params.of("bad", new int[] {1})));
    assertThrows(
        XypConfigException.class,
        () -> envelope(Params.of("bad", java.time.LocalDate.of(2024, 1, 31))));
  }

  @Test
  void refusesParamsOfAnotherShape() {
    XypConfigException error = assertThrows(XypConfigException.class, () -> envelope(List.of("a")));
    assertTrue(error.getMessage().startsWith("params must be null, Params"), error.getMessage());
    assertThrows(XypConfigException.class, () -> envelope(Map.of(1, "a")));
  }

  @Test
  void cannotBeUsedToInjectXml() {
    String envelope =
        Envelope.build(
            OPERATION,
            "ns\"/><evil",
            Params.of("regnum", "</regnum><admin>true</admin>"),
            null,
            null);

    assertTrue(
        envelope.contains("<regnum>&lt;/regnum&gt;&lt;admin&gt;true&lt;/admin&gt;</regnum>"),
        envelope);
    assertFalse(envelope.contains("<admin>") || envelope.contains("<evil"), envelope);
    assertTrue(envelope.contains("xmlns:tns=\"ns&quot;/&gt;&lt;evil\""), envelope);
  }

  @Test
  void escapesOnlyWhatZeepEscapes() {
    String envelope = envelope(Params.of("text", "a \"quoted\" 'text'\n\t&"));
    assertTrue(envelope.contains("<text>a \"quoted\" 'text'\n\t&amp;</text>"), envelope);
  }

  @Test
  void startsWithTheDeclarationAndAuth() {
    Auth citizen = Auth.otp("РД00000000", 1234);
    String envelope =
        Envelope.build(OPERATION, NAMESPACE, Params.of("regnum", "РД00000000"), citizen, null);

    assertTrue(
        envelope.startsWith("<?xml version='1.0' encoding='utf-8'?>\n<soap:Envelope "), envelope);
    assertTrue(
        envelope.contains(
            "<request><auth><citizen><authType>1</authType><otp>1234</otp>"
                + "<regnum>РД00000000</regnum></citizen></auth><regnum>"),
        envelope);
  }

  @Test
  void writesEveryAuthFieldInWsdlOrder() {
    Auth everything =
        Auth.builder()
            .signature("sig")
            .regnum("r")
            .otp(5)
            .fingerprint(new byte[] {1})
            .civilId("c")
            .certFingerprint("cf")
            .authType(AuthType.DAN_APP)
            .authAppName("app")
            .appAuthToken("tok")
            .build();
    String envelope = Envelope.build(OPERATION, NAMESPACE, null, null, everything);

    assertTrue(
        envelope.contains(
            "<request><auth><operator><appAuthToken>tok</appAuthToken><authAppName>app</authAppName>"
                + "<authType>5</authType><certFingerprint>cf</certFingerprint><civilId>c</civilId>"
                + "<fingerprint>AQ==</fingerprint><otp>5</otp><regnum>r</regnum>"
                + "<signature>sig</signature></operator></auth></request>"),
        envelope);
  }

  @Test
  void anAuthWithoutATypeStillSendsItsOtp() {
    String envelope = Envelope.build(OPERATION, NAMESPACE, null, Auth.builder().build(), null);
    assertTrue(envelope.contains("<auth><citizen><otp>0</otp></citizen></auth>"), envelope);
  }

  static Stream<Arguments> namesThatAreNotXmlNames() {
    return Stream.of(
        Arguments.of("param name", OPERATION, Params.of("a><admin>1</admin><b", "x")),
        Arguments.of("map key", OPERATION, Map.of("a/><evil", "x")),
        Arguments.of("nested name", OPERATION, Params.of("ok", Params.of("b c", "x"))),
        Arguments.of("empty name", OPERATION, Params.of("", "x")),
        Arguments.of("a null value still has its name checked", OPERATION, Params.of("1a", null)),
        Arguments.of("operation", "WS1><evil", null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource
  void namesThatAreNotXmlNames(String name, String operation, Object params) {
    XypConfigException error =
        assertThrows(
            XypConfigException.class,
            () -> Envelope.build(operation, NAMESPACE, params, null, null));
    assertTrue(error.getMessage().contains("not a valid XML element name"), error.getMessage());
  }
}
