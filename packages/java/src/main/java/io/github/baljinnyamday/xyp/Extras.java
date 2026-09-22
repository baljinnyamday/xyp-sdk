package io.github.baljinnyamday.xyp;

import java.util.List;
import java.util.Objects;

/**
 * What a typed response carries besides its declared fields. Every generated response record ends
 * with one.
 *
 * <p>{@link #toString()} lists the mismatches' paths and problems and leaves {@code raw} out: it is
 * the whole response, full of citizen data.
 *
 * @param mismatches normally empty; each entry is a field of this response that did not fit the
 *     SDK's model, which was decoded as {@code null} (an empty list for a list) while the call
 *     still succeeded
 * @param raw the whole parsed response: nested unmodifiable {@code Map<String, Object>} (in
 *     document order), {@code List<Object>}, {@code String} and {@code null}. Reach fields the
 *     generated model does not declare through it.
 */
public record Extras(List<Mismatch> mismatches, Object raw) {

  /**
   * Checks and copies the mismatches.
   *
   * @param mismatches the fields that did not fit
   * @param raw the whole parsed response
   */
  public Extras {
    mismatches = List.copyOf(Objects.requireNonNull(mismatches, "mismatches"));
  }

  /**
   * Lists the mismatches without their values, and leaves the raw response out.
   *
   * @return e.g. {@code "Extras[mismatches=[age (not a valid int)]]"}
   */
  @Override
  public String toString() {
    return "Extras[mismatches=" + mismatches + "]";
  }
}
