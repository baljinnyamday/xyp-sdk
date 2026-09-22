// First-contact check against the real XYP. Run it from a machine on the VPN, from packages/java:
//
//   export XYP_ACCESS_TOKEN=...            # never commit these
//   export XYP_PRIVATE_KEY=/path/to/private.key
//   ./mvnw -q package -DskipTests
//   java -cp target/xyp-sdk-0.1.0-alpha.1.jar examples/Smoke.java
//
// Java's single-file source launcher compiles and runs this file; nothing else is needed. The jar
// from Maven Central works the same way.
//
// listAccess needs no citizen data, so it is the safest first call. It proves four things at once:
// TLS against the bundled national CAs, the request signature, the SOAP envelope, and response
// parsing.

import io.github.baljinnyamday.xyp.XypClient;
import io.github.baljinnyamday.xyp.XypException;
import io.github.baljinnyamday.xyp.meta.ListAccessResponse;
import java.util.Locale;

public final class Smoke {

  private Smoke() {}

  public static void main(String[] args) {
    try (XypClient xyp = XypClient.builder().build()) {
      ListAccessResponse access = xyp.meta().listAccess();
      // Deliberately not the whole response: listAccess echoes your access token and certificate,
      // and this output is what people paste into bug reports.
      boolean registered = Boolean.TRUE.equals(access.registered());
      System.out.printf(
          "OK: organisation=\"%s\" registered=%b approved_services=%d model_mismatches=%d%n",
          access.orgTitle(),
          registered,
          access.approvedServices().size(),
          access.extras().mismatches().size());
    } catch (RuntimeException e) {
      String origin = XypException.originOf(e).name().toLowerCase(Locale.ROOT);
      System.err.printf("FAILED (%s): %s%n", origin, e.getMessage());
      System.exit(1);
    }
  }
}
