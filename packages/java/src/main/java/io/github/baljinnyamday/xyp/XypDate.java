package io.github.baljinnyamday.xyp;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A date field as XYP sent it, or a date to send.
 *
 * <p>Providers fill date fields by hand and the formats are not documented, so a response date is
 * never rejected: {@link #raw()} always holds the original text, and {@link #time()} is set only
 * when that text is ISO 8601 ({@code 2024-01-31}, {@code 2024-01-31 12:00:00}, {@code
 * 2024-01-31T12:00:00+0800}, ...). A value without a zone is read as UTC.
 *
 * <pre>{@code
 * XypDate born = card.birthDate();
 * if (born != null && born.time() != null) use(born.time()); else use(born.raw());
 * }</pre>
 *
 * <p>In a request, {@code raw} is sent as it stands when it is set and not empty; otherwise {@code
 * time} is sent in UTC, e.g. {@code 2024-01-31T12:00:00Z}; a date with neither is left out.
 *
 * @param raw the text as XYP sent it, or {@code null} for a date built from a time
 * @param time the parsed moment, or {@code null} when {@code raw} is not ISO 8601
 */
public record XypDate(String raw, OffsetDateTime time) {

  /**
   * A date to send, built from a moment.
   *
   * @param time the moment; it is sent in UTC
   * @return a date without {@code raw}
   */
  public static XypDate of(OffsetDateTime time) {
    return new XypDate(null, Objects.requireNonNull(time, "time"));
  }

  /**
   * A date to send, built from an instant.
   *
   * @param instant the moment; it is sent in UTC
   * @return a date without {@code raw}, whose time is in UTC
   */
  public static XypDate of(Instant instant) {
    return new XypDate(null, Objects.requireNonNull(instant, "instant").atOffset(ZoneOffset.UTC));
  }

  /**
   * A date from text, read the same way as a date in a response: {@link #time()} is set when the
   * text is ISO 8601. In a request, the text is sent exactly as given.
   *
   * @param raw the text
   * @return a date holding the text
   */
  public static XypDate ofRaw(String raw) {
    return Scalars.parseDate(Objects.requireNonNull(raw, "raw"));
  }
}
