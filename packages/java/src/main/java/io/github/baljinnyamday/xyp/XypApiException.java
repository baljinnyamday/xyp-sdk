package io.github.baljinnyamday.xyp;

import java.util.Objects;

/**
 * XYP processed the request and answered with a non-zero {@code resultCode}. {@link #reason()}
 * groups the documented codes the way XYP does; the codes are listed at <a
 * href="https://developer.xyp.gov.mn/docs/result-code">developer.xyp.gov.mn/docs/result-code</a>.
 *
 * <pre>{@code
 * } catch (XypApiException e) {
 *   if (e.reason() == XypApiException.Reason.NOT_FOUND) return Optional.empty();
 *   log.warn("XYP said {}, request {}", e.getMessage(), e.requestId());
 * }
 * }</pre>
 *
 * <p>{@link #getMessage()} is {@code "[code] message"}, as XYP wrote it. {@link #origin()} is
 * always {@link Origin#XYP}.
 */
public final class XypApiException extends XypException {

  private static final long serialVersionUID = 1L;

  /** The documented result codes, grouped the way XYP groups them. */
  public enum Reason {
    /** Code 1: the data provider has no record for this request. */
    NOT_FOUND(1),
    /** Code 2: XYP internal error. */
    INTERNAL(2),
    /** Code 3: missing input, wrong endpoint, or a bad accessToken/timeStamp/signature header. */
    INVALID_REQUEST(3),
    /** Codes 200-202: the auth block (citizen and/or operator approval) is missing. */
    AUTH_REQUIRED(200, 201, 202),
    /** Codes 203 and 501: your access token may not call this service. */
    ACCESS_DENIED(203, 501),
    /** Codes 301-304: fingerprint not registered, not matched, or matching failed. */
    FINGERPRINT(301, 302, 303, 304),
    /** Codes 401-402: the citizen must visit the registry, or is not the owner. */
    CITIZEN_DATA(401, 402),
    /** Codes 601-605: the citizen's digital signature or certificate was rejected. */
    SIGNATURE(601, 602, 603, 604, 605),
    /** Codes 801-802: the data provider's database is unreachable or timed out. */
    PROVIDER(801, 802),
    /** A code the list above does not cover. */
    OTHER;

    private final int[] codes;

    Reason(int... codes) {
      this.codes = codes;
    }

    /**
     * Finds the reason for a result code.
     *
     * @param resultCode the {@code resultCode} XYP answered with
     * @return the matching reason, or {@link #OTHER} for a code that is not documented
     */
    public static Reason of(int resultCode) {
      for (Reason reason : values()) {
        for (int code : reason.codes) {
          if (code == resultCode) {
            return reason;
          }
        }
      }
      return OTHER;
    }
  }

  /** The {@code resultCode} XYP answered with; never 0. */
  private final int resultCode;

  /** The {@code resultMessage} XYP answered with. */
  private final String resultMessage;

  /** The {@code requestId} XYP answered with. */
  private final String requestId;

  /**
   * Creates an exception.
   *
   * @param resultCode the {@code resultCode} XYP answered with
   * @param resultMessage the {@code resultMessage} XYP answered with; {@code null} becomes ""
   * @param requestId the {@code requestId} XYP answered with; {@code null} becomes ""
   */
  public XypApiException(int resultCode, String resultMessage, String requestId) {
    super("[" + resultCode + "] " + Objects.requireNonNullElse(resultMessage, ""), null);
    this.resultCode = resultCode;
    this.resultMessage = Objects.requireNonNullElse(resultMessage, "");
    this.requestId = Objects.requireNonNullElse(requestId, "");
  }

  /**
   * The {@code resultCode} XYP answered with.
   *
   * @return the result code, never 0
   */
  public int resultCode() {
    return resultCode;
  }

  /**
   * The {@code resultMessage} XYP answered with, usually in Mongolian.
   *
   * @return the result message, possibly empty
   */
  public String resultMessage() {
    return resultMessage;
  }

  /**
   * The {@code requestId} XYP answered with. It is what XYP support will ask you for.
   *
   * @return the request id, possibly empty
   */
  public String requestId() {
    return requestId;
  }

  /**
   * The documented group of {@link #resultCode()}.
   *
   * @return the reason, {@link Reason#OTHER} for an undocumented code
   */
  public Reason reason() {
    return Reason.of(resultCode);
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
