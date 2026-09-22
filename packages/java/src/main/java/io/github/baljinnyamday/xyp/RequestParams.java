package io.github.baljinnyamday.xyp;

/**
 * Typed request parameters. Every generated {@code *Params} class implements it, listing its fields
 * in the order the service's schema declares them; implement it yourself to send a type of your own
 * through {@link XypClient#call(String, Object)}.
 */
public interface RequestParams {

  /**
   * Lists the parameters in the order they go on the wire. A {@code null} value is left out of the
   * request.
   *
   * @return the parameters, never {@code null}
   */
  Params toParams();
}
