<?php

declare(strict_types=1);

namespace Xyp\Tests\Internal;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Xyp\Auth;
use Xyp\AuthType;
use Xyp\Bytes;
use Xyp\Date;
use Xyp\Exception\ConfigException;
use Xyp\Exception\Origin;
use Xyp\Internal\Envelope;
use Xyp\Tests\Models\RequestInput;

final class EnvelopeTest extends TestCase
{
    private const NAMESPACE = 'http://citizen.xyp.gov.mn/';

    private const OPERATION = 'WS100101_getCitizenIDCardInfo';

    public function testOmitsEmptyFieldsRepeatsListsAndNestsObjects(): void
    {
        $input = new RequestInput(flag: false, ids: [1, 2], nested: ['code' => 'A', 'empty' => ''], photo: "\x00\x01", className: 'c');

        $envelope = Envelope::build(self::OPERATION, self::NAMESPACE, $input);

        self::assertStringContainsString(
            '<request><flag>0</flag><ids>1</ids><ids>2</ids><nested><code>A</code></nested>'
            . '<photo>AAE=</photo><class>c</class></request>',
            $envelope,
        );
        self::assertStringNotContainsString($input->secret(), $envelope, 'a private property reached the wire');
    }

    /**
     * @param array<mixed>|object|null $params
     */
    #[DataProvider('paramShapes')]
    public function testAcceptsEveryParamShape(array|object|null $params, string $want): void
    {
        self::assertStringContainsString($want, Envelope::build(self::OPERATION, self::NAMESPACE, $params));
    }

    /**
     * @return iterable<string, array{array<mixed>|object|null, string}>
     */
    public static function paramShapes(): iterable
    {
        $moment = new \DateTimeImmutable('2024-01-31T12:00:00Z');

        yield 'null' => [null, '<request />'];
        yield 'empty array' => [[], '<request />'];
        yield 'array order is kept' => [['b' => '2', 'a' => '1'], '<request><b>2</b><a>1</a></request>'];
        yield 'object' => [new RequestInput(skipped: 'A'), '<request><skipped>A</skipped></request>'];
        yield 'stdClass' => [(object) ['b' => 1, 'a' => 2], '<request><b>1</b><a>2</a></request>'];
        yield 'time is UTC RFC 3339' => [['at' => $moment], '<request><at>2024-01-31T12:00:00Z</at></request>'];
        yield 'time in another zone is converted' => [
            ['at' => new \DateTime('2024-01-31T20:00:00.250+08:00')],
            '<request><at>2024-01-31T12:00:00.25Z</at></request>',
        ];
        yield 'a raw date is sent as it stands' => [['at' => new Date('31.01.2024')], '<request><at>31.01.2024</at></request>'];
        yield 'an empty date is left out' => [['at' => new Date()], '<request />'];
        yield 'a date with only a time is formatted' => [['at' => new Date('', $moment)], '<request><at>2024-01-31T12:00:00Z</at></request>'];
        yield 'floats keep their shortest form' => [['n' => 0.5, 'm' => 1e21, 'k' => 1e-7], '<request><n>0.5</n><m>1000000000000000000000</m><k>0.0000001</k></request>'];
        yield 'bool is 1 or 0' => [['t' => true, 'f' => false], '<request><t>1</t><f>0</f></request>'];
        yield 'bytes are base64' => [['b' => new Bytes("\x00\x01")], '<request><b>AAE=</b></request>'];
        yield 'empty bytes are an empty element' => [['b' => new Bytes('')], '<request><b /></request>'];
        yield 'a backed enum sends its value' => [['t' => AuthType::DanApp], '<request><t>5</t></request>'];
        yield 'null, empty string and empty list are left out' => [['a' => null, 'b' => '', 'c' => []], '<request />'];
        yield 'an object whose fields are all empty is an empty element' => [['o' => ['a' => null]], '<request><o /></request>'];
        yield 'a list of objects repeats the element' => [
            ['row' => [['a' => '1'], ['a' => '2']]],
            '<request><row><a>1</a></row><row><a>2</a></row></request>',
        ];
        yield 'text keeps quotes and line breaks' => [['t' => "a \"b\"\nc"], "<request><t>a \"b\"\nc</t></request>"];
    }

