package io.github.baljinnyamday.xyp;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The decoders of every field type the SDK models, for {@link ResponseReader#get(String, Decoder)}.
 *
 * <p>XML carries only text, so every scalar is read from text, leniently: the spellings accepted
 * are the ones every XYP SDK accepts. A value that still does not fit becomes {@code null} and is
 * reported as {@code "not a valid <kind>"}; an object or a list where a scalar belongs is reported
 * as {@code "expected <kind>, got an object"} (or {@code "a list"}).
 *
 * <p>An absent field, and an element XYP sent empty, decode to {@code null}, except for a list,
 * which is then empty: a list is never {@code null}.
 */
public final class Decoders {

  /** Text as XYP sent it, trimmed. XML cannot tell an empty string from a missing element. */
  public static final Decoder<String> STRING = scalar("string", Optional::of);

  /**
   * A 64-bit integer. {@code "34.0"} is accepted, since it is an integer written by a spreadsheet;
   * {@code "34.5"} and values beyond the {@code long} range are not.
   */
  public static final Decoder<Long> INT = scalar("int", Scalars::parseInteger);

  /** A double, in decimal or exponent notation. A value beyond the double range is rejected. */
  public static final Decoder<Double> FLOAT = scalar("float", Scalars::parseFloat);

  /**
   * A boolean: {@code true}, {@code 1}, {@code t}, {@code yes}, {@code y}, {@code on} and their
   * opposites {@code false}, {@code 0}, {@code f}, {@code no}, {@code n}, {@code off}, in any case.
   */
  public static final Decoder<Boolean> BOOL = scalar("bool", Scalars::parseBoolean);

  /** A decimal, kept exact: {@code "1234567890123456789.50"} keeps every digit and its scale. */
  public static final Decoder<BigDecimal> DECIMAL = scalar("decimal", Scalars::parseDecimal);

  /**
   * Standard base64, which may be line-wrapped. Strict on purpose: text such as {@code "N/A"} is
   * reported rather than decoded into garbage bytes.
   */
  public static final Decoder<byte[]> BYTES = scalar("bytes", Scalars::parseBytes);

  /**
   * A date. Never rejects text: {@link XypDate#raw()} keeps it, and {@link XypDate#time()} is set
   * when it is ISO 8601.
   */
  public static final Decoder<XypDate> DATE =
      scalar("date", text -> Optional.of(Scalars.parseDate(text)));

  /**
   * The raw node as it stands: a {@code String}, or an unmodifiable {@code Map<String, Object>} or
   * {@code List<Object>}.
   */
  public static final Decoder<Object> ANY = (node, context) -> node;

  private Decoders() {}

  /**
   * A nested object, decoded by {@code decoder}. A value that is not an object is reported as
   * {@code "expected an object"}; a field inside it that does not fit costs only that field.
   *
   * @param <T> the type of the object
   * @param decoder the decoder of the nested object, usually a record's {@code decode} method
   * @return a decoder whose absent value is {@code null}
   */
  public static <T> Decoder<T> object(ResponseDecoder<T> decoder) {
    Objects.requireNonNull(decoder, "decoder");
    return (node, context) -> {
      if (!(node instanceof Map<?, ?> fields)) {
        throw context.reject("expected an object");
      }
      return decoder.decode(new ResponseReader(fields, context.path(), context.session()));
    };
  }

  /**
   * A list of items, each decoded by {@code item}.
   *
   * <ul>
   *   <li>XML cannot tell a one-item list from a single value, so a single value becomes a one-item
   *       list.
   *   <li>An empty item carries no data and is skipped.
   *   <li>A list never holds a stand-in for an item that did not fit: when one item does not fit,
   *       the whole list becomes empty, and every item that did not fit is reported, at a path such
   *       as {@code "listData[1]"}.
   * </ul>
   *
   * @param <T> the type of the items
   * @param item the decoder of one item
   * @return a decoder of unmodifiable lists, whose absent value is an empty list
   */
  public static <T> Decoder<List<T>> list(Decoder<T> item) {
    Objects.requireNonNull(item, "item");
    return new Decoder<>() {
      @Override
      public List<T> decode(Object node, Decoder.Context context) {
        List<?> raw = node instanceof List<?> items ? items : List.of(node);
        List<T> decoded = new ArrayList<>(raw.size());
        DecodeSession session = context.session();
        int index = 0;
        boolean fits = true;
        for (Object element : raw) {
          if (element == null) {
            continue;
          }
          String path = context.path() + "[" + index + "]";
          index++;
          try {
            decoded.add(item.decode(element, new Decoder.Context(element, path, session)));
          } catch (Rejection rejection) {
            if (!rejection.belongsTo(session)) {
              throw rejection;
            }
            fits = false;
          }
        }
        if (!fits) {
          // Every rejected item is reported already; the list itself adds no mismatch.
          throw new Rejection(session);
        }
        return Collections.unmodifiableList(decoded);
      }

      @Override
      public List<T> absent() {
        return List.of();
      }
    };
  }

  /** A decoder of text; a map or a list is reported as the wrong shape. */
  private static <T> Decoder<T> scalar(String kind, Function<String, Optional<T>> parse) {
    return (node, context) -> {
      if (!(node instanceof String text)) {
        String shape = node instanceof List<?> ? "a list" : "an object";
        throw context.reject("expected " + kind + ", got " + shape);
      }
      return parse.apply(text).orElseThrow(() -> context.reject("not a valid " + kind));
    };
  }
}
