<?php

declare(strict_types=1);

namespace Xyp\Tests\Internal;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Xyp\Internal\Scalars;

final class ScalarsTest extends TestCase
{
    #[DataProvider('integers')]
    public function testReadsIntegersLikeTheOtherSdks(string $text, ?int $want): void
    {
        self::assertSame($want, Scalars::integer($text));
    }

    /**
     * @return iterable<string, array{string, ?int}>
     */
    public static function integers(): iterable
    {
        yield 'plain' => ['34', 34];
        yield 'spreadsheet' => ['34.000', 34];
        yield 'signs' => ['+7', 7];
        yield 'negative' => ['-7', -7];
        yield 'leading zeros' => ['007', 7];
        yield 'negative zero' => ['-0', 0];
        yield 'max' => ['9223372036854775807', PHP_INT_MAX];
        yield 'min' => ['-9223372036854775808', PHP_INT_MIN];
        yield 'overflow' => ['9223372036854775808', null];
        yield 'underflow' => ['-9223372036854775809', null];
        yield 'fraction' => ['34.5', null];
        yield 'exponent' => ['1e3', null];
        yield 'space' => [' 34', null];
        yield 'trailing newline' => ["34\n", null];
        yield 'empty' => ['', null];
        yield 'dot only' => ['34.', null];
    }

    #[DataProvider('floats')]
    public function testReadsFloatsLikeTheOtherSdks(string $text, ?float $want): void
    {
        self::assertSame($want, Scalars::float($text));
    }

    /**
     * @return iterable<string, array{string, ?float}>
     */
    public static function floats(): iterable
    {
        yield 'plain' => ['0.5', 0.5];
        yield 'leading dot' => ['.5', 0.5];
        yield 'trailing dot' => ['5.', 5.0];
        yield 'exponent' => ['-1.5E+3', -1500.0];
        yield 'integer' => ['+3', 3.0];
        yield 'overflow' => ['1e999', null];
        yield 'comma' => ['1,5', null];
        yield 'NaN' => ['NaN', null];
        yield 'Inf' => ['Inf', null];
        yield 'hex' => ['0x1A', null];
        yield 'space' => ['1 ', null];
    }

    public function testBooleansAcceptTheHandTypedSpellings(): void
    {
        foreach (['true', 'TRUE', '1', 't', 'Yes', 'y', 'ON'] as $text) {
            self::assertTrue(Scalars::boolean($text), $text);
        }
        foreach (['false', '0', 'F', 'no', 'N', 'off'] as $text) {
            self::assertFalse(Scalars::boolean($text), $text);
        }
        foreach (['', 'maybe', '2', ' true', 'тийм'] as $text) {
            self::assertNull(Scalars::boolean($text), $text);
        }
    }

    public function testBase64IsStrict(): void
    {
        self::assertSame("\x00\x01", Scalars::bytes('AAE='));
        self::assertSame("\x00\x01\x02", Scalars::bytes("AA\nEC"));
        self::assertSame('', Scalars::bytes(''));
        foreach (['N/A', 'AAE', 'AAE==', '====', 'AA-_', 'AAE=AAE='] as $text) {
            self::assertNull(Scalars::bytes($text), $text);
        }
    }

    #[DataProvider('dates')]
    public function testReadsIso8601AndKeepsEverythingElseAsText(string $text, ?string $want): void
    {
        $date = Scalars::date($text);

        self::assertSame($text, $date->raw);
        self::assertSame($text, (string) $date);
        self::assertSame($want, $date->time?->format('Y-m-d\TH:i:s.uP'));
    }

    /**
     * @return iterable<string, array{string, ?string}>
     */
    public static function dates(): iterable
    {
        yield 'date' => ['2024-01-31', '2024-01-31T00:00:00.000000+00:00'];
        yield 'minutes' => ['2024-01-31T12:30', '2024-01-31T12:30:00.000000+00:00'];
        yield 'minutes with zone' => ['2024-01-31T12:30+08:00', '2024-01-31T12:30:00.000000+08:00'];
        yield 'space separator' => ['2024-01-31 12:00:00', '2024-01-31T12:00:00.000000+00:00'];
        yield 'Z' => ['2024-01-31T12:00:00Z', '2024-01-31T12:00:00.000000+00:00'];
        yield 'offset without colon' => ['2024-01-31T12:00:00+0800', '2024-01-31T12:00:00.000000+08:00'];
        yield 'negative offset' => ['2024-01-31T12:00:00-03:30', '2024-01-31T12:00:00.000000-03:30'];
        yield 'fraction' => ['2024-01-31T12:00:00.5Z', '2024-01-31T12:00:00.500000+00:00'];
        yield 'nanoseconds are cut to microseconds' => ['2024-01-31T12:00:00.123456789', '2024-01-31T12:00:00.123456+00:00'];
        yield 'a year is not a timestamp' => ['2020', null];
        yield 'undocumented format' => ['31.01.2024', null];
        yield 'impossible day' => ['2024-02-31', null];
        yield 'impossible hour' => ['2024-01-31T25:00', null];
        yield 'zone without time' => ['2024-01-31Z', null];
        yield 'trailing text' => ['2024-01-31 or so', null];
    }

    public function testADateWithoutAZoneIsUtc(): void
    {
        self::assertSame('UTC', Scalars::date('2024-01-31T12:00:00')->time?->getTimezone()->getName());
    }

    #[DataProvider('floatTexts')]
    public function testFormatsFloatsAsGoFormatFloatFMinusOneDoes(float $value, string $want): void
    {
        self::assertSame($want, Scalars::formatFloat($value));
    }

    /**
     * Expected values are what Go's strconv.FormatFloat(v, 'f', -1, 64) prints.
     *
     * @return iterable<string, array{float, string}>
     */
    public static function floatTexts(): iterable
    {
        yield 'half' => [0.5, '0.5'];
        yield 'whole' => [100.0, '100'];
        yield 'zero' => [0.0, '0'];
        yield 'negative zero' => [-0.0, '-0'];
        yield 'large' => [1e21, '1000000000000000000000'];
        yield 'small' => [1e-7, '0.0000001'];
        yield 'shortest round trip' => [0.1 + 0.2, '0.30000000000000004'];
        yield 'negative' => [-1234.5678, '-1234.5678'];
        yield 'max int as float' => [9007199254740993.0, '9007199254740992'];
        yield 'third' => [1 / 3, '0.3333333333333333'];
        yield 'smallest subnormal' => [5e-324, '0.' . str_repeat('0', 323) . '5'];
    }

    public function testFormatsTimesInUtcWithoutTrailingZeros(): void
    {
        self::assertSame('2024-01-31T12:00:00Z', Scalars::formatTime(new \DateTimeImmutable('2024-01-31T20:00:00+08:00')));
        self::assertSame('2024-01-31T12:00:00.1Z', Scalars::formatTime(new \DateTimeImmutable('2024-01-31T12:00:00.100000Z')));
        self::assertSame('2024-01-31T12:00:00.000001Z', Scalars::formatTime(new \DateTime('2024-01-31T12:00:00.000001Z')));
    }
}
