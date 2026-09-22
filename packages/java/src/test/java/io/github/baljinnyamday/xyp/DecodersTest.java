package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Soft decoding: a value that does not fit never fails a call. */
class DecodersTest {

  record Year(Long year) {
    static Year decode(ResponseReader r) {
      return new Year(r.get("year", Decoders.INT));
    }
  }

  record Address(String city) {
    static Address decode(ResponseReader r) {
      return new Address(r.get("city", Decoders.STRING));
    }
  }

  /** Shaped like a generated response record: every kind, and extras last. */
  record Sample(
      String firstName,
      Long age,
      Boolean active,
      XypDate born,
      byte[] photo,
      BigDecimal amount,
      Double ratio,
      List<Year> listData,
      Address address,
      Object anything,
      Extras extras) {
    static Sample decode(ResponseReader r) {
      return new Sample(
          r.get("firstName", Decoders.STRING),
          r.get("age", Decoders.INT),
          r.get("active", Decoders.BOOL),
          r.get("born", Decoders.DATE),
          r.get("photo", Decoders.BYTES),
          r.get("amount", Decoders.DECIMAL),
          r.get("ratio", Decoders.FLOAT),
          r.get("listData", Decoders.list(Decoders.object(Year::decode))),
          r.get("address", Decoders.object(Address::decode)),
          r.get("anything", Decoders.ANY),
          r.extras());
    }
  }

  private static Sample decode(Object tree) {
    return ResponseReader.decode(tree, Sample::decode);
  }

  private static Sample decode(String name, Object value) {
    return decode(Map.of(name, value));
  }

  private static List<String> paths(Extras extras) {
    return extras.mismatches().stream().map(Mismatch::path).collect(Collectors.toList());
  }

