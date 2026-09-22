package io.github.baljinnyamday.xyp;

import java.util.Arrays;
import java.util.Objects;

/**
 * The approval of a person, sent in a request's {@code <auth>} block: the citizen whose data is
 * requested ({@link CallOptions.Builder#citizen(Auth)}) or the staff member making the request
 * ({@link CallOptions.Builder#operator(Auth)}). It mirrors the WSDL's {@code authorizationEntity}.
 *
 * <p>Prefer the factories, which set the matching {@link AuthType}:
 *
 * <pre>{@code
 * CallOptions options = CallOptions.builder()
 *     .citizen(Auth.otp("РД00000000", 123456))
 *     .operator(Auth.fingerprint("ОП11111111", scan))
 *     .build();
 * }</pre>
 *
 * Use {@link #builder()} only for a combination they do not cover. Instances are immutable. {@link
 * #toString()} prints the approval type only: the other fields are citizen data or secrets.
 */
public final class Auth {

  private final String regnum;
  private final String civilId;
  private final AuthType authType;
  private final int otp;
  private final byte[] fingerprint;
  private final String signature;
  private final String certFingerprint;
  private final String appAuthToken;
  private final String authAppName;

  private Auth(Builder builder) {
    this.regnum = builder.regnum;
    this.civilId = builder.civilId;
    this.authType = builder.authType;
    this.otp = builder.otp;
    this.fingerprint = copy(builder.fingerprint);
    this.signature = builder.signature;
    this.certFingerprint = builder.certFingerprint;
    this.appAuthToken = builder.appAuthToken;
    this.authAppName = builder.authAppName;
  }

  /**
   * Approval by a one-time code the person received by SMS.
   *
   * @param regnum the person's registration number
   * @param otp the code
   * @return the approval, of type {@link AuthType#SMS_OTP}
   */
  public static Auth otp(String regnum, int otp) {
    return builder()
        .regnum(Objects.requireNonNull(regnum, "regnum"))
        .otp(otp)
        .authType(AuthType.SMS_OTP)
        .build();
  }

  /**
   * Approval by a one-time code issued through the government SSO.
   *
   * @param regnum the person's registration number
   * @param otp the code
   * @return the approval, of type {@link AuthType#SSO_OTP}
   */
  public static Auth ssoOtp(String regnum, int otp) {
    return builder()
        .regnum(Objects.requireNonNull(regnum, "regnum"))
        .otp(otp)
        .authType(AuthType.SSO_OTP)
        .build();
  }

  /**
   * Approval by the person's digital signature and the fingerprint of the certificate that produced
   * it.
   *
   * @param regnum the person's registration number
   * @param signature the signature
   * @param certFingerprint the fingerprint of the signing certificate
   * @return the approval, of type {@link AuthType#DIGITAL_SIGNATURE}
   */
  public static Auth signature(String regnum, String signature, String certFingerprint) {
    return builder()
        .regnum(Objects.requireNonNull(regnum, "regnum"))
        .signature(Objects.requireNonNull(signature, "signature"))
        .certFingerprint(Objects.requireNonNull(certFingerprint, "certFingerprint"))
        .authType(AuthType.DIGITAL_SIGNATURE)
        .build();
  }

  /**
   * Approval by a freshly scanned fingerprint image.
   *
   * @param regnum the person's registration number
   * @param image the scanned image; copied, so later changes to the array do not reach the request
   * @return the approval, of type {@link AuthType#FINGERPRINT}
   */
  public static Auth fingerprint(String regnum, byte[] image) {
    return builder()
        .regnum(Objects.requireNonNull(regnum, "regnum"))
        .fingerprint(Objects.requireNonNull(image, "image"))
        .authType(AuthType.FINGERPRINT)
        .build();
  }

  /**
   * Approval through the ДАН mobile app.
   *
   * @param regnum the person's registration number
   * @return the approval, of type {@link AuthType#DAN_APP}
   */
  public static Auth danApp(String regnum) {
    return builder()
        .regnum(Objects.requireNonNull(regnum, "regnum"))
        .authType(AuthType.DAN_APP)
        .build();
  }

  /**
   * Starts an approval with every field unset, for a combination the factories do not cover.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The person's registration number.
   *
   * @return the registration number, or {@code null} when unset
   */
  public String regnum() {
    return regnum;
  }

  /**
   * The person's civil id.
   *
   * @return the civil id, or {@code null} when unset
   */
  public String civilId() {
    return civilId;
  }

  /**
   * How the person approved.
   *
   * @return the approval type, or {@code null} when unset (then {@code authType} is not sent)
   */
  public AuthType authType() {
    return authType;
  }

  /**
   * The one-time code. XYP always receives one: 0 stands for "no code".
   *
   * @return the code, 0 when unset
   */
  public int otp() {
    return otp;
  }

  /**
   * The scanned fingerprint image.
   *
   * @return a copy of the image, or {@code null} when unset
   */
  public byte[] fingerprint() {
    return copy(fingerprint);
  }

  /**
   * The person's digital signature.
   *
   * @return the signature, or {@code null} when unset
   */
  public String signature() {
    return signature;
  }

  /**
   * The fingerprint of the certificate that produced {@link #signature()}.
   *
   * @return the certificate fingerprint, or {@code null} when unset
   */
  public String certFingerprint() {
    return certFingerprint;
  }

