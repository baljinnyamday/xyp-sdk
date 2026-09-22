<?php

declare(strict_types=1);

namespace Xyp\Tests;

use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;
use Xyp\Auth;
use Xyp\Citizen\GetCitizenIDCardInfoParams;
use Xyp\Exception\ConfigException;
use Xyp\Exception\ConnectionException;
use Xyp\Exception\NotFoundException;
use Xyp\Exception\Origin;
use Xyp\Exception\ResponseException;
use Xyp\Exception\TimeoutException;
use Xyp\Http\Request;
use Xyp\Http\Response;
use Xyp\Insurance\GetCitizenPensionInquiryParams;
use Xyp\Tests\Models\IdCard;
use Xyp\Tests\Models\NoDefault;
use Xyp\Tests\Support\FakeTransport;
use Xyp\Tests\Support\Keys;
use Xyp\Tests\Support\RecordingLogger;
use Xyp\Tests\Support\Soap;
use Xyp\XypClient;

final class XypClientTest extends TestCase
{
    private const TOKEN = 'test-access-token';

    private const BASE_URL = 'https://xyp.test';

    public function testCallsATypedServiceEndToEnd(): void
    {
        $transport = FakeTransport::answering(Soap::response(Soap::ID_CARD));
        $xyp = $this->client($transport);

        $card = $xyp->citizen->getCitizenIDCardInfo(
            new GetCitizenIDCardInfoParams(regnum: Soap::REGNUM),
            auth: Auth::otp(Soap::REGNUM, 1234),
        );

        self::assertSame('Бат', $card->firstname);
        self::assertSame([], $card->xyp->mismatches);
        self::assertSame(['firstname' => 'Бат', 'regnum' => Soap::REGNUM], $card->xyp->raw);
        self::assertCount(1, $transport->requests);
        $request = $transport->requests[0];
        self::assertSame('POST', $request->method);
        self::assertSame(self::BASE_URL . '/citizen-1.5.0/ws', $request->url);
        self::assertSame(['Content-Type', 'SOAPAction', 'accessToken', 'timeStamp', 'signature'], array_keys($request->headers));
        self::assertSame('text/xml; charset=utf-8', $request->headers['Content-Type']);
        self::assertSame('""', $request->headers['SOAPAction']);
        self::assertSame(self::TOKEN, $request->headers['accessToken']);
        self::assertMatchesRegularExpression('/^\d+$/D', $request->headers['timeStamp']);
        self::assertEqualsWithDelta(time(), (int) $request->headers['timeStamp'], 5);
        $public = openssl_pkey_get_public(Keys::publicPem(Keys::rsa()));
        self::assertNotFalse($public);
        self::assertSame(1, openssl_verify(
            self::TOKEN . '.' . $request->headers['timeStamp'],
            (string) base64_decode($request->headers['signature'], true),
            $public,
            OPENSSL_ALGO_SHA256,
        ));
        foreach ([
            'xmlns:tns="http://citizen.xyp.gov.mn/"',
            '<tns:WS100101_getCitizenIDCardInfo><request><auth><citizen>',
            '<otp>1234</otp><regnum>' . Soap::REGNUM . '</regnum>',
        ] as $want) {
            self::assertStringContainsString($want, $request->body);
        }
    }

    public function testSendsInputsInSchemaOrderWhateverOrderTheCallerUsed(): void
    {
        $transport = FakeTransport::answering(Soap::response(Soap::ID_CARD));

        $this->client($transport)->citizen->getCitizenIDCardInfo(new GetCitizenIDCardInfoParams(regnum: Soap::REGNUM, civilId: '1'));

        self::assertStringContainsString('<civilId>1</civilId><regnum>' . Soap::REGNUM . '</regnum>', $transport->requests[0]->body);
    }

    public function testSendsTheOperatorsApprovalAfterTheCitizens(): void
    {
        $transport = FakeTransport::answering(Soap::response(Soap::ID_CARD));

        $this->client($transport)->call('WS100101_getCitizenIDCardInfo', null, Auth::otp('A', 1), Auth::fingerprint('B', "\x00\x01"));

        self::assertStringContainsString(
            '<auth><citizen><authType>1</authType><otp>1</otp><regnum>A</regnum></citizen>'
            . '<operator><authType>3</authType><fingerprint>AAE=</fingerprint><otp>0</otp><regnum>B</regnum></operator></auth>',
            $transport->requests[0]->body,
        );
    }

    public function testCallByOriginalNameReturnsTheRawTree(): void
    {
        $xyp = $this->client(FakeTransport::answering(Soap::response(Soap::ID_CARD)));

        self::assertSame(
            ['firstname' => 'Бат', 'regnum' => Soap::REGNUM],
            $xyp->call('WS100101_getCitizenIDCardInfo', ['regnum' => Soap::REGNUM]),
        );
    }

