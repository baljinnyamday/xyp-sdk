package io.github.baljinnyamday.xyp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** The shared test data in src/test/resources. */
final class Fixtures {

  private static List<JsonNode> envelopes;

  private Fixtures() {}

  /** The zeep-verified request envelopes, one per operation in spec/wsdl. */
  static synchronized List<JsonNode> envelopes() {
    if (envelopes == null) {
      try (InputStream in = open("/envelopes.json")) {
        List<JsonNode> read = new ArrayList<>();
        new ObjectMapper().readTree(in).forEach(read::add);
        envelopes = List.copyOf(read);
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }
    return envelopes;
  }

  static JsonNode envelope(String operation) {
    return envelopes().stream()
        .filter(fixture -> fixture.get("operation").asText().equals(operation))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no envelope fixture for " + operation));
  }

  /** The approvals a fixture was built with: an SMS code for the citizen, a fingerprint scan. */
  static CallOptions options(JsonNode fixture) {
    JsonNode auth = fixture.get("auth");
    if (auth.isNull()) {
      return CallOptions.none();
    }
    JsonNode citizen = auth.get("citizen");
    JsonNode operator = auth.get("operator");
    return CallOptions.builder()
        .citizen(Auth.otp(citizen.get("regnum").asText(), citizen.get("otp").asInt()))
        .operator(
            Auth.fingerprint(
                operator.get("regnum").asText(),
                Base64.getDecoder().decode(operator.get("fingerprintBase64").asText())))
        .build();
  }

  /** A file under src/test/resources, as text. */
  static String resource(String name) {
    try (InputStream in = open(name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private static InputStream open(String name) {
    InputStream in = Fixtures.class.getResourceAsStream(name);
    if (in == null) {
      throw new AssertionError("src/test/resources" + name + " is missing");
    }
    return in;
  }
}
