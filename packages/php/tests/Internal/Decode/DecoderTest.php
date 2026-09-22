<?php

declare(strict_types=1);

namespace Xyp\Tests\Internal\Decode;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Xyp\Exception\ConfigException;
use Xyp\Extras;
use Xyp\Internal\Decode\Decoder;
use Xyp\Internal\Decode\Schemas;
use Xyp\Internal\MismatchReporter;
use Xyp\Mismatch;
use Xyp\Tests\Models\Lists;
use Xyp\Tests\Models\NoDefault;
use Xyp\Tests\Models\Renamed;
use Xyp\Tests\Models\Sample;
use Xyp\Tests\Models\SampleAddress;
use Xyp\Tests\Models\SampleYear;
use Xyp\Tests\Models\UntypedList;
use Xyp\Tests\Models\WithBadNested;
use Xyp\Tests\Models\WithDateTime;
use Xyp\Tests\Models\WithUnion;
use Xyp\Tests\Support\RecordingLogger;

final class DecoderTest extends TestCase
{
    public function testCoercesTextByFieldTypeAndLeavesMissingFieldsAtTheirDefault(): void
    {
        $tree = [
            'firstName' => 'Бат',
            'age' => '34',
            'active' => 'true',
            'born' => '1990-05-01T00:00:00+08:00',
            'photo' => 'AAE=',
            'amount' => '1234567890123456789.50',
            'ratio' => '0.5',
            'address' => ['city' => 'УБ'],
            'anything' => ['kept' => 'as is'],
            'unknown' => 'only in raw',
        ];
        [$out, $mismatches] = Decoder::decode(Sample::class, $tree);

        self::assertSame([], $mismatches);
        self::assertSame('Бат', $out->firstName);
        self::assertSame(34, $out->age);
        self::assertTrue($out->active);
        self::assertEquals(new \DateTimeImmutable('1990-05-01T00:00:00+08:00'), $out->born?->time);
        self::assertSame('1990-05-01T00:00:00+08:00', $out->born?->raw);
        self::assertSame("\x00\x01", $out->photo);
        self::assertSame('1234567890123456789.50', $out->amount, 'a decimal stays text');
        self::assertSame(0.5, $out->ratio);
        self::assertEquals(new SampleAddress('УБ'), $out->address);
        self::assertSame(['kept' => 'as is'], $out->anything);
        self::assertSame([], $out->listData, 'an absent list is empty');
        self::assertSame([], $out->xyp->mismatches);
        self::assertSame($tree, $out->xyp->raw, 'Extras::$raw holds the whole response');
    }

    public function testZeroAndFalseAreRealAnswers(): void
    {
        [$out] = Decoder::decode(Sample::class, ['age' => '0', 'active' => 'false', 'ratio' => '0']);

        self::assertSame(0, $out->age);
        self::assertFalse($out->active);
        self::assertSame(0.0, $out->ratio);
    }

    public function testTreatsASingleItemAsAOneItemList(): void
    {
        [$out, $mismatches] = Decoder::decode(Sample::class, ['listData' => ['year' => '2020']]);

        self::assertSame([], $mismatches);
        self::assertEquals([new SampleYear(2020)], $out->listData);
    }

    public function testDropsNullItemsWithoutComplaining(): void
    {
        [$out, $mismatches] = Decoder::decode(Sample::class, ['listData' => [['year' => '1'], null]]);

        self::assertSame([], $mismatches);
        self::assertEquals([new SampleYear(1)], $out->listData);
    }

    public function testAcceptsAMissingResponse(): void
    {
        [$out, $mismatches] = Decoder::decode(Sample::class, null);

        self::assertSame([], $mismatches);
        self::assertEquals(new Sample(), $out);
    }

    public function testNeverFailsTheCallAndSaysWhichFieldDidNotFit(): void
    {
        [$out, $mismatches] = Decoder::decode(Sample::class, [
            'firstName' => 'Бат',
            'age' => 'not-a-number-РД00000000',
            'listData' => [['year' => '2020'], ['year' => 'MMXXI']],
            'address' => 'just text',
        ]);

        self::assertSame('Бат', $out->firstName, 'a field that fits is still decoded');
        self::assertNull($out->age);
        // A nested field that does not fit only costs that field, not the whole list.
        self::assertEquals([new SampleYear(2020), new SampleYear()], $out->listData);
        self::assertNull($out->address);
        self::assertSame(
            [['age', 'not a valid int', 'not-a-number-РД00000000'], ['listData[1].year', 'not a valid int', 'MMXXI'], ['address', 'expected an object', 'just text']],
            array_map(static fn(Mismatch $mismatch): array => [$mismatch->path, $mismatch->problem, $mismatch->value()], $mismatches),
        );
        self::assertSame($mismatches, $out->xyp->mismatches);
    }