  @Test
  void coercesTextByFieldTypeAndLeavesMissingFieldsNull() {
    Map<String, Object> tree =
        Map.of(
            "firstName", "Бат",
            "age", "34",
            "active", "true",
            "born", "1990-05-01T00:00:00+08:00",
            "photo", "AAE=",
            "amount", "1234567890123456789.50",
            "ratio", "0.5",
            "anything", Map.of("kept", "as is"));

    Sample out = decode(tree);

    assertEquals(List.of(), out.extras().mismatches());
    assertEquals("Бат", out.firstName());
    assertEquals(34L, out.age());
    assertEquals(Boolean.TRUE, out.active());
    assertTrue(
        out.born()
            .time()
            .isEqual(OffsetDateTime.of(1990, 5, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8))));
    assertArrayEquals(new byte[] {0, 1}, out.photo());
    assertEquals("1234567890123456789.50", out.amount().toString());
    assertEquals(0.5, out.ratio());
    assertEquals(List.of(), out.listData(), "an absent list is empty, never null");
    assertNull(out.address());
    assertEquals(Map.of("kept", "as is"), out.anything());
    assertSame(tree, out.extras().raw());
  }

  @ParameterizedTest
  @CsvSource({
    "2024-01-31, true",
    "2024-01-31 12:00:00, true",
    "2024-01-31T12:00:00+0800, true",
    "2024-01-31T12:00, true",
    "2024-01-31T12:00:00.123456789123Z, true",
    "2020, false",
    "31.01.2024, false",
    "2024-13-45, false",
    "2024-02-30T00:00:00Z, false"
  })
  void readsDatesLeniently(String text, boolean hasTime) {
    Sample out = decode("born", text);
    assertEquals(List.of(), out.extras().mismatches(), "a date is never a mismatch");
    assertEquals(text, out.born().raw());
    assertEquals(hasTime, out.born().time() != null, () -> "time = " + out.born().time());
  }

  @Test
  void datesWithoutAZoneAreUtc() {
    assertEquals(
        OffsetDateTime.of(2024, 1, 31, 12, 0, 0, 0, ZoneOffset.UTC),
        decode("born", "2024-01-31T12:00:00").born().time());
    assertEquals(
        OffsetDateTime.of(2024, 1, 31, 0, 0, 0, 0, ZoneOffset.UTC),
        decode("born", "2024-01-31").born().time());
    assertEquals(
        ZoneOffset.ofHours(8),
        decode("born", "2024-01-31T12:00:00+0800").born().time().getOffset());
  }

  @Test
  void treatsASingleItemAsAOneItemList() {
    Sample out = decode("listData", Map.of("year", "2020"));
    assertEquals(List.of(), out.extras().mismatches());
    assertEquals(List.of(new Year(2020L)), out.listData());
  }

  @Test
  void dropsNullItemsWithoutComplaining() {
    Sample out = decode("listData", Arrays.asList(Map.of("year", "1"), null));
    assertEquals(List.of(), out.extras().mismatches(), "a null item from XYP is not a mismatch");
    assertEquals(List.of(new Year(1L)), out.listData());
    assertThrows(UnsupportedOperationException.class, () -> out.listData().add(new Year(2L)));
  }

  @Test
  void acceptsAMissingResponse() {
    Sample out = decode(null);
    assertEquals(List.of(), out.extras().mismatches());
    assertNull(out.firstName());
    assertNull(out.age());
    assertNull(out.born());
    assertNull(out.extras().raw());
  }

  @Test
  void neverFailsTheCallAndSaysWhoseFaultItIs() {
    Sample out =
        decode(
            Map.of(
                "firstName", "Бат",
                "age", "not-a-number-РД00000000",
                "listData", List.of(Map.of("year", "2020"), Map.of("year", "MMXXI")),
                "address", "just text"));

    assertEquals("Бат", out.firstName(), "a field that fits is still decoded");
    assertNull(out.age());
    // A nested field that does not fit only costs that field, not the whole list.
    assertEquals(List.of(new Year(2020L), new Year(null)), out.listData());
    assertNull(out.address());
    List<Mismatch> mismatches = out.extras().mismatches();
    assertEquals(List.of("age", "listData[1].year", "address"), paths(out.extras()));
    assertEquals(
        List.of("not-a-number-РД00000000", "MMXXI", "just text"),
        mismatches.stream().map(Mismatch::value).collect(Collectors.toList()));
    assertEquals(
        List.of("not a valid int", "not a valid int", "expected an object"),
        mismatches.stream().map(Mismatch::problem).collect(Collectors.toList()));
  }

  @Test
  void neverPutsAStandInInsideAList() {
    record Lists(List<Long> years, List<Year> listData, Extras extras) {
      static Lists decode(ResponseReader r) {
        return new Lists(
            r.get("years", Decoders.list(Decoders.INT)),
            r.get("listData", Decoders.list(Decoders.object(Year::decode))),
            r.extras());
      }
    }

    Lists out =
        ResponseReader.decode(
            Map.of(
                "years", List.of("2020", "MMXXI", "x"),
                "listData", List.of(Map.of("year", "1"), "text")),
            Lists::decode);

    assertEquals(List.of(), out.years(), "the whole list is dropped");
    assertEquals(List.of(), out.listData());
    assertEquals(List.of("years[1]", "years[2]", "listData[1]"), paths(out.extras()));
  }

  @Test
  void indexesListItemsPastSkippedNulls() {
    List<Long> years =
        ResponseReader.decode(
            Map.of("years", Arrays.asList(null, "1", null, "x")),
            r -> {
              List<Long> decoded = r.get("years", Decoders.list(Decoders.INT));
              assertEquals(List.of("years[1]"), paths(r.extras()));
              return decoded;
            });
    assertEquals(List.of(), years);
  }

  @Test
  void fillsListsOfEverySupportedItemType() {
    record Lists(
        List<String> names,
        List<Object> anything,
        List<BigDecimal> amounts,
        List<XypDate> dates,
        List<Boolean> flags,
        List<Double> ratios,
        List<byte[]> blobs,
        Extras extras) {
      static Lists decode(ResponseReader r) {
        return new Lists(
            r.get("names", Decoders.list(Decoders.STRING)),
            r.get("anything", Decoders.list(Decoders.ANY)),
            r.get("amounts", Decoders.list(Decoders.DECIMAL)),
            r.get("dates", Decoders.list(Decoders.DATE)),
            r.get("flags", Decoders.list(Decoders.BOOL)),
            r.get("ratios", Decoders.list(Decoders.FLOAT)),
            r.get("blobs", Decoders.list(Decoders.BYTES)),
            r.extras());
      }
    }

    Lists out =
        ResponseReader.decode(
            Map.of(
                "names", List.of("a", "b"),
                "anything", List.of("text", Map.of("nested", "value")),
                "amounts", "1.5",
                "dates", List.of("2024-01-31", "31.01.2024"),
                "flags", List.of("Y", "off"),
                "ratios", List.of("1e3", ".5"),
                "blobs", "AAE="),
            Lists::decode);

    assertEquals(List.of(), out.extras().mismatches());
    assertEquals(List.of("a", "b"), out.names());
    assertEquals(List.of("text", Map.of("nested", "value")), out.anything());
    assertEquals(List.of(new BigDecimal("1.5")), out.amounts(), "a single value wrapped in a list");
    assertEquals("31.01.2024", out.dates().get(1).raw());
    assertNull(out.dates().get(1).time());
    assertEquals(List.of(true, false), out.flags());
    assertEquals(List.of(1000.0, 0.5), out.ratios());
    assertArrayEquals(new byte[] {0, 1}, out.blobs().get(0));
  }

  @ParameterizedTest
  @ValueSource(strings = {"N/A", "0", "NoImage", "байхгүй", "AAE"})
  void reportsTextInABytesFieldInsteadOfGarbage(String text) {
    // "none" is left out: four alphabet characters are valid base64 in any decoder.
    Sample out = decode("photo", text);
    assertNull(out.photo());
    assertEquals(1, out.extras().mismatches().size());
    assertEquals(text, out.extras().mismatches().get(0).value());
    assertEquals("not a valid bytes", out.extras().mismatches().get(0).problem());
  }

  @Test
  void decodesLineWrappedBase64() {
    Sample out = decode("photo", "AA\r\nE=\t");
    assertEquals(List.of(), out.extras().mismatches());
    assertArrayEquals(new byte[] {0, 1}, out.photo());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Y", "yes", "T", "on", "TRUE", "1"})
  void readsHandTypedTrue(String text) {
    assertEquals(Boolean.TRUE, decode("active", text).active());
  }

  @ParameterizedTest
  @ValueSource(strings = {"N", "no", "F", "off", "0", "False"})
  void readsHandTypedFalse(String text) {
    assertEquals(Boolean.FALSE, decode("active", text).active());
  }

  @Test
  void rejectsAnythingElseInABool() {
    Sample out = decode("active", "maybe");
    assertNull(out.active());
    assertEquals("not a valid bool", out.extras().mismatches().get(0).problem());
  }

  @Test
  void readsHandTypedIntegers() {
    assertEquals(
        34L, decode("age", "34.0").age(), "\"34.0\" is an integer written by a spreadsheet");
    assertEquals(34L, decode("age", "+34").age());
    assertEquals(-34L, decode("age", "-34").age());
    Sample fractional = decode("age", "34.5");
    assertNull(fractional.age());
    assertEquals(1, fractional.extras().mismatches().size());
  }

  @Test
  void rejectsIntegersThatDoNotFitALong() {
    Sample out = decode("age", "9223372036854775808");
    assertNull(out.age());
    assertEquals(
        List.of("age (not a valid int)"),
        out.extras().mismatches().stream().map(Mismatch::toString).toList());
    assertEquals(Long.MAX_VALUE, decode("age", "9223372036854775807").age());
  }

  @Test
  void readsFloatsAndDecimalsWithTheSamePattern() {
    assertEquals(1.5e10, decode("ratio", "1.5E+10").ratio());
    assertEquals(5.0, decode("ratio", "5.").ratio());
    assertNull(decode("ratio", "1e400").ratio(), "out of range is rejected, not infinity");
    assertNull(decode("ratio", "NaN").ratio());
    assertNull(decode("ratio", "0x10").ratio());
    assertEquals(new BigDecimal("-1.5E+3"), decode("amount", "-1.5E+3").amount());
    assertNull(decode("amount", "1,5").amount());
    assertEquals(
        "not a valid decimal", decode("amount", "1,5").extras().mismatches().get(0).problem());
  }

  @Test
  void reportsAResponseThatIsNotAnObject() {
    Sample out = decode("just text");
    assertNull(out.firstName());
    List<Mismatch> mismatches = out.extras().mismatches();
    assertEquals(1, mismatches.size());
    assertEquals("", mismatches.get(0).path());
    assertEquals("expected an object", mismatches.get(0).problem());
    assertEquals("<response> (expected an object)", mismatches.get(0).toString());
    assertEquals("just text", out.extras().raw());
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"list | expected string, got a list", "object | expected string, got an object"})
  void namesTheShapeItGotForAScalar(String shape, String want) {
    Object node = shape.equals("list") ? List.of("a") : Map.of("a", "b");
    Sample out = decode("firstName", node);
    assertNull(out.firstName());
    assertEquals(want, out.extras().mismatches().get(0).problem());
  }

  @Test
  void everyScalarKindNamesItselfInTheShapeProblem() {
    Map<String, Decoder<?>> decoders =
        Map.of(
            "string", Decoders.STRING,
            "int", Decoders.INT,
            "float", Decoders.FLOAT,
            "bool", Decoders.BOOL,
            "decimal", Decoders.DECIMAL,
            "bytes", Decoders.BYTES,
            "date", Decoders.DATE);
    decoders.forEach(
        (kind, decoder) ->
            ResponseReader.decode(
                Map.of("x", List.of("a", "b")),
                r -> {
                  assertNull(r.get("x", decoder));
                  assertEquals(
                      "expected " + kind + ", got a list",
                      r.extras().mismatches().get(0).problem());
                  return null;
                }));
  }

  @Test
  void anyKeepsTheRawNode() {
    assertEquals(List.of("a", "b"), decode("anything", List.of("a", "b")).anything());
    assertEquals("text", decode("anything", "text").anything());
  }

  @Test
  void aCustomDecoderRejectsThroughItsContext() {
    Decoder<Character> initial =
        (node, context) -> {
          String text = Decoders.STRING.decode(node, context);
          if (text.length() != 1) {
            throw context.reject("not a valid initial");
          }
          return text.charAt(0);
        };
    record Person(Character initial, List<Character> initials, Extras extras) {}

    Person out =
        ResponseReader.decode(
            Map.of("initial", "Бат", "initials", List.of("Б", "Д")),
            r ->
                new Person(
                    r.get("initial", initial),
                    r.get("initials", Decoders.list(initial)),
                    r.extras()));

    assertNull(out.initial());
    assertEquals(List.of('Б', 'Д'), out.initials());
    assertEquals(
        List.of("initial (not a valid initial)"),
        out.extras().mismatches().stream().map(Mismatch::toString).toList());
  }

  @Test
  void nestedObjectsSeeTheirOwnFieldsAndPaths() {
    record Outer(Address address, Extras extras) {}
    Outer out =
        ResponseReader.decode(
            Map.of("address", Map.of("city", Map.of("x", "y"))),
            r -> new Outer(r.get("address", Decoders.object(Address::decode)), r.extras()));
    assertNotNull(out.address());
    assertNull(out.address().city());
    assertEquals(List.of("address.city"), paths(out.extras()));
  }

  @Test
  void mismatchNeverPrintsTheValue() {
    Extras extras = decode("age", "РД00000000").extras();
    Mismatch mismatch = extras.mismatches().get(0);
    for (String printed :
        List.of(mismatch.toString(), extras.toString(), String.valueOf(List.of(mismatch)))) {
      assertFalse(printed.contains("РД00000000"), printed);
      assertTrue(printed.contains("age"), printed);
    }
    assertEquals("РД00000000", mismatch.value(), "the caller must still be able to ask");
  }

  @Test
  void extrasNeverPrintTheRawResponse() {
    Extras extras = decode(Map.of("firstName", "РД00000000")).extras();
    assertFalse(extras.toString().contains("РД00000000"), extras.toString());
    assertThrows(UnsupportedOperationException.class, () -> extras.mismatches().clear());
  }
}
