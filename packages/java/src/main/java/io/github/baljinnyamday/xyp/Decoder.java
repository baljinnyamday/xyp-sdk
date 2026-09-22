package io.github.baljinnyamday.xyp;

/**
 * Decodes one response field. Use the ones in {@link Decoders}; implement this interface only for a
 * value they do not cover. A decoder never fails a call: a value that does not fit is reported
 * through {@link Context#reject(String)}, and the field becomes {@link #absent()}.
 *
 * <pre>{@code
 * Decoder<Gender> gender = (node, context) -> {
 *   String code = Decoders.STRING.decode(node, context);
 *   return switch (code) {
 *     case "M" -> Gender.MALE;
 *     case "F" -> Gender.FEMALE;
 *     default -> throw context.reject("not a valid gender");
 *   };
 * };
 * }</pre>
 *
 * @param <T> the type it decodes to
 */
@FunctionalInterface
public interface Decoder<T> {

  /**
   * Decodes a value that is present. An absent field, and an element XYP sent empty, never reach
   * this method: they become {@link #absent()}.
   *
   * @param node the raw value, never {@code null}: a {@code String}, or an unmodifiable {@code
   *     Map<String, Object>} or {@code List<Object>}
   * @param context where the value is, and how to reject it
   * @return the decoded value
   * @throws RuntimeException the exception {@link Context#reject(String)} returned, when the value
   *     does not fit
   */
  T decode(Object node, Context context);

  /**
   * The value of a field that is absent, empty, or did not fit.
   *
   * @return {@code null}, unless the decoder says otherwise (a list decoder returns an empty list)
   */
  default T absent() {
    return null;
  }

  /** Where the value being decoded sits in the response, and how to reject it. */
  final class Context {

    private final Object node;
    private final String path;
    private final DecodeSession session;

    Context(Object node, String path, DecodeSession session) {
      this.node = node;
      this.path = path;
      this.session = session;
    }

    /**
     * Where the value is in the response.
     *
     * @return e.g. {@code "listData[1].year"}; {@code ""} is the response itself
     */
    public String path() {
      return path;
    }

    /**
     * Records that the value does not fit and returns the exception to throw, so the field becomes
     * {@link Decoder#absent()} and the value is kept in {@link Extras#mismatches()}.
     *
     * <pre>{@code
     * throw context.reject("not a valid gender");
     * }</pre>
     *
     * @param problem what did not fit, e.g. {@code "not a valid int"}; it goes into a log line, so
     *     it must not contain the value
     * @return the exception to throw from {@link Decoder#decode(Object, Context)}
     */
    public RuntimeException reject(String problem) {
      session.report(path, problem, node);
      return new Rejection(session);
    }

    DecodeSession session() {
      return session;
    }
  }
}
