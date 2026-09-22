package io.github.baljinnyamday.xyp;

import static io.github.baljinnyamday.xyp.FakeXyp.soapResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SoapResponseTest {

  private static SoapResponse.Result parse(String payload) {
    return SoapResponse.parse(payload.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void readsTheDocumentedShape() {
    SoapResponse.Result result =
        parse(
            soapResponse(
                "<firstname>Бат &amp; &#1041;</firstname><empty/><gone "
                    + FakeXyp.XSI
                    + " xsi:nil=\"true\"><child>x</child></gone><one xsi:nil=\"1\" "
                    + FakeXyp.XSI
                    + "/><listData><year>2020</year></listData><listData><year>2021</year></listData>"
                    + "<spaced>\n   padded  </spaced><cdata><![CDATA[<b>]]></cdata>",
                0,
                "амжилттай"));

    assertEquals("4fd9aa5f-1984-4b61-b379-13c1bcbd29c7", result.requestId());
    assertEquals(0, result.resultCode());
    assertEquals("амжилттай", result.message());
    Map<String, Object> want = new LinkedHashMap<>();
    want.put("firstname", "Бат & Б");
    want.put("empty", null);
    want.put("gone", null);
    want.put("one", null);
    want.put("listData", List.of(Map.of("year", "2020"), Map.of("year", "2021")));
    want.put("spaced", "padded");
    want.put("cdata", "<b>");
    assertEquals(want, result.data());
    // Document order, which a Java map can keep.
    assertEquals(
        List.of("firstname", "empty", "gone", "one", "listData", "spaced", "cdata"),
        List.copyOf(((Map<?, ?>) result.data()).keySet()));
  }

  @Test
  void theTreeIsUnmodifiable() {
    Map<?, ?> data = (Map<?, ?>) parse(soapResponse("<a>1</a><l>1</l><l>2</l>", 0, "ok")).data();
    assertThrows(UnsupportedOperationException.class, data::clear);
    assertThrows(UnsupportedOperationException.class, () -> ((List<?>) data.get("l")).clear());
  }

  @Test
  void keepsNumericLookingTextAsText() {
    assertEquals(
        Map.of("regnum", "0012", "phone", "+97699"),
        parse(soapResponse("<regnum>0012</regnum><phone>+97699</phone>", 0, "ok")).data());
  }

  @Test
  void givesNullDataForAnEmptyResponseElement() {
    SoapResponse.Result result = parse(soapResponse("", 1, "олдсонгүй"));
    assertNull(result.data());
    assertEquals(1, result.resultCode());
  }

  @Test
  void readsTheProductionShapeWithTheEchoedRequest() throws IOException {
    // JAX-WS (Metro) echoes the request, auth included, before <response>, puts resultCode
    // after it and prefixes the envelope with S:. All values here are made up.
    byte[] payload = resource("ws100101-production-shape.xml");

    SoapResponse.Result result = SoapResponse.parse(payload);

    assertEquals("00000000-0000-4000-8000-000000000000", result.requestId());
    assertEquals(0, result.resultCode());
    assertEquals("амжилттай", result.message());
    Map<?, ?> data = assertInstanceOf(Map.class, result.data());
    assertEquals("Тест", data.get("firstname"));
    assertEquals("ТЕ00000000", data.get("regnum"));
    assertNull(data.get("addressStreetName"));
    assertFalse(data.containsKey("auth"), "the echoed request must not leak into the data");
    assertFalse(data.containsKey("resultCode"));

    record IdCard(
        String firstname, Long aimagCityCode, XypDate birthDate, byte[] image, Extras extras) {
      static IdCard decode(ResponseReader r) {
        return new IdCard(
            r.get("firstname", Decoders.STRING),
            r.get("aimagCityCode", Decoders.INT),
            r.get("birthDate", Decoders.DATE),
            r.get("image", Decoders.BYTES),
            r.extras());
      }
    }
    IdCard card = ResponseReader.decode(result.data(), IdCard::decode);
    assertEquals("Тест", card.firstname());
    assertEquals(11L, card.aimagCityCode());
    assertEquals("1990-01-01 00:00:00.0", card.birthDate().raw());
    assertEquals(1990, card.birthDate().time().getYear());
    assertTrue(Arrays.equals(new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, card.image()));
    assertEquals(List.of(), card.extras().mismatches());
    assertEquals(result.data(), card.extras().raw());
  }

  @Test
  void aJaxWsFaultWithHttp500KeepsItsText() throws IOException {
    XypResponseException error =
        assertThrows(
            XypResponseException.class,
            () -> SoapResponse.unwrap(500, resource("jaxws-fault.xml")));

    assertEquals(500, error.statusCode());
    assertEquals(
        "XYP answered with HTTP 500: XYP returned a SOAP fault: Unmarshalling Error: unexpected "
            + "element (uri:\"\", local:\"regnumm\"). Expected elements are <{}regnum>",
        error.getMessage());
    assertEquals(Origin.XYP, error.origin());
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      textBlock =
          """
          fault | <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault><faultcode>soap:Client</faultcode><faultstring>Unmarshalling Error</faultstring></soap:Fault></soap:Body></soap:Envelope> | XYP returned a SOAP fault: Unmarshalling Error
          empty fault | <Envelope><Body><Fault/></Body></Envelope> | XYP returned a SOAP fault: unknown SOAP fault
          gateway page | <html>gateway timeout | XYP returned a response that is not valid XML
          no return element | <a/> | XYP response has no <return> element
          an empty return element | <a><return/></a> | XYP response has no numeric <resultCode>
          plain text | Bad Gateway | XYP response has no <return> element
          entity bomb | <?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><x>&a;</x> | XYP returned a response that is not valid XML
          external entity | <?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><return><resultCode>0</resultCode><response>&e;</response></return> | XYP returned a response that is not valid XML
          a DTD without entities | <!DOCTYPE return><return><resultCode>0</resultCode></return> | XYP returned a response that is not valid XML
          an undeclared entity | <return><resultCode>0</resultCode><response>&x;</response></return> | XYP returned a response that is not valid XML
          """)
  void turnsFaultsGarbageAndDtdsIntoResponseErrors(String name, String payload, String want) {
    XypResponseException error = assertThrows(XypResponseException.class, () -> parse(payload));
    assertEquals(want, error.getMessage());
    assertEquals(0, error.statusCode());
    assertEquals(Origin.XYP, XypException.originOf(error));
  }

  @Test
  void anEmptyBodyHasNoReturnElement() {
    XypResponseException error =
        assertThrows(XypResponseException.class, () -> SoapResponse.unwrap(502, new byte[0]));
    assertEquals(
        "XYP answered with HTTP 502: XYP response has no <return> element", error.getMessage());
    assertEquals(502, error.statusCode());
  }

  @Test
  void requiresANumericResultCode() {
    parse(soapResponse("", 0, "ok"));
    for (String code : List.of("ok", "1.0", "99999999999", "")) {
      String broken =
          soapResponse("", 0, "ok")
              .replace("<resultCode>0</resultCode>", "<resultCode>" + code + "</resultCode>");
      XypResponseException error = assertThrows(XypResponseException.class, () -> parse(broken));
      assertEquals("XYP response has no numeric <resultCode>", error.getMessage(), code);
    }
    assertEquals(-5, parse(soapResponse("", -5, "no")).resultCode());
  }

  @Test
  void aNonZeroResultCodeIsAnApiErrorWhateverTheStatus() {
    byte[] payload = soapResponse("", 3, "буруу").getBytes(StandardCharsets.UTF_8);
    for (int status : new int[] {200, 500}) {
      XypApiException error =
          assertThrows(XypApiException.class, () -> SoapResponse.unwrap(status, payload));
      assertEquals(3, error.resultCode());
      assertEquals(XypApiException.Reason.INVALID_REQUEST, error.reason());
    }
  }

  @Test
  void aSuccessWithAnErrorStatusStillReturnsItsData() {
    byte[] payload = soapResponse("<a>1</a>", 0, "ok").getBytes(StandardCharsets.UTF_8);
    assertEquals(Map.of("a", "1"), SoapResponse.unwrap(500, payload));
  }

  @Test
  void readsANonUtf8DocumentByItsDeclaration() {
    byte[] payload =
        ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>"
                + "<return><resultCode>0</resultCode><response><name>café</name></response></return>")
            .getBytes(StandardCharsets.ISO_8859_1);
    assertEquals(Map.of("name", "café"), SoapResponse.parse(payload).data());
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream in = SoapResponseTest.class.getResourceAsStream("/responses/" + name)) {
      if (in == null) {
        throw new AssertionError("src/test/resources/responses/" + name + " is missing");
      }
      return in.readAllBytes();
    }
  }
}
