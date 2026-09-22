package io.github.baljinnyamday.xyp;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An ordered, immutable list of raw request parameters. Use it instead of a map when the service
 * cares about field order, which XYP's schemas do:
 *
 * <pre>{@code
 * Object data = xyp.call("WS100101_getCitizenIDCardInfo",
 *     Params.builder().add("civilId", civilId).add("regnum", regnum).build());
 * }</pre>
 *
 * A value may be {@code null}, which leaves it out of the request; see the package documentation
 * for how every other value is encoded. A name may repeat. {@link #toString()} lists the names
 * only, because the values are citizen data.
 */
public final class Params implements RequestParams {

  private static final Params EMPTY = new Params(List.of());

  private final List<Map.Entry<String, Object>> entries;

  private Params(List<Map.Entry<String, Object>> entries) {
    this.entries = entries;
  }

  /**
   * No parameters.
   *
   * @return the empty list of parameters
   */
  public static Params empty() {
    return EMPTY;
  }

  /**
   * One parameter.
   *
   * @param name the element name
   * @param value the value, or {@code null} to leave it out
   * @return the parameters
   */
  public static Params of(String name, Object value) {
    return builder().add(name, value).build();
  }

  /**
   * Starts an empty list of parameters.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The parameters in the order they go on the wire.
   *
   * @return an unmodifiable list of name/value pairs; a value may be {@code null}
   */
  public List<Map.Entry<String, Object>> entries() {
    return entries;
  }

  /**
   * The number of parameters.
   *
   * @return the number of entries, counting {@code null} values
   */
  public int size() {
    return entries.size();
  }

  /**
   * Whether there are no parameters.
   *
   * @return {@code true} when there are no entries
   */
  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /**
   * Returns this list itself.
   *
   * @return {@code this}
   */
  @Override
  public Params toParams() {
    return this;
  }

  /**
   * Compares the entries, in order.
   *
   * @param other the object to compare with
   * @return whether {@code other} holds the same names and values in the same order
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof Params that && entries.equals(that.entries);
  }

  /**
   * A hash of the entries, consistent with {@link #equals(Object)}.
   *
   * @return the hash code
   */
  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  /**
   * Lists the names only: the values are citizen data and stay out of logs.
   *
   * @return e.g. {@code "Params[civilId, regnum]"}
   */
  @Override
  public String toString() {
    return entries.stream()
        .map(Map.Entry::getKey)
        .collect(Collectors.joining(", ", "Params[", "]"));
  }

  /** Collects parameters in order. */
  public static final class Builder {

    private final List<Map.Entry<String, Object>> entries = new ArrayList<>();

    private Builder() {}

    /**
     * Appends a parameter. The name is checked when the request is built: it must be a valid XML
     * element name, because names cannot be escaped.
     *
     * @param name the element name
     * @param value the value, or {@code null} to leave it out of the request
     * @return this builder
     */
    public Builder add(String name, Object value) {
      entries.add(
          new AbstractMap.SimpleImmutableEntry<>(Objects.requireNonNull(name, "name"), value));
      return this;
    }

    /**
     * Builds the parameters.
     *
     * @return new immutable parameters holding what was added so far
     */
    public Params build() {
      return entries.isEmpty()
          ? EMPTY
          : new Params(Collections.unmodifiableList(new ArrayList<>(entries)));
    }
  }
}