    public function testAnUnknownOperationPointsAtTheEndpointArgument(): void
    {
        $transport = FakeTransport::answering(Soap::response(''));

        try {
            $this->client($transport)->call('WS999999_doesNotExist');
            self::fail('no ConfigException');
        } catch (ConfigException $error) {
            self::assertStringContainsString('unknown operation "WS999999_doesNotExist"', $error->getMessage());
            self::assertStringContainsString('endpoint:', $error->getMessage());
            self::assertSame(Origin::Config, $error->origin());
        }
        self::assertSame([], $transport->requests);
    }

    public function testCallAcceptsAnEndpointTheRegistryDoesNotKnow(): void
    {
        $transport = new FakeTransport(static fn(Request $request): Response => $request->method === 'GET'
            ? new Response(200, '<wsdl:definitions targetNamespace="http://brand.new/">')
            : new Response(200, Soap::response(Soap::ID_CARD)));

        $this->client($transport)->call('WS109999_brandNew', null, endpoint: 'citizen-9.9.9');

        self::assertSame(self::BASE_URL . '/citizen-9.9.9/ws?WSDL', $transport->requests[0]->url);
        self::assertSame([], $transport->requests[0]->headers, 'the WSDL is public; it is fetched unsigned');
        self::assertSame(self::BASE_URL . '/citizen-9.9.9/ws', $transport->requests[1]->url);
        self::assertStringContainsString('xmlns:tns="http://brand.new/"', $transport->requests[1]->body);
        self::assertStringContainsString('<tns:WS109999_brandNew><request />', $transport->requests[1]->body);
    }

    public function testReadsAnUnknownNamespaceFromTheWsdlOncePerClient(): void
    {
        $transport = new FakeTransport(static fn(Request $request): Response => $request->method === 'GET'
            ? new Response(200, '<wsdl:definitions targetNamespace="http://insurance.example/">')
            : new Response(200, Soap::response('<isPensioner>true</isPensioner>')));
        $xyp = $this->client($transport);
        $params = new GetCitizenPensionInquiryParams(regnum: Soap::REGNUM);

        $first = $xyp->insurance->getCitizenPensionInquiry($params);
        $xyp->insurance->getCitizenPensionInquiry($params);

        self::assertTrue($first->isPensioner);
        self::assertSame([self::BASE_URL . '/insurance-1.5.0/ws?WSDL'], array_map(static fn(Request $request): string => $request->url, $transport->gets()));
        self::assertCount(3, $transport->requests);
        self::assertStringContainsString('xmlns:tns="http://insurance.example/"', $transport->requests[2]->body, 'the learned namespace was not reused');

        $this->client($transport)->insurance->getCitizenPensionInquiry($params);
        self::assertCount(2, $transport->gets(), 'the cache belongs to one client');
    }

    /**
     * @param \Closure(Request): Response $answer
     */
    #[DataProvider('unreadableWsdls')]
    public function testAnUnreadableWsdlIsAResponseException(\Closure $answer, int $status): void
    {
        try {
            $this->client(new FakeTransport($answer))->call('WS109999_brandNew', endpoint: 'citizen-9.9.9');
            self::fail('no ResponseException');
        } catch (ResponseException $error) {
            self::assertSame('could not read the WSDL of endpoint "citizen-9.9.9"', $error->getMessage());
            self::assertSame($status, $error->statusCode);
        }
    }

    /**
     * @return iterable<string, array{\Closure(Request): Response, int}>
     */
    public static function unreadableWsdls(): iterable
    {
        yield 'not found' => [static fn(): Response => new Response(404, 'targetNamespace="http://error.page/"'), 404];
        yield 'no namespace' => [static fn(): Response => new Response(200, '<html>login</html>'), 200];
    }

    #[DataProvider('badEndpoints')]
    public function testRejectsAnEndpointThatIsNotAPathSegment(string $endpoint): void
    {
        $transport = FakeTransport::answering(Soap::response(''));

        try {
            $this->client($transport)->call('WS100101_getCitizenIDCardInfo', endpoint: $endpoint);
            self::fail('no ConfigException');
        } catch (ConfigException) {
        }
        self::assertSame([], $transport->requests);
    }

    /**
     * @return iterable<string, array{string}>
     */
    public static function badEndpoints(): iterable
    {
        foreach (['../admin', 'citizen-1.5.0/ws?x=', 'a b', '..', '', '.hidden', "citizen\n"] as $endpoint) {
            yield var_export($endpoint, true) => [$endpoint];
        }
    }

