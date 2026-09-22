package io.github.baljinnyamday.xyp;

import java.util.Optional;

/**
 * Tunes one call: the approvals it carries and, for a service newer than this SDK version, the
 * endpoint that serves it. The generated service methods pass it straight through.
 *
 * <pre>{@code
 * CallOptions options = CallOptions.builder()
 *     .citizen(Auth.otp("РД00000000", 123456))
 *     .build();
 * }</pre>
 *
 * Instances are immutable. {@link #toString()} prints no citizen data.
 */
public final class CallOptions {

  private static final CallOptions NONE = new CallOptions(null, null, null);

  private final Auth citizen;
  private final Auth operator;
  private final String endpoint;

  private CallOptions(Auth citizen, Auth operator, String endpoint) {
    this.citizen = citizen;
    this.operator = operator;
    this.endpoint = endpoint;
  }

  /**
   * No approvals and the endpoint from the SDK's registry.
   *
   * @return the empty options
   */
  public static CallOptions none() {
    return NONE;
  }

  /**
   * Starts a set of options with nothing set.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The approval of the citizen whose data is requested.
   *
   * @return the citizen's approval, if one was set
   */
  public Optional<Auth> citizen() {
    return Optional.ofNullable(citizen);
  }

  /**
   * The approval of the staff member making the request.
   *
   * @return the operator's approval, if one was set
   */
  public Optional<Auth> operator() {
    return Optional.ofNullable(operator);
  }

  /**
   * The endpoint to call instead of the one in the SDK's registry.
   *
   * @return the endpoint name such as {@code "citizen-1.5.0"}, if one was set
   */
  public Optional<String> endpoint() {
    return Optional.ofNullable(endpoint);
  }

  /**
   * Says what is set, without any citizen data.
   *
   * @return e.g. {@code "CallOptions[citizen=Auth[authType=SMS_OTP], operator=none,
   *     endpoint=registry]"}
   */
  @Override
  public String toString() {
    return "CallOptions[citizen="
        + (citizen == null ? "none" : citizen)
        + ", operator="
        + (operator == null ? "none" : operator)
        + ", endpoint="
        + (endpoint == null ? "registry" : endpoint)
        + "]";
  }

  /** Builds {@link CallOptions}. Nothing is set until a method sets it. */
  public static final class Builder {

    private Auth citizen;
    private Auth operator;
    private String endpoint;

    private Builder() {}

    /**
     * Attaches the approval of the citizen whose data is requested.
     *
     * @param citizen the approval, or {@code null} for none
     * @return this builder
     */
    public Builder citizen(Auth citizen) {
      this.citizen = citizen;
      return this;
    }

    /**
     * Attaches the approval of the staff member making the request.
     *
     * @param operator the approval, or {@code null} for none
     * @return this builder
     */
    public Builder operator(Auth operator) {
      this.operator = operator;
      return this;
    }

    /**
     * Names the endpoint to call, e.g. {@code "citizen-1.5.0"}, for a service that is newer than
     * this SDK version. It must be one URL path segment: letters, digits, {@code .}, {@code _} and
     * {@code -}, starting with a letter or digit.
     *
     * @param endpoint the endpoint name, or {@code null} or {@code ""} for the SDK's registry
     * @return this builder
     */
    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint == null || endpoint.isEmpty() ? null : endpoint;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return new immutable options
     */
    public CallOptions build() {
      return new CallOptions(citizen, operator, endpoint);
    }
  }
}
