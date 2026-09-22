package io.github.baljinnyamday.xyp;

/**
 * One response field that did not fit the SDK's model. The field was decoded as {@code null} (an
 * empty list for a list) and the call still succeeded.
 *
 * <p>{@link #toString()} prints the path and the problem, never the value: the value is citizen
 * data and stays out of logs unless the caller asks for it through {@link #value()}.
 */
public final class Mismatch {

  private final String path;
  private final String problem;
  private final Object value;

  Mismatch(String path, String problem, Object value) {
    this.path = path;
    this.problem = problem;
    this.value = value;
  }

  /**
   * Where the field is in the response.
   *
   * @return e.g. {@code "listData[1].year"}; {@code ""} is the response itself
   */
  public String path() {
    return path;
  }

  /**
   * What did not fit.
   *
   * @return e.g. {@code "not a valid int"} or {@code "expected an object"}
   */
  public String problem() {
    return problem;
  }

  /**
   * The raw value XYP sent for this field: a {@code String}, or an unmodifiable {@code Map} or
   * {@code List} of the response tree.
   *
   * @return the raw value
   */
  public Object value() {
    return value;
  }

  /**
   * The path and the problem, never the value.
   *
   * @return e.g. {@code "age (not a valid int)"}, or {@code "<response> (expected an object)"}
   */
  @Override
  public String toString() {
    return (path.isEmpty() ? "<response>" : path) + " (" + problem + ")";
  }
}
