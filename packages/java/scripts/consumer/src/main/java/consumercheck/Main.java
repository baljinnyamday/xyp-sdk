// A project that depends on the SDK from the outside. CI builds it once against the freshly
// installed jar and once against the version on Maven Central, and runs it on the class path and
// on the module path each time. Everything it does works without a network route to XYP.
package consumercheck;

import io.github.baljinnyamday.xyp.Origin;
import io.github.baljinnyamday.xyp.XypClient;
import io.github.baljinnyamday.xyp.XypConfigException;
import io.github.baljinnyamday.xyp.XypConnectionException;
import io.github.baljinnyamday.xyp.XypException;
import io.github.baljinnyamday.xyp.citizen.GetCitizenIDCardInfoParams;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;

public final class Main {
  // Port 1 refuses connections, so nothing here waits on a real network.
  private static final String UNREACHABLE = "http://127.0.0.1:1";

  private static final String SDK_MODULE = "io.github.baljinnyamday.xyp";

  private Main() {}

  /**
   * Runs the checks.
   *
   * @param args {@code class-path} or {@code module-path}: how the caller launched this, which is
   *     checked too, so a module-path run that silently fell back to the class path fails
   */
  public static void main(String[] args) {
    try {
      String path = checkLaunch(args.length == 1 ? args[0] : "");
      check();
      System.out.println("OK: the SDK installs, compiles and behaves from outside, on the " + path);
    } catch (CheckFailed failed) {
      System.err.println("FAILED: " + failed.getMessage());
      System.exit(1);
    }
  }

  private static String checkLaunch(String expected) {
    Module self = Main.class.getModule();
    Module sdk = XypClient.class.getModule();
    switch (expected) {
      case "class-path":
        if (self.isNamed() || sdk.isNamed()) {
          throw new CheckFailed("asked for the class path, but running in " + sdk);
        }
        return "class path";
      case "module-path":
        if (!self.isNamed() || !SDK_MODULE.equals(sdk.getName())) {
          throw new CheckFailed("asked for the module path, but the SDK is in " + sdk);
        }
        return "module path";
      default:
        throw new CheckFailed("usage: consumercheck.Main class-path|module-path");
    }
  }

  private static void check() {
    PrivateKey key = rsaKey();

    XypConfigException badUrl =
        expect(
            XypConfigException.class,
            "a base URL without a scheme",
            () ->
                XypClient.builder().accessToken("t").privateKey(key).baseUrl("xyp.gov.mn").build());
    if (XypException.originOf(badUrl) != Origin.CONFIG) {
      throw new CheckFailed("a base URL without a scheme has origin " + badUrl.origin());
    }

    try (XypClient xyp =
        XypClient.builder().accessToken("t").privateKey(key).baseUrl(UNREACHABLE).build()) {
      expect(
          XypConfigException.class,
          "an unknown operation",
          () -> xyp.call("WS999999_doesNotExist", null));

      // The generated group packages are separate exports, so this proves they ship and resolve.
      XypConnectionException unreachable =
          expect(
              XypConnectionException.class,
              "an unreachable XYP",
              () ->
                  xyp.citizen()
                      .getCitizenIDCardInfo(
                          GetCitizenIDCardInfoParams.builder().regnum("РД00000000").build()));
      if (XypException.originOf(unreachable) != Origin.NETWORK) {
        throw new CheckFailed("an unreachable XYP has origin " + unreachable.origin());
      }
    }
  }

  private static PrivateKey rsaKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair().getPrivate();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new CheckFailed("this JDK cannot generate an RSA key: " + e);
    }
  }

  private static <T extends XypException> T expect(Class<T> type, String what, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      if (type.isInstance(e)) {
        return type.cast(e);
      }
      throw new CheckFailed(what + " gave " + e + ", want " + type.getSimpleName(), e);
    }
    throw new CheckFailed(what + " succeeded, want " + type.getSimpleName());
  }

  private static final class CheckFailed extends RuntimeException {
    private static final long serialVersionUID = 1L;

    CheckFailed(String message) {
      super(message);
    }

    CheckFailed(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