  /**
   * The token of an approving app.
   *
   * @return the app token, or {@code null} when unset
   */
  public String appAuthToken() {
    return appAuthToken;
  }

  /**
   * The name of an approving app.
   *
   * @return the app name, or {@code null} when unset
   */
  public String authAppName() {
    return authAppName;
  }

  /**
   * Lists the fields in the order the WSDL declares them. Unset fields are omitted by the encoder,
   * with two exceptions handled here: {@code authType} is left out when it is unset (no approval
   * type was chosen), and {@code otp} is always sent, because some XYP WSDLs declare it as a
   * required int and the official samples send 0 when there is no code.
   */
  Params toWire() {
    return Params.builder()
        .add("appAuthToken", appAuthToken)
        .add("authAppName", authAppName)
        .add("authType", authType == null ? null : authType.code())
        .add("certFingerprint", certFingerprint)
        .add("civilId", civilId)
        .add("fingerprint", fingerprint)
        .add("otp", otp)
        .add("regnum", regnum)
        .add("signature", signature)
        .build();
  }

  /**
   * Compares every field, the fingerprint image by content.
   *
   * @param other the object to compare with
   * @return whether {@code other} is an {@code Auth} with the same fields
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof Auth that
        && otp == that.otp
        && authType == that.authType
        && Objects.equals(regnum, that.regnum)
        && Objects.equals(civilId, that.civilId)
        && Arrays.equals(fingerprint, that.fingerprint)
        && Objects.equals(signature, that.signature)
        && Objects.equals(certFingerprint, that.certFingerprint)
        && Objects.equals(appAuthToken, that.appAuthToken)
        && Objects.equals(authAppName, that.authAppName);
  }

  /**
   * A hash of every field, consistent with {@link #equals(Object)}.
   *
   * @return the hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(
                regnum,
                civilId,
                authType,
                otp,
                signature,
                certFingerprint,
                appAuthToken,
                authAppName)
            * 31
        + Arrays.hashCode(fingerprint);
  }

  /**
   * Names the approval type only. The registration number, the code, the signature and the
   * fingerprint are citizen data or secrets and stay out of logs.
   *
   * @return e.g. {@code "Auth[authType=SMS_OTP]"}
   */
  @Override
  public String toString() {
    return "Auth[authType=" + (authType == null ? "unset" : authType.name()) + "]";
  }

  private static byte[] copy(byte[] data) {
    return data == null ? null : data.clone();
  }

  /** Builds an {@link Auth} field by field. Every field starts unset. */
  public static final class Builder {

    private String regnum;
    private String civilId;
    private AuthType authType;
    private int otp;
    private byte[] fingerprint;
    private String signature;
    private String certFingerprint;
    private String appAuthToken;
    private String authAppName;

    private Builder() {}

    /**
     * Sets the person's registration number.
     *
     * @param regnum the registration number, or {@code null} to leave it out
     * @return this builder
     */
    public Builder regnum(String regnum) {
      this.regnum = regnum;
      return this;
    }

    /**
     * Sets the person's civil id.
     *
     * @param civilId the civil id, or {@code null} to leave it out
     * @return this builder
     */
    public Builder civilId(String civilId) {
      this.civilId = civilId;
      return this;
    }

    /**
     * Sets how the person approved.
     *
     * @param authType the approval type, or {@code null} to leave {@code authType} out
     * @return this builder
     */
    public Builder authType(AuthType authType) {
      this.authType = authType;
      return this;
    }

    /**
     * Sets the one-time code.
     *
     * @param otp the code; 0, the default, means "no code" and is still sent
     * @return this builder
     */
    public Builder otp(int otp) {
      this.otp = otp;
      return this;
    }

    /**
     * Sets the scanned fingerprint image.
     *
     * @param fingerprint the image, copied; or {@code null} to leave it out
     * @return this builder
     */
    public Builder fingerprint(byte[] fingerprint) {
      this.fingerprint = copy(fingerprint);
      return this;
    }

    /**
     * Sets the person's digital signature.
     *
     * @param signature the signature, or {@code null} to leave it out
     * @return this builder
     */
    public Builder signature(String signature) {
      this.signature = signature;
      return this;
    }

    /**
     * Sets the fingerprint of the certificate that produced the signature.
     *
     * @param certFingerprint the certificate fingerprint, or {@code null} to leave it out
     * @return this builder
     */
    public Builder certFingerprint(String certFingerprint) {
      this.certFingerprint = certFingerprint;
      return this;
    }

    /**
     * Sets the token of an approving app.
     *
     * @param appAuthToken the app token, or {@code null} to leave it out
     * @return this builder
     */
    public Builder appAuthToken(String appAuthToken) {
      this.appAuthToken = appAuthToken;
      return this;
    }

    /**
     * Sets the name of an approving app.
     *
     * @param authAppName the app name, or {@code null} to leave it out
     * @return this builder
     */
    public Builder authAppName(String authAppName) {
      this.authAppName = authAppName;
      return this;
    }

    /**
     * Builds the approval.
     *
     * @return a new immutable {@link Auth}
     */
    public Auth build() {
      return new Auth(this);
    }
  }
}
