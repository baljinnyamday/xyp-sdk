package io.github.baljinnyamday.xyp;

/**
 * XYP could not be reached at all: the connection was refused, DNS or the hosts entry failed, the
 * TLS handshake failed, the request timed out or the calling thread was interrupted. {@link
 * #getCause()} keeps the underlying exception. {@link #origin()} is always {@link Origin#NETWORK}.
 */
public final class XypConnectionException extends XypException {

  private static final long serialVersionUID = 1L;

  /** Whether the request ran out of time. */
  private final boolean timeout;

  /**
   * Creates an exception.
   *
   * @param message what failed, free of secrets
   * @param cause the underlying failure
   * @param timeout whether the request ran out of time
   */
  public XypConnectionException(String message, Throwable cause, boolean timeout) {
    super(message, cause);
    this.timeout = timeout;
  }

  /**
   * Reports whether the request ran out of time, which makes it worth retrying, unlike a refused
   * connection or a failed TLS handshake.
   *
   * @return {@code true} when the request timed out
   */
  public boolean isTimeout() {
    return timeout;
  }

  /**
   * Always {@link Origin#NETWORK}.
   *
   * @return {@link Origin#NETWORK}
   */
  @Override
  public Origin origin() {
    return Origin.NETWORK;
  }
}