    /**
     * @param array<mixed> $params
     */
    #[DataProvider('unsendableValues')]
    public function testRejectsAValueItCannotSend(array $params): void
    {
        try {
            Envelope::build(self::OPERATION, self::NAMESPACE, $params);
            self::fail('no ConfigException');
        } catch (ConfigException $error) {
            self::assertSame(Origin::Config, $error->origin());
        }
    }

    /**
     * @return iterable<string, array{array<mixed>}>
     */
    public static function unsendableValues(): iterable
    {
        yield 'closure' => [['bad' => static fn(): int => 1]];
        yield 'resource' => [['bad' => STDIN]];
        yield 'NaN' => [['bad' => NAN]];
        yield 'infinity' => [['bad' => INF]];
        yield 'unit enum' => [['bad' => UnitEnumFixture::One]];
    }

    public function testRejectsAListAsTheParams(): void
    {
        $this->expectException(ConfigException::class);
        Envelope::build(self::OPERATION, self::NAMESPACE, ['a', 'b']);
    }

    public function testCannotBeUsedToInjectXml(): void
    {
        $envelope = Envelope::build(self::OPERATION, 'ns"/><evil', ['regnum' => '</regnum><admin>true</admin>']);

        self::assertStringContainsString('<regnum>&lt;/regnum&gt;&lt;admin&gt;true&lt;/admin&gt;</regnum>', $envelope);
        self::assertStringNotContainsString('<admin>', $envelope);
        self::assertStringNotContainsString('<evil', $envelope);
        self::assertStringContainsString('xmlns:tns="ns&quot;/&gt;&lt;evil"', $envelope);
    }

    public function testStartsWithTheDeclarationAndAuth(): void
    {
        $envelope = Envelope::build(self::OPERATION, self::NAMESPACE, ['regnum' => 'РД00000000'], Auth::otp('РД00000000', 1234));

        self::assertStringStartsWith("<?xml version='1.0' encoding='utf-8'?>\n<soap:Envelope ", $envelope);
        self::assertStringContainsString(
            '<request><auth><citizen><authType>1</authType><otp>1234</otp><regnum>РД00000000</regnum></citizen></auth><regnum>',
            $envelope,
        );
    }

    public function testWritesAuthFieldsInWsdlOrderAndAlwaysSendsOtp(): void
    {
        $everything = new Auth(
            regnum: 'R',
            civilId: 'C',
            authType: AuthType::DigitalSignature,
            otp: 7,
            fingerprint: "\x00\x01",
            signature: 'S',
            certFingerprint: 'F',
            appAuthToken: 'T',
            authAppName: 'N',
        );
        $envelope = Envelope::build(self::OPERATION, self::NAMESPACE, null, $everything, new Auth());

        self::assertStringContainsString(
            '<auth><citizen><appAuthToken>T</appAuthToken><authAppName>N</authAppName><authType>2</authType>'
            . '<certFingerprint>F</certFingerprint><civilId>C</civilId><fingerprint>AAE=</fingerprint>'
            . '<otp>7</otp><regnum>R</regnum><signature>S</signature></citizen>'
            . '<operator><otp>0</otp></operator></auth>',
            $envelope,
        );
    }

    /**
     * @param array<mixed>|null $params
     */
    #[DataProvider('invalidNames')]
    public function testRejectsNamesThatAreNotXmlNames(string $operation, ?array $params): void
    {
        $this->expectException(ConfigException::class);
        Envelope::build($operation, self::NAMESPACE, $params);
    }

    /**
     * @return iterable<string, array{string, array<mixed>|null}>
     */
    public static function invalidNames(): iterable
    {
        yield 'param name' => [self::OPERATION, ['a><admin>1</admin><b' => 'x']];
        yield 'nested name' => [self::OPERATION, ['ok' => ['b c' => 'x']]];
        yield 'empty name' => [self::OPERATION, ['' => 'x']];
        yield 'integer key' => [self::OPERATION, ['a' => 'x', 5 => 'y']];
        yield 'operation' => ['WS1><evil', null];
        yield 'trailing newline' => [self::OPERATION, ["a\n" => 'x']];
    }
}
