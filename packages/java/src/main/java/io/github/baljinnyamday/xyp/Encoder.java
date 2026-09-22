package io.github.baljinnyamday.xyp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.regex.Pattern;

/**
 * Renders request parameters as XML, byte for byte the way zeep (the known-working client) and the
 * other XYP SDKs do. See the package documentation for the accepted shapes and the encoding of
 * every value.
 *
 * <p>No XML library is used for requests: they escape more characters (and write a newline as
 * {@code &#xA;}), which would no longer match the bytes zeep sends.
 */
final class Encoder {

  /**
   * What may become an element name. Names reach the envelope unescaped (an element name has no
   * escaped form), so a name taken from a map key or a raw call is checked instead: anything else
   * could rewrite the request.
   */
  private static final Pattern XML_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]*$");

  private Encoder() {}

  static void checkName(String name) {
    if (name == null || !XML_NAME.matcher(name).matches()) {
      throw new XypConfigException(
          quote(String.valueOf(name))
              + " cannot be sent to XYP: it is not a valid XML element name");
    }
  }

  /**
   * Renders the body of a {@code <request>} element from the top-level params: {@code null}, {@link
   * Params}, a {@link RequestParams} or a {@code Map} with {@code String} keys.
   */
  static String encodeFields(Object params) {
    if (params == null) {
      return "";
    }
    if (params instanceof RequestParams requestParams) {
      return encodeParams(requestParams.toParams());
    }
    if (params instanceof Map<?, ?> map) {
      return encodeMap(map);
    }
    throw new XypConfigException(
        "params must be null, Params, a RequestParams or a Map with String keys, got "
            + params.getClass().getName());
  }

  static String encodeParams(Params params) {
    if (params == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, Object> entry : params.entries()) {
      encodeElement(out, entry.getKey(), entry.getValue());
    }
    return out.toString();
  }

  /**
   * A {@code LinkedHashMap} or a {@code SortedMap} is written in its iteration order. Any other map
   * has its keys sorted: its iteration order is unspecified (and for {@code Map.of} it changes
   * between runs), and the same input has to give the same bytes.
   */
  private static String encodeMap(Map<?, ?> map) {
    Map<String, Object> ordered = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String name)) {
        throw new XypConfigException(
            "map keys must be strings, got "
                + (entry.getKey() == null ? "null" : entry.getKey().getClass().getName()));
      }
      ordered.put(name, entry.getValue());
    }
    List<Map.Entry<String, Object>> entries = new ArrayList<>(ordered.entrySet());
    if (!(map instanceof LinkedHashMap<?, ?> || map instanceof SortedMap<?, ?>)) {
      entries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
    }
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, Object> entry : entries) {
      encodeElement(out, entry.getKey(), entry.getValue());
    }
    return out.toString();
  }

  /** Appends one element, or nothing when the value is unset. */
  static void encodeElement(StringBuilder out, String name, Object value) {
    checkName(name);
    if (value == null) {
      return;
    }
    if (value instanceof Optional<?> optional) {
      if (optional.isPresent()) {
        encodeElement(out, name, optional.get());
      }
      return;
    }
    if (value instanceof CharSequence text) {
      // An empty string is how a generated or hand-built request says "unset", so it is left
      // out rather than sent as an empty element.
      if (text.length() > 0) {
        wrap(out, name, escapeText(text.toString()));
      }
      return;
    }
    if (value instanceof Collection<?> items) {
      // XML writes a list by repeating the element once per item.
      for (Object item : items) {
        encodeElement(out, name, item);
      }
      return;
    }
    if (value instanceof Object[] items) {
      for (Object item : items) {
        encodeElement(out, name, item);
      }
      return;
    }
    if (value instanceof RequestParams nested) {
      wrap(out, name, encodeParams(nested.toParams()));
      return;
    }
    if (value instanceof Map<?, ?> nested) {
      wrap(out, name, encodeMap(nested));
      return;
    }
    if (value instanceof XypDate date) {
      encodeDate(out, name, date);
      return;
    }
    wrap(out, name, encodeScalar(name, value));
  }

  private static void encodeDate(StringBuilder out, String name, XypDate date) {
    if (date.raw() != null && !date.raw().isEmpty()) {
      wrap(out, name, escapeText(date.raw()));
    } else if (date.time() != null) {
      wrap(out, name, Scalars.formatTime(date.time()));
    }
  }

  private static String encodeScalar(String name, Object value) {
    if (value instanceof Boolean flag) {
      // "1"/"0" is valid for xs:boolean and xs:int alike; the portal calls some xs:int flags
      // "boolean", so this form is accepted whichever the server declares.
      return flag ? "1" : "0";
    }
    if (value instanceof Long
        || value instanceof Integer
        || value instanceof Short
        || value instanceof Byte
        || value instanceof BigInteger) {
      return value.toString();
    }
    if (value instanceof BigDecimal decimal) {
      return decimal.toPlainString();
    }
    if (value instanceof Double number) {
      checkFinite(name, number);
      return Scalars.formatDouble(number);
    }
    if (value instanceof Float number) {
      checkFinite(name, number.doubleValue());
      return Scalars.formatFloat(number);
    }
    if (value instanceof byte[] data) {
      return Base64.getEncoder().encodeToString(data);
    }
    if (value instanceof OffsetDateTime time) {
      return Scalars.formatTime(time);
    }
    if (value instanceof Instant instant) {
      return Scalars.formatTime(instant.atOffset(ZoneOffset.UTC));
    }
    if (value instanceof ZonedDateTime time) {
      return Scalars.formatTime(time.toOffsetDateTime());
    }
    throw new XypConfigException(
        "cannot send field " + quote(name) + " of type " + value.getClass().getName() + " to XYP");
  }

  private static void checkFinite(String name, double number) {
    if (!Double.isFinite(number)) {
      // Neither xs:double's INF/NaN spellings nor Java's are something a provider expects.
      throw new XypConfigException(
          "cannot send field " + quote(name) + " to XYP: NaN and infinity have no XML form here");
    }
  }

  private static void wrap(StringBuilder out, String name, String inner) {
    if (inner.isEmpty()) {
      out.append('<').append(name).append(" />");
      return;
    }
    out.append('<').append(name).append('>').append(inner).append("</").append(name).append('>');
  }

  static String wrapElement(String name, String inner) {
    StringBuilder out = new StringBuilder(inner.length() + 2 * name.length() + 5);
    wrap(out, name, inner);
    return out.toString();
  }

  /** Escapes the three characters that are special in element content. */
  static String escapeText(String text) {
    return escape(text, false);
  }

  /** Also escapes the quote that delimits an attribute value. */
  static String escapeAttribute(String text) {
    return escape(text, true);
  }

  private static String escape(String text, boolean attribute) {
    StringBuilder out = null;
    for (int index = 0; index < text.length(); index++) {
      char c = text.charAt(index);
      String replacement =
          switch (c) {
            case '&' -> "&amp;";
            case '<' -> "&lt;";
            case '>' -> "&gt;";
            case '"' -> attribute ? "&quot;" : null;
            default -> null;
          };
      if (replacement != null) {
        if (out == null) {
          out = new StringBuilder(text.length() + 16).append(text, 0, index);
        }
        out.append(replacement);
      } else if (out != null) {
        out.append(c);
      }
    }
    return out == null ? text : out.toString();
  }

  /** Quotes a name for a message, the way Go's %q does for the plain names that reach it. */
  static String quote(String text) {
    return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
  }
}
