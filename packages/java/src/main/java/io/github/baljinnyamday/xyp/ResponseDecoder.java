package io.github.baljinnyamday.xyp;

/**
 * Builds a typed value from one object of a response. Every generated response record has one, its
 * static {@code decode} method; write your own for a service the generated code does not cover yet:
 *
 * <pre>{@code
 * record IdCard(String firstname, XypDate birthDate, Extras extras) {
 *   static IdCard decode(ResponseReader r) {
 *     return new IdCard(r.get("firstname", Decoders.STRING), r.get("birthDate", Decoders.DATE),
 *         r.extras());
 *   }
 * }
 * IdCard card = xyp.invoke("WS100101_getCitizenIDCardInfo", params, options, IdCard::decode);
 * }</pre>
 *
 * @param <T> the type it builds
 */
@FunctionalInterface
public interface ResponseDecoder<T> {

  /**
   * Builds the value from the fields the reader holds.
   *
   * @param reader the fields of one object of the response
   * @return the value
   */
  T decode(ResponseReader reader);
}