    public function testNeverPutsADefaultInsideAList(): void
    {
        [$out, $mismatches] = Decoder::decode(Lists::class, [
            'years' => ['2020', 'MMXXI'],
            'listData' => [['year' => '1'], 'text'],
        ]);

        self::assertSame([], $out->years);
        self::assertSame([], $out->listData);
        self::assertSame(['years[1]', 'listData[1]'], array_map(static fn(Mismatch $mismatch): string => $mismatch->path, $mismatches));
    }

    public function testCountsListIndexesOverTheItemsThatCarryData(): void
    {
        [, $mismatches] = Decoder::decode(Lists::class, ['years' => [null, '1', null, 'x']]);

        self::assertSame(['years[1]'], array_map(static fn(Mismatch $mismatch): string => $mismatch->path, $mismatches));
    }

    public function testFillsListsOfEverySupportedItemType(): void
    {
        [$out, $mismatches] = Decoder::decode(Lists::class, [
            'names' => ['a', 'b'],
            'anything' => ['text', ['nested' => 'value']],
            'amounts' => '1.5',
            'dates' => ['2024-01-31', '31.01.2024'],
            'years' => ['1', '2.0'],
            'photos' => 'AAE=',
            'flags' => ['yes', 'off'],
            'ratios' => ['1e3', '.5'],
        ]);

        self::assertSame([], $mismatches);
        self::assertSame(['a', 'b'], $out->names);
        self::assertSame(['text', ['nested' => 'value']], $out->anything);
        self::assertSame(['1.5'], $out->amounts, 'a single value is wrapped in a list');
        self::assertCount(2, $out->dates);
        self::assertNotNull($out->dates[0]->time);
        self::assertSame('31.01.2024', $out->dates[1]->raw);
        self::assertNull($out->dates[1]->time);
        self::assertSame([1, 2], $out->years);
        self::assertSame(["\x00\x01"], $out->photos);
        self::assertSame([true, false], $out->flags);
        self::assertSame([1000.0, 0.5], $out->ratios);
    }

    #[DataProvider('dates')]
    public function testReadsDatesLeniently(string $text, bool $hasTime): void
    {
        [$out, $mismatches] = Decoder::decode(Sample::class, ['born' => $text]);

        self::assertSame([], $mismatches, 'a date is never a mismatch');
        $born = $out->born;
        self::assertNotNull($born);
        self::assertSame($text, $born->raw);
        self::assertSame($hasTime, $born->time !== null);
    }

    /**
     * @return iterable<string, array{string, bool}>
     */
    public static function dates(): iterable
    {
        yield 'date' => ['2024-01-31', true];
        yield 'space separator' => ['2024-01-31 12:00:00', true];
        yield 'offset without a colon' => ['2024-01-31T12:00:00+0800', true];
        yield 'a year is not a timestamp' => ['2020', false];
        yield 'undocumented format' => ['31.01.2024', false];
    }

    public function testReportsTextInABytesFieldInsteadOfGarbage(): void
    {
        // "none" is left out: four alphabet characters are valid base64 in any decoder.
        foreach (['N/A', '0', 'NoImage', 'байхгүй', 'AAE', 'AA=E'] as $text) {
            [$out, $mismatches] = Decoder::decode(Sample::class, ['photo' => $text]);
            self::assertNull($out->photo, $text);
            self::assertCount(1, $mismatches, $text);
            self::assertSame($text, $mismatches[0]->value());
            self::assertSame('not a valid bytes', $mismatches[0]->problem);
        }
        [$wrapped, $mismatches] = Decoder::decode(Sample::class, ['photo' => "AA\r\nE="]);
        self::assertSame([], $mismatches, 'line-wrapped base64 must decode');
        self::assertSame("\x00\x01", $wrapped->photo);
    }

    public function testReadsHandTypedBooleansAndIntegers(): void
    {
        foreach (['Y', 'yes', 'T', 'on', 'TRUE', '1'] as $text) {
            self::assertTrue(Decoder::decode(Sample::class, ['active' => $text])[0]->active, $text);
        }
        foreach (['N', 'no', 'F', 'off', '0', 'False'] as $text) {
            self::assertFalse(Decoder::decode(Sample::class, ['active' => $text])[0]->active, $text);
        }
        self::assertNull(Decoder::decode(Sample::class, ['active' => 'maybe'])[0]->active);
        self::assertSame(34, Decoder::decode(Sample::class, ['age' => '34.0'])[0]->age, '"34.0" is an integer written by a spreadsheet');
        [$fractional, $mismatches] = Decoder::decode(Sample::class, ['age' => '34.5']);
        self::assertNull($fractional->age);
        self::assertCount(1, $mismatches);
    }

    public function testRejectsIntegersThatDoNotFitIn64Bits(): void
    {
        [$out, $mismatches] = Decoder::decode(Sample::class, ['age' => '9223372036854775808']);

        self::assertNull($out->age);
        self::assertCount(1, $mismatches);
        self::assertSame('not a valid int', $mismatches[0]->problem);
    }

