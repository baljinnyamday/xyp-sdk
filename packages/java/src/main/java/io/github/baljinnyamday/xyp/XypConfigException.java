package io.github.baljinnyamday.xyp;

/**
 * The client, the call or its parameters were set up incorrectly: a missing credential, a key that
 * is not RSA, an unknown operation, a value that cannot be sent. Retrying will not help; the
 * program has to change. {@link #origin()} is always {@link Origin#CONFIG}.
 */
public final class XypConfigException extends XypException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception without a cause.
   *
   * @param message what is wrong and, where possible, how to fix it
   */
  public XypConfigException(String message) {
    super(message, null);
  }

  /**
   * Creates an exception with a cause.
   *
   * @param message what is wrong and, where possible, how to fix it
   * @param cause the underlying failure
   */
  public XypConfigException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Always {@link Origin#CONFIG}.
   *
   * @return {@link Origin#CONFIG}
   */
  @Override
  public Origin origin() {
    return Origin.CONFIG;
  }
}
