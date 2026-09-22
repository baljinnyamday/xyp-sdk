<?php

declare(strict_types=1);

namespace Xyp\Tests\Internal;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Xyp\Exception\NotFoundException;
use Xyp\Exception\Origin;
use Xyp\Exception\ResponseException;
use Xyp\Http\Response;
use Xyp\Internal\ResponseReader;
use Xyp\Internal\XmlTree;
use Xyp\Tests\Support\Soap;

final class ResponseReaderTest extends TestCase
{
    public function testReadsTheDocumentedShape(): void
    {
        $result = ResponseReader::parse(Soap::response(
            '<firstname>Бат &amp; &#1041;</firstname><empty/><gone xsi:nil="true"/>'
            . '<listData><year>2020</year></listData><listData><year>2021</year></listData>'
            . '<one><x>1</x></one><one><x>2</x></one><one><x>3</x></one><cdata><![CDATA[a<b]]></cdata>',
            0,
            'амжилттай',
        ));

        self::assertSame(Soap::REQUEST_ID, $result->requestId);
        self::assertSame(0, $result->resultCode);
        self::assertSame('амжилттай', $result->message);
        self::assertSame([
            'firstname' => 'Бат & Б',
            'empty' => null,
            'gone' => null,
            'listData' => [['year' => '2020'], ['year' => '2021']],
            'one' => [['x' => '1'], ['x' => '2'], ['x' => '3']],
            'cdata' => 'a<b',
        ], $result->data);
    }

    public function testKeepsNumericLookingTextAsText(): void
    {
        $result = ResponseReader::parse(Soap::response('<regnum>0012</regnum><phone>+97699</phone>'));

        self::assertSame(['regnum' => '0012', 'phone' => '+97699'], $result->data);
    }

    public function testTrimsUnicodeWhiteSpaceLikeTheOtherSdks(): void
    {
        $result = ResponseReader::parse(Soap::response("<name>\u{00A0} Бат\n\t</name><blank>\u{2003}</blank>"));

        self::assertSame(['name' => 'Бат', 'blank' => null], $result->data);
    }

    public function testNilWinsOverContentAndRepeatedNilsStayInTheList(): void
    {
        $xsi = 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"';
        $result = ResponseReader::parse(Soap::response(
            "<a xsi:nil=\"1\" {$xsi}>text</a><b xsi:nil=\"false\" {$xsi}>kept</b><c>1</c><c xsi:nil=\"true\" {$xsi}/>",
        ));

        self::assertSame(['a' => null, 'b' => 'kept', 'c' => ['1', null]], $result->data);
    }

    public function testGivesNullDataForAnEmptyResponseElement(): void
    {
        $result = ResponseReader::parse(Soap::response('', 1, 'олдсонгүй'));

        self::assertNull($result->data);
        self::assertSame(1, $result->resultCode);
    }

    #[DataProvider('brokenPayloads')]
    public function testTurnsFaultsGarbageAndDtdsIntoResponseExceptions(string $payload, string $want): void
    {
        try {
            ResponseReader::parse($payload);
            self::fail('no ResponseException');
        } catch (ResponseException $error) {
            self::assertStringContainsString($want, $error->getMessage());
            self::assertSame(Origin::Xyp, $error->origin());
            self::assertNull($error->statusCode);
        }
    }

    /**
     * @return iterable<string, array{string, string}>
     */
    public static function brokenPayloads(): iterable
    {
        yield 'fault' => [Soap::fault('Unmarshalling Error'), 'XYP returned a SOAP fault: Unmarshalling Error'];
        yield 'fault without a reason' => [Soap::fault(''), 'unknown SOAP fault'];
        yield 'gateway page' => ['<html>gateway timeout', 'not valid XML'];
        yield 'empty body' => ['', 'not valid XML'];
        yield 'not XML at all' => ['Bad Gateway', 'not valid XML'];
        yield 'no return element' => ['<a/>', '<return>'];
        yield 'entity bomb' => ['<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><x>&a;</x>', 'not valid XML'];
        yield 'external entity' => [
            '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><x>&e;</x>',
            'not valid XML',
        ];
        yield 'bare doctype' => ['<!DOCTYPE html><return><resultCode>0</resultCode></return>', 'not valid XML'];
        yield 'undeclared entity' => ['<return><resultCode>0</resultCode><x>&nope;</x></return>', 'not valid XML'];
        yield 'two roots' => ['<return><resultCode>0</resultCode></return><extra/>', 'not valid XML'];
    }

    public function testParsingLeavesNoLibxmlStateBehind(): void
    {
        $before = libxml_use_internal_errors(false);
        try {
            try {
                XmlTree::parse('<broken');
            } catch (ResponseException) {
            }
            self::assertFalse(libxml_use_internal_errors(false), 'the caller\'s libxml setting was not restored');
            self::assertSame([], libxml_get_errors());
        } finally {
            libxml_use_internal_errors($before);
        }
    }

    public function testRequiresANumericResultCode(): void
    {
        ResponseReader::parse(Soap::response(''));
        $this->expectException(ResponseException::class);
        $this->expectExceptionMessage('numeric <resultCode>');
        ResponseReader::parse(str_replace('<resultCode>0</resultCode>', '<resultCode>ok</resultCode>', Soap::response('')));
    }

    public function testRejectsAResultCodeOutsideTheIntegerRange(): void
    {
        $this->expectExceptionMessage('numeric <resultCode>');
        ResponseReader::parse(str_replace('<resultCode>0</resultCode>', '<resultCode>99999999999999999999</resultCode>', Soap::response('')));
    }

    public function testUnwrapReturnsTheDataOfASuccessfulCall(): void
    {
        self::assertSame(['firstname' => 'Бат', 'regnum' => Soap::REGNUM], ResponseReader::unwrap(new Response(200, Soap::response(Soap::ID_CARD))));
    }

    public function testUnwrapTurnsAResultCodeIntoAnApiException(): void
    {
        try {
            ResponseReader::unwrap(new Response(200, Soap::response('', 1, 'олдсонгүй')));
            self::fail('no ApiException');
        } catch (NotFoundException $error) {
            self::assertSame(1, $error->resultCode);
            self::assertSame('олдсонгүй', $error->resultMessage);
            self::assertSame(Soap::REQUEST_ID, $error->requestId);
            self::assertSame('[1] олдсонгүй', $error->getMessage());
        }
    }

    public function testUnwrapKeepsTheFaultTextOfAnHttp500(): void
    {
        try {
            ResponseReader::unwrap(new Response(500, Soap::fault('Unmarshalling Error: unexpected element')));
            self::fail('no ResponseException');
        } catch (ResponseException $error) {
            self::assertSame(500, $error->statusCode);
            self::assertStringContainsString('HTTP 500', $error->getMessage());
            self::assertStringContainsString('Unmarshalling Error: unexpected element', $error->getMessage());
            self::assertInstanceOf(ResponseException::class, $error->getPrevious());
        }
    }

    public function testUnwrapReportsAGatewayPageWithItsStatus(): void
    {
        try {
            ResponseReader::unwrap(new Response(502, '<html>Bad Gateway</html>'));
            self::fail('no ResponseException');
        } catch (ResponseException $error) {
            self::assertSame(502, $error->statusCode);
            self::assertStringContainsString('HTTP 502', $error->getMessage());
        }
    }

    public function testUnwrapStillReadsAValidReturnSentWithAnErrorStatus(): void
    {
        $this->expectException(NotFoundException::class);
        ResponseReader::unwrap(new Response(500, Soap::response('', 1, 'олдсонгүй')));
    }
}