    public function testRejectsDecimalsAndFloatsThatAreNotNumbers(): void
    {
        [$out, $mismatches] = Decoder::decode(Sample::class, ['amount' => '1,5', 'ratio' => '1e999']);

        self::assertNull($out->amount);
        self::assertNull($out->ratio);
        self::assertSame(['not a valid decimal', 'not a valid float'], array_map(static fn(Mismatch $mismatch): string => $mismatch->problem, $mismatches));
    }

    public function testReportsAResponseThatIsNotAnObject(): void
    {
        [$out, $mismatches] = Decoder::decode(Sample::class, 'just text');

        self::assertNull($out->firstName);
        self::assertCount(1, $mismatches);
        self::assertSame('', $mismatches[0]->path);
        self::assertSame('expected an object', $mismatches[0]->problem);
        self::assertSame('<response> (expected an object)', (string) $mismatches[0]);
        self::assertSame('just text', $out->xyp->raw);
    }

    #[DataProvider('shapes')]
    public function testNamesTheShapeItGotForAScalar(mixed $node, string $want): void
    {
        [, $mismatches] = Decoder::decode(Sample::class, ['firstName' => $node]);

        self::assertCount(1, $mismatches);
        self::assertSame($want, $mismatches[0]->problem);
    }

    /**
     * @return iterable<string, array{mixed, string}>
     */
    public static function shapes(): iterable
    {
        yield 'list' => [['a', 'b'], 'expected string, got a list'];
        yield 'object' => [['a' => 'b'], 'expected string, got an object'];
    }

    public function testReadsAWireNameAndASelfReferencingClass(): void
    {
        [$out, $mismatches] = Decoder::decode(Renamed::class, ['this' => 'a', 'child' => ['this' => 'b']]);

        self::assertSame([], $mismatches);
        self::assertSame('a', $out->this_);
        self::assertSame('b', $out->child?->this_);
    }

    /**
     * @param class-string $class
     */
    #[DataProvider('undecodableClasses')]
    public function testRejectsAClassItCannotFillBeforeLookingAtData(string $class, string $want): void
    {
        try {
            // The class is checked before the data, so the error does not depend on
            // whether XYP happened to send the field.
            Schemas::of($class);
            self::fail('no ConfigException');
        } catch (ConfigException $error) {
            self::assertStringContainsString($want, $error->getMessage());
        }
    }

    /**
     * @return iterable<string, array{class-string, string}>
     */
    public static function undecodableClasses(): iterable
    {
        yield 'no default' => [NoDefault::class, 'needs a default value'];
        yield 'array without ListOf' => [UntypedList::class, '#[ListOf'];
        yield 'internal class field' => [WithDateTime::class, 'DateTimeImmutable is not a response class'];
        yield 'union type' => [WithUnion::class, 'union'];
        yield 'bad nested class' => [WithBadNested::class, 'NoDefault::__construct() parameter $name needs a default value'];
        yield 'no constructor' => [\stdClass::class, 'stdClass is not a response class'];
        yield 'extras only' => [Extras::class, 'Extras::__construct() parameter $mismatches is an array without #[ListOf'];
    }

    public function testRejectsANameThatIsNotAClass(): void
    {
        // class-string rules this out for callers with static analysis; the rest get a
        // ConfigException rather than a ReflectionException.
        $this->expectException(ConfigException::class);
        $this->expectExceptionMessage('DoesNotExist is not a class');
        (new \ReflectionMethod(Schemas::class, 'of'))->invoke(null, 'Xyp\\Tests\\Models\\DoesNotExist');
    }

    public function testTheWarningNamesPathsButNeverValues(): void
    {
        $logger = new RecordingLogger();
        [, $mismatches] = Decoder::decode(Sample::class, ['age' => 'РД00000000', 'photo' => 'N/A']);

        (new MismatchReporter($logger))->report('WS100101_getCitizenIDCardInfo', $mismatches);

        self::assertCount(1, $logger->records, 'exactly one warning per call');
        self::assertSame('warning', $logger->records[0]['level']);
        self::assertSame(MismatchReporter::WARNING, $logger->records[0]['message']);
        self::assertSame([
            'operation' => 'WS100101_getCitizenIDCardInfo',
            'fields' => 'age (not a valid int); photo (not a valid bytes)',
        ], $logger->records[0]['context']);
        foreach (["gap in the SDK's models", MismatchReporter::ISSUES_URL, '->xyp->mismatches'] as $want) {
            self::assertStringContainsString($want, $logger->dump());
        }
        self::assertStringNotContainsString('РД00000000', $logger->dump(), 'citizen data reached the log');
    }

    public function testNoMismatchesMeansNoWarning(): void
    {
        $logger = new RecordingLogger();
        (new MismatchReporter($logger))->report('WS100101_getCitizenIDCardInfo', []);

        self::assertSame([], $logger->records);
    }
}
