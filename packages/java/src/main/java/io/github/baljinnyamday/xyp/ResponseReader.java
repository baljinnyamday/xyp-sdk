package io.github.baljinnyamday.xyp;

import java.util.Map;
import java.util.Objects;

/**
 * The fields of one object in a response, read by a {@link ResponseDecoder}. The generated response
 * records use it, and you can use it for types of your own.
 *
 * <p>Reading never fails: a field that does not fit the decoder is recorded as a {@link Mismatch}
 * and read as the decoder's absent value ({@code null}, or an empty list for a list). {@link
 * #extras()} hands the mismatches and the raw response to the caller.
 */
public final class ResponseReader {

  private final Map<?, ?> fields;
  private final String path;
  private final DecodeSession session;

  ResponseReader(Map<?, ?> fields, String path, DecodeSession session) {
    this.fields = fields;
    this.path = path;
    this.session = session;
  }

  /**
   * Decodes a response tree you already hold, the way {@link XypClient#invoke} does, but without
   * the log warning: from {@link XypClient#call(String, Object)}, or from a test fixture.
   *
   * @param <T> the type to decode to
   * @param tree the response tree: nested {@code Map<String, ?>}, {@code List<?>}, {@code String}
   *     and {@code null}
   * @param decoder the decoder of the response object
   * @return the decoded value
   */
  public static <T> T decode(Object tree, ResponseDecoder<T> decoder) {
    return DecodeSession.decode(tree, Objects.requireNonNull(decoder, "decoder")).value();
  }

  /**
   * Decodes one field of this object.
   *
   * @param <T> the type to decode to
   * @param wireName the element name, as XYP sends it
   * @param decoder how to decode it, usually one of {@link Decoders}
   * @return the value, or the decoder's absent value when the field is absent, empty or does not
   *     fit
   */
  public <T> T get(String wireName, Decoder<T> decoder) {
    Objects.requireNonNull(wireName, "wireName");
    Objects.requireNonNull(decoder, "decoder");
    return session.decodeNode(
        fields.get(wireName), DecodeSession.childPath(path, wireName), decoder);
  }

  /**
   * What the response carries besides the declared fields. A generated response record calls this
   * last, after every field was read, so the snapshot holds every mismatch.
   *
   * @return the mismatches recorded so far in this response, and the whole raw response
   */
  public Extras extras() {
    return session.extras();
  }
}
