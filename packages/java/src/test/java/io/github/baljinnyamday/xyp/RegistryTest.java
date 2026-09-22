package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class RegistryTest {

  @Test
  void coversEveryServiceInTheSpec() throws IOException {
    Path spec = Path.of("..", "..", "spec", "services.json");
    // A downloaded source jar holds only this package's own files.
    assumeTrue(Files.exists(spec), spec + " is not part of this checkout");
    Set<String> want = new TreeSet<>();
    for (JsonNode service : new ObjectMapper().readTree(spec.toFile())) {
      want.add(service.get("operationName").asText());
    }

    assertEquals(want, new TreeSet<>(Registry.OPERATION_ENDPOINTS.keySet()));
  }

  @Test
  void knownNamespacesAreSeededFromTheCheckedInWsdls() {
    assertEquals("http://citizen.xyp.gov.mn/", Registry.KNOWN_NAMESPACES.get("citizen-1.5.0"));
    assertEquals("http://meta.xyp.gov.mn/", Registry.KNOWN_NAMESPACES.get("meta-1.5.0"));
    assertFalse(
        Registry.KNOWN_NAMESPACES.containsKey("insurance-1.5.0"),
        "insurance-1.5.0 has no WSDL in the repository, so it must be learned at runtime");
    assertEquals(
        "citizen-1.5.0", Registry.OPERATION_ENDPOINTS.get("WS100101_getCitizenIDCardInfo"));
  }
}
