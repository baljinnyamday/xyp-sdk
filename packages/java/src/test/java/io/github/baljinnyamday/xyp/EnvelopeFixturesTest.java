package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The fixtures are the Python SDK's output, which is verified against zeep (the SOAP library the
 * known-working XYP clients use) for every operation in spec/wsdl. Same input, same bytes.
 */
class EnvelopeFixturesTest {

  /**
   * The WSDLs we hold cover this many operations; a fixture file with fewer than that is a sign the
   * export went wrong.
   */
  private static final int MINIMUM_FIXTURES = 160;

  @Test
  void theFixtureFileCoversEveryOperationInTheWsdls() throws IOException {
    assertTrue(
        fixtures().size() > MINIMUM_FIXTURES,
        "only " + fixtures().size() + " fixtures, want more than " + MINIMUM_FIXTURES);
  }

  @TestFactory
  Stream<DynamicTest> envelopesAreByteIdenticalToTheZeepVerifiedFixtures() throws IOException {
    return fixtures().stream()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture.get("operation").asText(),
                    () -> {
                      Auth citizen = null;
                      Auth operator = null;
                      JsonNode auth = fixture.get("auth");
                      if (!auth.isNull()) {
                        citizen = citizenAuth(auth.get("citizen"));
                        operator = operatorAuth(auth.get("operator"));
                      }
                      String envelope =
                          Envelope.build(
                              fixture.get("operation").asText(),
                              fixture.get("namespace").asText(),
                              params(fixture),
                              citizen,
                              operator);
                      assertEquals(fixture.get("envelope").asText(), envelope);
                    }));
  }

  /** Rebuilds the request in schema order, which the fixture keeps apart from the values. */
  private static Params params(JsonNode fixture) {
    Set<String> dateFields = new HashSet<>();
    fixture.get("dateFields").forEach(name -> dateFields.add(name.asText()));
    Params.Builder params = Params.builder();
    for (JsonNode nameNode : fixture.get("paramOrder")) {
      String name = nameNode.asText();
      JsonNode value = fixture.get("params").get(name);
      if (value == null) {
        throw new AssertionError("paramOrder names " + name + ", which params does not hold");
      }
      params.add(
          name, dateFields.contains(name) ? OffsetDateTime.parse(value.asText()) : value(value));
    }
    return params.build();
  }

  private static Object value(JsonNode value) {
    if (value.isBoolean()) {
      return value.booleanValue();
    }
    if (value.isIntegralNumber()) {
      // A long, so it is written without a ".0".
      return value.longValue();
    }
    if (value.isTextual()) {
      return value.textValue();
    }
    throw new AssertionError("unexpected fixture value " + value.getNodeType());
  }

  private static Auth citizenAuth(JsonNode citizen) {
    Auth auth = Auth.otp(citizen.get("regnum").asText(), citizen.get("otp").asInt());
    assertEquals(citizen.get("authType").asInt(), auth.authType().code());
    return auth;
  }

  private static Auth operatorAuth(JsonNode operator) {
    byte[] image = Base64.getDecoder().decode(operator.get("fingerprintBase64").asText());
    Auth auth = Auth.fingerprint(operator.get("regnum").asText(), image);
    assertEquals(operator.get("authType").asInt(), auth.authType().code());
    return auth;
  }

  private static List<JsonNode> fixtures() throws IOException {
    try (InputStream in = EnvelopeFixturesTest.class.getResourceAsStream("/envelopes.json")) {
      if (in == null) {
        throw new AssertionError("src/test/resources/envelopes.json is missing");
      }
      List<JsonNode> fixtures = new ArrayList<>();
      new ObjectMapper().readTree(in).forEach(fixtures::add);
      return fixtures;
    }
  }
}