    public function testABadParameterCostsNoRequestNotEvenForTheWsdl(): void
    {
        $transport = FakeTransport::answering('targetNamespace="http://x/"');

        try {
            $this->client($transport)->call('WS109999_brandNew', ['bad name' => 'x'], endpoint: 'citizen-9.9.9');
            self::fail('no ConfigException');
        } catch (ConfigException) {
        }
        self::assertSame([], $transport->requests);
    }

    public function testInvokeChecksTheResponseClassBeforeSendingAnything(): void
    {
        $transport = FakeTransport::answering(Soap::response(Soap::ID_CARD));

        try {
            $this->client($transport)->invoke('WS100101_getCitizenIDCardInfo', null, NoDefault::class);
            self::fail('no ConfigException');
        } catch (ConfigException) {
        }
        self::assertSame([], $transport->requests, 'a class the SDK cannot fill must not cost a request to XYP');
    }

    public function testInvokeFillsACallersOwnClassAndWarnsOnceWithoutValues(): void
    {
        $logger = new RecordingLogger();
        $xyp = $this->client(FakeTransport::answering(Soap::response(Soap::ID_CARD . '<age>РД-99</age>')), logger: $logger);

        $card = $xyp->invoke('WS100101_getCitizenIDCardInfo', ['regnum' => Soap::REGNUM], IdCard::class);

        self::assertSame('Бат', $card->firstname);
        self::assertNull($card->age);
        self::assertCount(1, $card->xyp->mismatches);
        self::assertSame('РД-99', $card->xyp->mismatches[0]->value());
        self::assertCount(1, $logger->records);
        self::assertSame(['operation' => 'WS100101_getCitizenIDCardInfo', 'fields' => 'age (not a valid int)'], $logger->records[0]['context']);
        self::assertStringNotContainsString('РД-99', $logger->dump());
    }

    public function testWithoutALoggerTheWarningGoesToErrorLog(): void
    {
        $log = tempnam(sys_get_temp_dir(), 'xyp-log-');
        self::assertIsString($log);
        $previous = ini_set('error_log', $log);
        try {
            $xyp = $this->client(FakeTransport::answering(Soap::response('<age>РД-99</age>')));
            $xyp->invoke('WS100101_getCitizenIDCardInfo', null, IdCard::class);
            $written = (string) file_get_contents($log);
        } finally {
            ini_set('error_log', $previous === false ? '' : $previous);
            unlink($log);
        }

        self::assertStringContainsString("xyp: XYP's response does not fit the SDK's model", $written);
        self::assertStringContainsString('operation=WS100101_getCitizenIDCardInfo fields=age (not a valid int)', $written);
        self::assertStringNotContainsString('РД-99', $written);
    }

    public function testResultCodesBecomeExceptionsThatSayWhoseSideItIsOn(): void
    {
        $xyp = $this->client(FakeTransport::answering(Soap::response('', 1, 'олдсонгүй')));

        try {
            $xyp->citizen->getCitizenIDCardInfo(new GetCitizenIDCardInfoParams(regnum: Soap::REGNUM));
            self::fail('no ApiException');
        } catch (NotFoundException $error) {
            self::assertSame(1, $error->resultCode);
            self::assertSame(Soap::REQUEST_ID, $error->requestId);
            self::assertSame('[1] олдсонгүй', $error->getMessage());
            self::assertSame(Origin::Xyp, $error->origin());
        }
    }

    public function testAnHttp500FaultKeepsItsText(): void
    {
        $xyp = $this->client(FakeTransport::answering(Soap::fault('Unmarshalling Error: unexpected element'), 500));

        try {
            $xyp->call('WS100101_getCitizenIDCardInfo');
            self::fail('no ResponseException');
        } catch (ResponseException $error) {
            self::assertSame(500, $error->statusCode);
            self::assertStringContainsString('HTTP 500', $error->getMessage());
            self::assertStringContainsString('Unmarshalling Error: unexpected element', $error->getMessage());
        }
    }

    public function testTransportExceptionsReachTheCallerUnchanged(): void
    {
        $timeout = TimeoutException::timedOut();
        $xyp = $this->client(new FakeTransport(static fn(): Response => throw $timeout));

        try {
            $xyp->call('WS100101_getCitizenIDCardInfo');
            self::fail('no TimeoutException');
        } catch (TimeoutException $error) {
            self::assertSame($timeout, $error);
            self::assertSame(Origin::Network, $error->origin());
        }
    }

    public function testUnreachableXypBecomesAConnectionExceptionThatIsNotATimeout(): void
    {
        // Port 1 refuses connections on every platform CI runs on.
        $xyp = new XypClient(accessToken: self::TOKEN, privateKey: Keys::rsa(), baseUrl: 'http://127.0.0.1:1', timeout: 5.0);

        try {
            $xyp->call('WS100101_getCitizenIDCardInfo');
            self::fail('no ConnectionException');
        } catch (ConnectionException $error) {
            self::assertNotInstanceOf(TimeoutException::class, $error, 'a refused connection is not a timeout');
            self::assertStringContainsString('VPN', $error->getMessage());
            self::assertSame(Origin::Network, $error->origin());
        }
    }

