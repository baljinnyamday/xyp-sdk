package io.github.baljinnyamday.xyp;

/**
 * SOAP 1.1 document/literal request envelopes for XYP. Pure functions, no I/O. Only the operation
 * element is namespaced:
 *
 * <pre>{@code
 * <soap:Envelope><soap:Body><tns:OP>
 *   <request> <auth><citizen/><operator/></auth> ...fields... </request>
 * </tns:OP></soap:Body></soap:Envelope>
 * }</pre>
 */
final class Envelope {

  private static final String SOAP_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
  private static final String XML_DECLARATION = "<?xml version='1.0' encoding='utf-8'?>\n";

  private Envelope() {}

  /**
   * Renders the content of {@code <request>}: the approvals, then the fields. Everything the caller
   * supplied is checked here, before any network I/O.
   */
  static String requestBody(String operation, Object params, Auth citizen, Auth operator) {
    Encoder.checkName(operation);
    return encodeAuth(citizen, operator) + Encoder.encodeFields(params);
  }

  /** Wraps a request body rendered by {@link #requestBody} into the full envelope. */
  static String wrap(String operation, String namespace, String requestBody) {
    String request = Encoder.wrapElement("request", requestBody);
    String body =
        Encoder.wrapElement("soap:Body", Encoder.wrapElement("tns:" + operation, request));
    String attributes =
        "xmlns:soap=\""
            + SOAP_NAMESPACE
            + "\" xmlns:tns=\""
            + Encoder.escapeAttribute(namespace)
            + "\"";
    return XML_DECLARATION + "<soap:Envelope " + attributes + ">" + body + "</soap:Envelope>";
  }

  static String build(
      String operation, String namespace, Object params, Auth citizen, Auth operator) {
    return wrap(operation, namespace, requestBody(operation, params, citizen, operator));
  }

  /**
   * Puts {@code <auth>} first: every request type extends {@code serviceRequest}, so that is its
   * position in the schema and where zeep (the known-working client) puts it.
   */
  private static String encodeAuth(Auth citizen, Auth operator) {
    if (citizen == null && operator == null) {
      return "";
    }
    Params.Builder auth = Params.builder();
    if (citizen != null) {
      auth.add("citizen", citizen.toWire());
    }
    if (operator != null) {
      auth.add("operator", operator.toWire());
    }
    return Encoder.wrapElement("auth", Encoder.encodeParams(auth.build()));
  }
}
