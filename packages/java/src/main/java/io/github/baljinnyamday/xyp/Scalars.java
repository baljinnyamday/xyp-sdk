package io.github.baljinnyamday.xyp;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lenient readers behind one response field, and the writers for dates and floats in a request.
 * The accepted spellings are the ones the Python, TypeScript and Go SDKs accept, so every SDK reads
 * a response the same way.
 */
final class Scalars {

  /** "34.0" is an integer written by a spreadsheet. */
  static final Pattern INTEGER = Pattern.compile("^[+-]?\\d+(\\.0+)?$");

  static final Pattern FLOAT = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

  /** Strict on purpose: a lenient decoder turns "N/A" into garbage bytes. */
  private static final Pattern BASE64 =
      Pattern.compile("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$");

  /** The whitespace Go's {@code \s} matches, which line-wrapped base64 may contain. */
  private static final Pattern WHITESPACE = Pattern.compile("[\\t\\n\\f\\r ]");

  /** Must start with a full date: a bare "2020" is a year, not a timestamp. */
  private static final Pattern ISO_DATE =
      Pattern.compile(
          "^(\\d{4}-\\d{2}-\\d{2})([T ](\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?)(Z|[+-]\\d{2}:?\\d{2})?)?$");

  private static final Set<String> TRUE_TEXT = Set.of("true", "1", "t", "yes", "y", "on");
  private static final Set<String> FALSE_TEXT = Set.of("false", "0", "f", "no", "n", "off");

  private static final DateTimeFormatter SECONDS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss", Locale.ROOT);

  /** java.time reads at most nine fraction digits; Go drops the ones after the ninth. */
  private static final int MAX_FRACTION_DIGITS = 9;

  private Scalars() {}

  static Optional<Long> parseInteger(String text) {
    if (!INTEGER.matcher(text).matches()) {
      return Optional.empty();
    }
    int dot = text.indexOf('.');
    try {
      return Optional.of(Long.parseLong(dot < 0 ? text : text.substring(0, dot)));
    } catch (NumberFormatException outOfRange) {
      return Optional.empty();
    }
  }

  static Optional<Double> parseFloat(String text) {
    if (!FLOAT.matcher(text).matches()) {
      return Optional.empty();
    }
    double value = Double.parseDouble(text);
    // Go's ParseFloat rejects a value out of range instead of returning infinity.
    return Double.isInfinite(value) ? Optional.empty() : Optional.of(value);
  }

  static Optional<BigDecimal> parseDecimal(String text) {
    // Kept exact: BigDecimal holds whatever precision XYP wrote.
    return FLOAT.matcher(text).matches() ? Optional.of(new BigDecimal(text)) : Optional.empty();
  }

  static Optional<Boolean> parseBoolean(String text) {
    String lower = text.toLowerCase(Locale.ROOT);
    if (TRUE_TEXT.contains(lower)) {
      return Optional.of(Boolean.TRUE);
    }
    return FALSE_TEXT.contains(lower) ? Optional.of(Boolean.FALSE) : Optional.empty();
  }

  static Optional<byte[]> parseBytes(String text) {
    String compact = WHITESPACE.matcher(text).replaceAll("");
    if (!BASE64.matcher(compact).matches()) {
      return Optional.empty();
    }
    return Optional.of(Base64.getDecoder().decode(compact));
  }

  /**
   * Never rejects: providers fill date fields by hand and the formats are not documented, so
   * anything unreadable is kept as text.
   */
  static XypDate parseDate(String text) {
    Matcher match = ISO_DATE.matcher(text);
    if (!match.matches()) {
      return new XypDate(text, null);
    }
    try {
      LocalDate date = LocalDate.parse(match.group(1));
      if (match.group(2) == null) {
        return new XypDate(text, date.atStartOfDay().atOffset(ZoneOffset.UTC));
      }
      LocalTime time = LocalTime.parse(truncateFraction(match.group(3)));
      return new XypDate(text, OffsetDateTime.of(date, time, offset(match.group(4))));
    } catch (DateTimeException outOfRange) {
      // The shape is ISO 8601 but a field is not, e.g. month 13: keep the text only.
      return new XypDate(text, null);
    }
  }

  private static String truncateFraction(String time) {
    int dot = time.indexOf('.');
    if (dot < 0 || time.length() - dot - 1 <= MAX_FRACTION_DIGITS) {
      return time;
    }
    return time.substring(0, dot + 1 + MAX_FRACTION_DIGITS);
  }

  /** A time without a zone is UTC, and a "+0800" offset gains its colon. */
  private static ZoneOffset offset(String zone) {
    if (zone == null || zone.equals("Z")) {
      return ZoneOffset.UTC;
    }
    String withColon =
        zone.indexOf(':') < 0 ? zone.substring(0, 3) + ":" + zone.substring(3) : zone;
    return ZoneOffset.of(withColon);
  }

  /**
   * Writes the lexical form zeep (the known-working client) sends: UTC with "Z", and no trailing
   * zeros in the fraction.
   */
  static String formatTime(OffsetDateTime time) {
    OffsetDateTime utc = time.withOffsetSameInstant(ZoneOffset.UTC);
    StringBuilder out = new StringBuilder(SECONDS.format(utc));
    int nanos = utc.getNano();
    if (nanos > 0) {
      String fraction = String.format(Locale.ROOT, "%09d", nanos);
      int end = fraction.length();
      while (fraction.charAt(end - 1) == '0') {
        end--;
      }
      out.append('.').append(fraction, 0, end);
    }
    return out.append('Z').toString();
  }

  /**
   * The shortest decimal that reads back as the same double, without an exponent: what Go's {@code
   * strconv.FormatFloat(v, 'f', -1, 64)} writes. {@link Double#toString} is not used, as it
   * switches to an exponent and, before Java 19, was not always the shortest.
   */
  static String formatDouble(double value) {
    if (value == 0) {
      return Double.doubleToRawLongBits(value) < 0 ? "-0" : "0";
    }
    BigDecimal exact = new BigDecimal(value);
    for (int digits = 1; ; digits++) {
      for (BigDecimal candidate : candidates(exact, digits)) {
        if (candidate.doubleValue() == value) {
          return candidate.stripTrailingZeros().toPlainString();
        }
      }
    }
  }

  /** The same for a float, as {@code strconv.FormatFloat(v, 'f', -1, 32)} writes it. */
  static String formatFloat(float value) {
    if (value == 0) {
      return Float.floatToRawIntBits(value) < 0 ? "-0" : "0";
    }
    BigDecimal exact = new BigDecimal(value);
    for (int digits = 1; ; digits++) {
      for (BigDecimal candidate : candidates(exact, digits)) {
        if (candidate.floatValue() == value) {
          return candidate.stripTrailingZeros().toPlainString();
        }
      }
    }
  }

  /**
   * The nearest decimal with this many significant digits first, then its neighbours on either
   * side: next to a power of two the interval that reads back as the same value is lopsided, so the
   * nearest candidate can miss while the one on the far side still fits.
   */
  private static BigDecimal[] candidates(BigDecimal exact, int digits) {
    return new BigDecimal[] {
      exact.round(new MathContext(digits, RoundingMode.HALF_EVEN)),
      exact.round(new MathContext(digits, RoundingMode.DOWN)),
      exact.round(new MathContext(digits, RoundingMode.UP)),
    };
  }
}