    public function testNormalisesAndChecksTheBaseUrl(): void
    {
        $transport = FakeTransport::answering(Soap::response(''));
        (new XypClient(accessToken: self::TOKEN, privateKey: Keys::rsa(), baseUrl: 'HTTP://proxy.local//', transport: $transport))
            ->call('WS100101_getCitizenIDCardInfo');
        self::assertSame('HTTP://proxy.local/citizen-1.5.0/ws', $transport->requests[0]->url);

        $this->expectException(ConfigException::class);
        $this->expectExceptionMessage('https://');
        new XypClient(accessToken: self::TOKEN, privateKey: Keys::rsa(), baseUrl: 'xyp.gov.mn');
    }

    public function testDefaultsToTheProductionBaseUrl(): void
    {
        $xyp = new XypClient(accessToken: self::TOKEN, privateKey: Keys::rsa(), transport: FakeTransport::answering(''));

        self::assertSame(['baseUrl' => 'https://xyp.gov.mn'], $xyp->__debugInfo());
    }

    /**
     * @param \Closure(): XypClient $build
     */
    #[DataProvider('badOptions')]
    public function testRejectsBadOptions(\Closure $build, string $want): void
    {
        $this->expectException(ConfigException::class);
        $this->expectExceptionMessage($want);
        $build();
    }

    /**
     * @return iterable<string, array{\Closure(): XypClient, string}>
     */
    public static function badOptions(): iterable
    {
        $key = Keys::rsa();

        yield 'zero timeout' => [static fn(): XypClient => new XypClient(self::TOKEN, $key, timeout: 0.0), 'timeout'];
        yield 'negative timeout' => [static fn(): XypClient => new XypClient(self::TOKEN, $key, timeout: -1.0), 'timeout'];
        yield 'infinite timeout' => [static fn(): XypClient => new XypClient(self::TOKEN, $key, timeout: INF), 'timeout'];
        yield 'empty verify' => [static fn(): XypClient => new XypClient(self::TOKEN, $key, verify: ''), 'verify'];
        yield 'missing CA file' => [static fn(): XypClient => new XypClient(self::TOKEN, $key, verify: '/nonexistent/ca.pem'), 'no such file'];
        yield 'token with a newline' => [static fn(): XypClient => new XypClient("token\n", $key), 'line break'];
        yield 'EC key' => [static fn(): XypClient => new XypClient(self::TOKEN, Keys::ec()), 'RSA'];
        yield 'key file missing' => [static fn(): XypClient => new XypClient(self::TOKEN, '/nonexistent/private.key'), 'no such file'];
        yield 'encrypted key without passphrase' => [static fn(): XypClient => new XypClient(self::TOKEN, Keys::pem($key, 'pw')), 'passphrase:'];
    }

    public function testAcceptsAnEncryptedKeyWithItsPassphrase(): void
    {
        $transport = FakeTransport::answering(Soap::response(''));
        (new XypClient(self::TOKEN, Keys::pem(Keys::rsa(), 'pw'), 'pw', transport: $transport))->call('WS100101_getCitizenIDCardInfo');

        self::assertCount(1, $transport->requests);
    }

    public function testNeverRevealsCredentialsWhenPrinted(): void
    {
        $xyp = $this->client(FakeTransport::answering(''));

        // var_export is left out: the generated services point back at the client, so
        // it only reports the circular reference.
        foreach ([print_r($xyp, true), print_r((array) $xyp, true), $this->dump($xyp), $this->dump((array) $xyp)] as $printed) {
            self::assertStringNotContainsString(self::TOKEN, $printed);
            self::assertStringNotContainsString('PRIVATE KEY', $printed);
        }
        self::assertStringContainsString(self::BASE_URL, $this->dump($xyp));
        self::assertSame(['baseUrl' => self::BASE_URL], $xyp->__debugInfo());
    }

    public function testCannotBeSerialized(): void
    {
        $this->expectException(\LogicException::class);
        serialize($this->client(FakeTransport::answering('')));
    }

    public function testCannotBeUnserialized(): void
    {
        $this->expectException(\LogicException::class);
        unserialize('O:13:"Xyp\XypClient":0:{}');
    }

    private function dump(mixed $value): string
    {
        ob_start();
        var_dump($value);

        return (string) ob_get_clean();
    }

    private function client(FakeTransport $transport, ?RecordingLogger $logger = null): XypClient
    {
        return new XypClient(
            accessToken: self::TOKEN,
            privateKey: Keys::rsa(),
            baseUrl: self::BASE_URL,
            logger: $logger,
            transport: $transport,
        );
    }
}
