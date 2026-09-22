package io.github.baljinnyamday.xyp;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads the {@code <return>} element every XYP service answers with:
 *
 * <pre>{@code
 * <return> <requestId/> <resultCode/> <resultMessage/> <response>...</response> </return>
 * }</pre>
 *
 * and turns a failed exchange into the matching exception. Pure functions, no I/O.
 */
final class SoapResponse {

  /** The only result code that is not an error. */
  private static final int RESULT_CODE_OK = 0;

  /** At and above this status a reply stops being a service response. */
  static final int HTTP_ERROR_STATUS = 400;

  private static final Pattern INTEGER_TEXT = Pattern.compile("^-?\\d+$");

  private SoapResponse() {}

  /** The parts of {@code <return>}. {@code data} is the raw {@code <response>} tree. */
  record Result(String requestId, int resultCode, String message, Object data) {}

  /**
   * Returns the {@code <response>} tree of a successful exchange.
   *
   * @throws XypResponseException when the reply is not a service response
   * @throws XypApiException when XYP answered with a non-zero result code
   */
  static Object unwrap(int status, byte[] body) {
    Result result;
    try {
      result = parse(body);
    } catch (XypResponseException notAResponse) {
      if (status >= HTTP_ERROR_STATUS) {
        // JAX-WS sends SOAP faults with HTTP 500: keep the fault text, it is the only
        // diagnostic the caller gets, and add the status to it.
        throw new XypResponseException(
            "XYP answered with HTTP " + status + ": " + notAResponse.getMessage(), status);
      }
      throw notAResponse;
    }
    if (result.resultCode() != RESULT_CODE_OK) {
      throw new XypApiException(result.resultCode(), result.message(), result.requestId());
    }
    return result.data();
  }

  static Result parse(byte[] payload) {
    Map<String, Object> document = XmlTree.parse(payload);
    Found fault = findElement(document, "Fault");
    if (fault != null) {
      String reason = textOf(childOf(fault.value(), "faultstring"));
      if (reason.isEmpty()) {
        reason = "unknown SOAP fault";
      }
      throw new XypResponseException("XYP returned a SOAP fault: " + reason, 0);
    }
    Found found = findElement(document, "return");
    if (found == null) {
      throw new XypResponseException("XYP response has no <return> element", 0);
    }
    Object result = found.value();
    String code = textOf(childOf(result, "resultCode"));
    if (!INTEGER_TEXT.matcher(code).matches()) {
      throw noNumericResultCode();
    }
    int resultCode;
    try {
      resultCode = Integer.parseInt(code);
    } catch (NumberFormatException outOfRange) {
      throw noNumericResultCode();
    }
    return new Result(
        textOf(childOf(result, "requestId")),
        resultCode,
        textOf(childOf(result, "resultMessage")),
        childOf(result, "response"));
  }

  private static XypResponseException noNumericResultCode() {
    return new XypResponseException("XYP response has no numeric <resultCode>", 0);
  }

  /** An element that was found; its value may be {@code null} for an empty element. */
  private record Found(Object value) {}

  /**
   * A depth-first search for the first element called {@code name}, or {@code null} when there is
   * none. Keys are visited in sorted order, as the Go SDK does, so both find the same element when
   * a response holds more than one.
   */
  private static Found findElement(Object node, String name) {
    if (node instanceof Map<?, ?> map) {
      if (map.containsKey(name)) {
        return new Found(map.get(name));
      }
      for (Object key : new TreeSet<>(map.keySet())) {
        Found found = findElement(map.get(key), name);
        if (found != null) {
          return found;
        }
      }
    } else if (node instanceof List<?> list) {
      for (Object item : list) {
        Found found = findElement(item, name);
        if (found != null) {
          return found;
        }
      }
    }
    return null;
  }

  private static Object childOf(Object node, String name) {
    return node instanceof Map<?, ?> map ? map.get(name) : null;
  }

  private static String textOf(Object node) {
    return node instanceof String text ? text : "";
  }
}
