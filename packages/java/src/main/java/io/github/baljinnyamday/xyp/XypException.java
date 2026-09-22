package io.github.baljinnyamday.xyp;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * The base of every exception this SDK throws. All of them are unchecked, and each says whose side
 * the problem is on through {@link #origin()}:
 *
 * <pre>{@code
 * try {
 *   card = xyp.citizen().getCitizenIDCardInfo(params);
 * } catch (XypApiException e) {
 *   if (e.reason() == XypApiException.Reason.NOT_FOUND) return null;
 *   throw e;
 * } catch (XypConnectionException e) {
 *   if (e.isTimeout()) retryLater();
 *   throw e;
 * }
 * }</pre>
 *
 * <p>The messages never contain the access token, the private key, a one-time code or any value
 * from a request or a response.
 */
public abstract sealed class XypException extends RuntimeException
    permits XypConfigException, XypConnectionException, XypResponseException, XypApiException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception.
   *
   * @param message what went wrong, free of secrets and citizen data
   * @param cause the underlying failure, or {@code null}
   */
  XypException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Says whose side this problem is on.
   *
   * @return the origin, never {@code null}
   */
  public abstract Origin origin();

  /**
   * Says whose side any throwable is on. The cause chain is searched for an {@code XypException},
   * so an SDK exception wrapped by your own code keeps its origin. Anything else is {@link
   * Origin#SDK}: it reached the caller through this SDK, so this SDK owns it.
   *
   * @param throwable the throwable to classify, may be {@code null}
   * @return the origin of the first {@code XypException} in the cause chain, or {@link Origin#SDK}
   */
  public static Origin originOf(Throwable throwable) {
    // A cause chain can be cyclic; visiting each throwable once keeps this finite.
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable current = throwable;
        current != null && seen.add(current);
        current = current.getCause()) {
      if (current instanceof XypException xypException) {
        return xypException.origin();
      }
    }
    return Origin.SDK;
  }
}
