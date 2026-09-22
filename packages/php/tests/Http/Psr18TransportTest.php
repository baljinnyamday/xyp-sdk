<?php

declare(strict_types=1);

namespace Xyp\Tests\Http;

use GuzzleHttp\Psr7\HttpFactory;
use PHPUnit\Framework\TestCase;
use Psr\Http\Message\RequestInterface;
use Psr\Http\Message\ResponseInterface;
use Xyp\Exception\ConfigException;
use Xyp\Exception\ConnectionException;
use Xyp\Exception\TimeoutException;
use Xyp\Http\Psr18Transport;
use Xyp\Http\Request;
use Xyp\Tests\Support\FakeNetworkException;
use Xyp\Tests\Support\FakePsr18Client;
use Xyp\Tests\Support\Keys;
use Xyp\Tests\Support\Soap;
use Xyp\XypClient;

final class Psr18TransportTest extends TestCase
{
    public function testSendsTheRequestWithHeaderNamesInTheirOwnCase(): void
    {
        $client = FakePsr18Client::answering(500, 'fault body');

        $response = $this->transport($client)->send(new Request('POST', 'https://xyp.test/citizen-1.5.0/ws', [
            'accessToken' => 'token',
            'timeStamp' => '1700000000',
            'SOAPAction' => '""',
        ], '<envelope/>'));

        self::assertSame(500, $response->status);
        self::assertSame('fault body', $response->body);
        $sent = $client->request;
        self::assertNotNull($sent);
        self::assertSame('POST', $sent->getMethod());
        self::assertSame('https://xyp.test/citizen-1.5.0/ws', (string) $sent->getUri());
        self::assertSame('<envelope/>', (string) $sent->getBody());
        self::assertSame(['token'], $sent->getHeaders()['accessToken'] ?? null, 'PSR-7 must keep the name as set');
        self::assertSame(['1700000000'], $sent->getHeaders()['timeStamp'] ?? null);
        self::assertSame('""', $sent->getHeaderLine('SOAPAction'));
    }

    public function testSendsAGetWithoutABody(): void
    {
        $client = FakePsr18Client::answering(200, 'targetNamespace="x"');

        $this->transport($client)->send(new Request('GET', 'https://xyp.test/ws?WSDL'));

        $sent = $client->request;
        self::assertNotNull($sent);
        self::assertSame('GET', $sent->getMethod());
        self::assertSame('', (string) $sent->getBody());
    }

    public function testTurnsClientExceptionsIntoConnectionExceptions(): void
    {
        $client = new FakePsr18Client(static fn(RequestInterface $request): ResponseInterface => throw new FakeNetworkException('Could not resolve host: xyp.gov.mn', $request));

        try {
            $this->transport($client)->send(new Request('GET', 'https://xyp.gov.mn/'));
            self::fail('no ConnectionException');
        } catch (ConnectionException $error) {
            self::assertNotInstanceOf(TimeoutException::class, $error);
            self::assertStringContainsString('Could not resolve host', $error->getMessage());
            self::assertInstanceOf(FakeNetworkException::class, $error->getPrevious());
        }
    }

    public function testRecognisesATimeoutByItsMessage(): void
    {
        foreach (['cURL error 28: Operation timed out after 30001 milliseconds', 'Idle timeout reached for "https://xyp.gov.mn/"'] as $message) {
            $client = new FakePsr18Client(static fn(RequestInterface $request): ResponseInterface => throw new FakeNetworkException($message, $request));
            try {
                $this->transport($client)->send(new Request('GET', 'https://xyp.gov.mn/'));
                self::fail('no TimeoutException for ' . $message);
            } catch (TimeoutException) {
                $this->addToAssertionCount(1);
            }
        }
    }

    public function testAHeaderPsr7RefusesIsAConfigExceptionThatDoesNotQuoteIt(): void
    {
        try {
            $this->transport(FakePsr18Client::answering(200, ''))->send(new Request('POST', 'https://xyp.test/', ['accessToken' => "secret-token\r\nX: 1"], 'x'));
            self::fail('no ConfigException');
        } catch (ConfigException $error) {
            self::assertStringNotContainsString('secret-token', $error->getMessage());
            self::assertNull($error->getPrevious());
        }
    }

    public function testDrivesAWholeCall(): void
    {
        $client = FakePsr18Client::answering(200, Soap::response(Soap::ID_CARD));
        $xyp = new XypClient(accessToken: 'token', privateKey: Keys::rsa(), baseUrl: 'https://xyp.test', transport: $this->transport($client));

        self::assertSame(['firstname' => 'Бат', 'regnum' => Soap::REGNUM], $xyp->call('WS100101_getCitizenIDCardInfo'));
        $sent = $client->request;
        self::assertNotNull($sent);
        self::assertSame('text/xml; charset=utf-8', $sent->getHeaderLine('Content-Type'));
        self::assertNotSame('', $sent->getHeaderLine('signature'));
    }

    private function transport(FakePsr18Client $client): Psr18Transport
    {
        $factory = new HttpFactory();

        return new Psr18Transport($client, $factory, $factory);
    }
}
