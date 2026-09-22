package io.github.baljinnyamday.xyp;

/**
 * XYP answered with something that is not a valid service response: a SOAP fault, a gateway error
 * page, or XML the SDK cannot read. {@link #origin()} is always {@link Origin#XYP}.
 */
public final class XypResponseException extends XypException {

  private static final long serialVersionUID = 1L;

  /** The HTTP status, or 0 when the failure was not tied to one. */
  private final int statusCode;

  /**
   * Creates an exception.
   *
   * @param message what XYP answered with, free of citizen data
   * @param statusCode the HTTP status, or 0 when the failure was not tied to one
   */
  public XypResponseException(String message, int statusCode) {
    super(message, null);
    this.statusCode = statusCode;
  }

  /**
   * The HTTP status of the answer.
   *
   * @return the HTTP status, or 0 when the failure was not tied to one
   */
  public int statusCode() {
    return statusCode;
  }

  /**
   * Always {@link Origin#XYP}.
   *
   * @return {@link Origin#XYP}
   */
  @Override
  public Origin origin() {
    return Origin.XYP;
  }
}
