package io.github.baljinnyamday.xyp;

/**
 * Unwinds a decoder whose value did not fit, back to the field it was decoding. The mismatch has
 * been recorded by then; this carries no message (it would only repeat it) and no stack trace (it
 * is control flow, not a failure).
 */
final class Rejection extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The response being decoded, so a rejection cannot be caught by another response's reader. */
  private final transient DecodeSession session;

  Rejection(DecodeSession session) {
    super(null, null, false, false);
    this.session = session;
  }

  boolean belongsTo(DecodeSession other) {
    return session == other;
  }
}
