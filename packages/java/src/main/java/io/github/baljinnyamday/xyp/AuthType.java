package io.github.baljinnyamday.xyp;

/** How a person proved that they approve of a request. */
public enum AuthType {
  /** A one-time code the person received by SMS. */
  SMS_OTP(1),
  /** The person's own digital signature. */
  DIGITAL_SIGNATURE(2),
  /** A freshly scanned fingerprint image. */
  FINGERPRINT(3),
  /** A one-time code issued through the government SSO. */
  SSO_OTP(4),
  /** Approval through the ДАН mobile app. */
  DAN_APP(5);

  private final int code;

  AuthType(int code) {
    this.code = code;
  }

  /**
   * The number XYP uses for this approval type on the wire.
   *
   * @return the {@code authType} value, 1 to 5
   */
  public int code() {
    return code;
  }
}
