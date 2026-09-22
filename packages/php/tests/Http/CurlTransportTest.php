<?php

declare(strict_types=1);

namespace Xyp\Tests\Http;

use PHPUnit\Framework\TestCase;
use Xyp\Exception\ConfigException;
use Xyp\Exception\ConnectionException;
use Xyp\Exception\Origin;
use Xyp\Exception\TimeoutException;
use Xyp\Http\CurlTransport;
use Xyp\Http\Request;
use Xyp\Http\Response;
use Xyp\Internal\CertificateAuthorities;
use Xyp\Tests\Support\LocalServer;

final class CurlTransportTest extends TestCase
{
    private static ?LocalServer $server = null;

    public static function setUpBeforeClass(): void
    {
        self::$server = new LocalServer(__DIR__ . '/router.php');
    }

    public static function tearDownAfterClass(): void
    {
        self::$server?->stop();
        self::$server = null;
    }

    public function testSendsHeaderNamesVerbatimAndTheBodyIntact(): void
    {
        // Over 1 KiB, where curl would otherwise send "Expect: 100-continue" and wait.
        $body = "<?xml version='1.0'?>" . str_repeat('Бат ', 400);
        $transport = new CurlTransport(5.0);

        $echo = $this->echo($transport->send(new Request('POST', self::url('/citizen-1.5.0/ws'), [
            'Content-Type' => 'text/xml; charset=utf-8',
            'SOAPAction' => '""',
            'accessToken' => 'token',
            'timeStamp' => '1700000000',
            'signature' => 'c2ln',
        ], $body)));

        self::assertSame('POST', $echo['method']);
        self::assertSame('/citizen-1.5.0/ws', $echo['uri']);
        self::assertSame($body, $echo['body']);
        foreach (['accessToken' => 'token', 'timeStamp' => '1700000000', 'signature' => 'c2ln', 'SOAPAction' => '""', 'Content-Type' => 'text/xml; charset=utf-8'] as $name => $value) {
            self::assertArrayHasKey($name, $echo['headers'], "the {$name} header lost its case");
            self::assertSame($value, $echo['headers'][$name]);
        }
        self::assertArrayNotHasKey('Expect', $echo['headers']);
    }

    public function testReusesItsHandleAcrossMethods(): void
    {
        $transport = new CurlTransport(5.0);
        $transport->send(new Request('POST', self::url('/first'), [], 'body'));

        $get = $this->echo($transport->send(new Request('GET', self::url('/ws?WSDL'))));
        $put = $this->echo($transport->send(new Request('PUT', self::url('/put'), [], 'x')));

        self::assertSame(['GET', '/ws?WSDL', ''], [$get['method'], $get['uri'], $get['body']], 'the last POST leaked into a GET');
        self::assertSame(['PUT', 'x'], [$put['method'], $put['body']]);
    }

    public function testReturnsErrorStatusesAsResponses(): void
    {
        $response = (new CurlTransport(5.0))->send(new Request('POST', self::url('/status/500'), [], 'x'));

        self::assertSame(500, $response->status);
        self::assertSame('status 500', $response->body);
    }

    public function testARefusedConnectionIsNotATimeout(): void
    {
        try {
            (new CurlTransport(5.0))->send(new Request('GET', 'http://127.0.0.1:1/'));
            self::fail('no ConnectionException');
        } catch (ConnectionException $error) {
            self::assertNotInstanceOf(TimeoutException::class, $error);
            self::assertStringContainsString('could not reach XYP (', $error->getMessage());
            self::assertStringContainsString('VPN', $error->getMessage());
            self::assertNotNull($error->getPrevious());
        }
    }

    public function testRefusesAHeaderThatWouldStartAnotherHeader(): void
    {
        $this->expectException(ConfigException::class);
        $this->expectExceptionMessage('accessToken header holds a line break');
        (new CurlTransport(5.0))->send(new Request('POST', self::url('/'), ['accessToken' => "token\r\nX-Evil: 1"], 'x'));
    }

    public function testAcceptsEveryVerifySetting(): void
    {
        $bundled = CertificateAuthorities::directory() . '/MNRCA-2021.pem';
        $pem = (string) file_get_contents($bundled);
        foreach ([true, false, $bundled, $pem] as $verify) {
            $response = (new CurlTransport(5.0, $verify))->send(new Request('GET', self::url('/')));
            self::assertSame(200, $response->status);
        }
    }

    public function testRejectsAnUnusableVerifySetting(): void
    {
        foreach (['/nonexistent/ca.pem' => 'no such file', __DIR__ . '/router.php' => 'not a PEM certificate'] as $path => $problem) {
            try {
                new CurlTransport(5.0, $path);
                self::fail('no ConfigException for ' . $problem);
            } catch (ConfigException $error) {
                self::assertSame('cannot read the CA file passed as verify: (' . $problem . ')', $error->getMessage());
            }
        }
    }

    public function testRejectsANonPositiveTimeout(): void
    {
        $this->expectException(ConfigException::class);
        new CurlTransport(0.0);
    }

    public function testTheBundledCertificatesAreBothIncluded(): void
    {
        $bundle = (new CertificateAuthorities())->bundled();

        self::assertSame(2, substr_count($bundle, '-----BEGIN CERTIFICATE-----'));
    }

    /**
     * Last in the class: the single-process server is still sleeping on this request
     * when the class ends, and stopping it is what ends the sleep.
     */
    public function testATimeoutIsATimeoutExceptionAndIsFast(): void
    {
        $started = microtime(true);
        try {
            (new CurlTransport(0.2))->send(new Request('GET', self::url('/slow')));
            self::fail('no TimeoutException');
        } catch (TimeoutException $error) {
            self::assertStringContainsString('timeout', $error->getMessage());
            self::assertSame(Origin::Network, $error->origin());
            self::assertSame(28, $error->getPrevious()?->getCode(), 'curl\'s own error stays reachable');
        }
        self::assertLessThan(1.2, microtime(true) - $started);
    }

    private static function url(string $path): string
    {
        self::assertNotNull(self::$server);

        return self::$server->baseUrl . $path;
    }

    /**
     * @return array{method: string, uri: string, headers: array<string, string>, body: string}
     */
    private function echo(Response $response): array
    {
        self::assertSame(200, $response->status);
        $echo = json_decode($response->body, true, 512, JSON_THROW_ON_ERROR);
        self::assertIsArray($echo);

        /** @var array{method: string, uri: string, headers: array<string, string>, body: string} $echo */
        return $echo;
    }
}
