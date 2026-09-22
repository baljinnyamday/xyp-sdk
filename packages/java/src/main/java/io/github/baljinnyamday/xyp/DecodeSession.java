package io.github.baljinnyamday.xyp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The state of decoding one response: the whole raw tree, and the fields that did not fit so far.
 * One thread decodes a response, so none of this is synchronised.
 */
final class DecodeSession {

  private final Object raw;
  private final List<Mismatch> mismatches = new ArrayList<>();

  private DecodeSession(Object raw) {
    this.raw = raw;
  }

  /**
   * Decodes a whole response. A response that is not an object is reported at path {@code ""} and
   * decoded as if it were empty; a missing response is simply empty.
   */
  static <T> Decoded<T> decode(Object tree, ResponseDecoder<T> decoder) {
    DecodeSession session = new DecodeSession(tree);
    Map<?, ?> fields = Map.of();
    if (tree instanceof Map<?, ?> map) {
      fields = map;
    } else if (tree != null) {
      session.report("", "expected an object", tree);
    }
    T value = decoder.decode(new ResponseReader(fields, "", session));
    return new Decoded<>(value, List.copyOf(session.mismatches));
  }

  /** A decoded response and the fields that did not fit. */
  record Decoded<T>(T value, List<Mismatch> mismatches) {}

  void report(String path, String problem, Object value) {
    mismatches.add(new Mismatch(path, problem, value));
  }

  Extras extras() {
    return new Extras(mismatches, raw);
  }

  /**
   * Decodes one node, which may be {@code null}. A value that does not fit has been reported by the
   * decoder, and becomes the decoder's absent value.
   */
  <T> T decodeNode(Object node, String path, Decoder<T> decoder) {
    if (node == null) {
      return decoder.absent();
    }
    try {
      return decoder.decode(node, new Decoder.Context(node, path, this));
    } catch (Rejection rejection) {
      if (!rejection.belongsTo(this)) {
        throw rejection;
      }
      return decoder.absent();
    }
  }

  static String childPath(String path, String name) {
    return path.isEmpty() ? name : path + "." + name;
  }
}
