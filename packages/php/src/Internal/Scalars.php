<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Xyp\Date;

/**
 * The lenient readers and the writers behind one field. The accepted spellings
 * are the ones the Python, TypeScript and Go SDKs accept, so every SDK reads a
 * response the same way and writes a request byte for byte the same.
 *
 * @internal
 */
final class Scalars
{
    /** "34.0" is an integer written by a spreadsheet. */
    private const INTEGER = '/^[+-]?\d+(\.0+)?$/D';

    public const FLOAT = '/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/D';

    /** Strict on purpose: a lenient decoder turns "N/A" into garbage bytes. */
    private const BASE64 = '/^(?:[A-Za-z0-9+\/]{4})*(?:[A-Za-z0-9+\/]{2}==|[A-Za-z0-9+\/]{3}=)?$/D';

    /** Must start with a full date: a bare "2020" is a year, not a timestamp. */
    private const ISO_DATE = '/^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2})(?::(\d{2})(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?)?$/D';

    private const TRUE_TEXT = ['true', '1', 't', 'yes', 'y', 'on'];

    private const FALSE_TEXT = ['false', '0', 'f', 'no', 'n', 'off'];

    public static function integer(string $text): ?int
    {
        if (preg_match(self::INTEGER, $text) !== 1) {
            return null;
        }

        return self::parseInteger(explode('.', $text, 2)[0]); // the pattern only allows ".000" here
    }

    /**
     * Reads a signed decimal integer, or null when it does not fit in 64 bits.
     */
    public static function parseInteger(string $digits): ?int
    {
        if (preg_match('/^([+-]?)(\d+)$/D', $digits, $parts) !== 1) {
            return null;
        }
        $magnitude = ltrim($parts[2], '0');
        if ($magnitude === '') {
            return 0;
        }
        $value = filter_var($parts[1] === '-' ? '-' . $magnitude : $magnitude, FILTER_VALIDATE_INT);

        return is_int($value) ? $value : null; // false when out of range
    }

    public static function float(string $text): ?float
    {
        if (preg_match(self::FLOAT, $text) !== 1) {
            return null;
        }
        $value = (float) $text;

        return is_finite($value) ? $value : null;
    }

    public static function isDecimal(string $text): bool
    {
        return preg_match(self::FLOAT, $text) === 1;
    }

    public static function boolean(string $text): ?bool
    {
        $lower = strtolower($text);

        return match (true) {
            in_array($lower, self::TRUE_TEXT, true) => true,
            in_array($lower, self::FALSE_TEXT, true) => false,
            default => null,
        };
    }

    /**
     * Decodes base64 that may be line-wrapped, and nothing else.
     */
    public static function bytes(string $text): ?string
    {
        $compact = preg_replace('/[\t\n\f\r ]/', '', $text) ?? $text;
        if (preg_match(self::BASE64, $compact) !== 1) {
            return null;
        }
        $data = base64_decode($compact, true);

        return $data === false ? null : $data;
    }

    /**
     * Never rejects: providers fill date fields by hand and the formats are not
     * documented, so anything unreadable is kept as text.
     */
    public static function date(string $text): Date
    {
        if (preg_match(self::ISO_DATE, $text, $parts, PREG_UNMATCHED_AS_NULL) !== 1) {
            return new Date($text);
        }
        [, $day, $minutes, $seconds, $fraction, $zone] = $parts + [null, null, null, null, null, null];
        $normalized = $day . 'T' . ($minutes ?? '00:00') . ':' . ($seconds ?? '00')
            . '.' . str_pad(substr(ltrim($fraction ?? '.', '.'), 0, 6), 6, '0');
        $zone = match (true) {
            $zone === null, $zone === 'Z' => '+00:00',
            strlen($zone) === 5 => substr($zone, 0, 3) . ':' . substr($zone, 3),
            default => $zone,
        };
        $time = \DateTimeImmutable::createFromFormat('!Y-m-d\TH:i:s.uP', $normalized . $zone);
        $errors = \DateTimeImmutable::getLastErrors();
        // PHP would roll an impossible date such as 2024-02-31 over into March and
        // only warn; Go rejects it, so it stays text here too.
        if ($time === false || ($errors !== false && ($errors['warning_count'] > 0 || $errors['error_count'] > 0))) {
            return new Date($text);
        }

        return new Date($text, $zone === '+00:00' ? $time->setTimezone(new \DateTimeZone('UTC')) : $time);
    }

    /**
     * The shortest text that reads back as the same float, never in exponent form:
     * what Go's strconv.FormatFloat(v, 'f', -1, 64) writes.
     */
    public static function formatFloat(float $value): string
    {
        if ($value === 0.0) {
            return fdiv(1.0, $value) < 0 ? '-0' : '0';
        }
        $scientific = sprintf('%.16e', $value); // 17 significant digits always read back
        for ($precision = 0; $precision < 17; ++$precision) {
            $scientific = sprintf('%.' . $precision . 'e', $value);
            if ((float) $scientific === $value) {
                break;
            }
        }
        preg_match('/^(-?)(\d)(?:\.(\d+))?e([+-]\d+)$/D', $scientific, $parts);
        [, $sign, $lead, $rest, $exponent] = $parts + ['', '', '', '', '0'];
        $digits = rtrim($lead . $rest, '0');
        $digits = $digits === '' ? '0' : $digits;
        $point = (int) $exponent + 1; // digits before the decimal point

        return $sign . match (true) {
            $point <= 0 => '0.' . str_repeat('0', -$point) . $digits,
            $point >= strlen($digits) => $digits . str_repeat('0', $point - strlen($digits)),
            default => substr($digits, 0, $point) . '.' . substr($digits, $point),
        };
    }

    /**
     * The lexical form zeep (the known-working client) sends: UTC with "Z", and no
     * trailing zeros in the fraction.
     */
    public static function formatTime(\DateTimeInterface $time): string
    {
        $utc = \DateTimeImmutable::createFromInterface($time)->setTimezone(new \DateTimeZone('UTC'));
        $fraction = rtrim($utc->format('u'), '0');

        return $utc->format('Y-m-d\TH:i:s') . ($fraction === '' ? '' : '.' . $fraction) . 'Z';
    }
}
