<?php

declare(strict_types=1);

namespace Xyp\Tests;

use PHPUnit\Framework\TestCase;
use Xyp\Exception\ConfigException;
use Xyp\Internal\Environment;
use Xyp\Tests\Support\FakeTransport;
use Xyp\Tests\Support\Keys;
use Xyp\Tests\Support\Soap;
use Xyp\XypClient;

final class XypClientEnvironmentTest extends TestCase
{
    private const NAMES = [XypClient::ACCESS_TOKEN_ENV, XypClient::PRIVATE_KEY_ENV];

    /** @var array<string, array{env: string|false, _ENV: mixed, _SERVER: mixed}> */
    private array $saved = [];

    private ?string $keyFile = null;

    protected function setUp(): void
    {
        foreach (self::NAMES as $name) {
            $this->saved[$name] = ['env' => getenv($name), '_ENV' => $_ENV[$name] ?? null, '_SERVER' => $_SERVER[$name] ?? null];
            self::unset($name);
        }
    }

    protected function tearDown(): void
    {
        foreach ($this->saved as $name => $saved) {
            self::unset($name);
            if ($saved['env'] !== false) {
                putenv($name . '=' . $saved['env']);
            }
            if ($saved['_ENV'] !== null) {
                $_ENV[$name] = $saved['_ENV'];
            }
            if ($saved['_SERVER'] !== null) {
                $_SERVER[$name] = $saved['_SERVER'];
            }
        }
        if ($this->keyFile !== null) {
            unlink($this->keyFile);
        }
    }

    public function testNamesTheVariableForAMissingToken(): void
    {
        $this->expectException(ConfigException::class);
        $this->expectExceptionMessage('XYP_ACCESS_TOKEN');
        new XypClient(privateKey: Keys::rsa());
    }

    public function testNamesTheVariableForAMissingKey(): void
    {
        $this->expectException(ConfigException::class);
        $this->expectExceptionMessage('XYP_PRIVATE_KEY');
        new XypClient(accessToken: 'token');
    }

    public function testReadsTheTokenFromTheProcessEnvironment(): void
    {
        putenv(XypClient::ACCESS_TOKEN_ENV . '=from-getenv');

        self::assertSame('from-getenv', $this->sentToken(new XypClient(privateKey: Keys::rsa(), transport: $transport = $this->transport()), $transport));
    }

    public function testReadsTheTokenFromEnvAndServerArrays(): void
    {
        $_SERVER[XypClient::ACCESS_TOKEN_ENV] = 'from-server';
        self::assertSame('from-server', $this->sentToken(new XypClient(privateKey: Keys::rsa(), transport: $transport = $this->transport()), $transport));

        $_ENV[XypClient::ACCESS_TOKEN_ENV] = 'from-env';
        self::assertSame('from-env', $this->sentToken(new XypClient(privateKey: Keys::rsa(), transport: $transport = $this->transport()), $transport), '$_ENV comes before $_SERVER');

        putenv(XypClient::ACCESS_TOKEN_ENV . '=from-getenv');
        self::assertSame('from-getenv', Environment::get(XypClient::ACCESS_TOKEN_ENV), 'the process environment comes first');
    }

    public function testAnEmptyValueCountsAsUnset(): void
    {
        putenv(XypClient::ACCESS_TOKEN_ENV . '=');
        $_ENV[XypClient::ACCESS_TOKEN_ENV] = '';
        $_SERVER[XypClient::ACCESS_TOKEN_ENV] = 'from-server';

        self::assertSame('from-server', Environment::get(XypClient::ACCESS_TOKEN_ENV));
        $_SERVER[XypClient::ACCESS_TOKEN_ENV] = ['not', 'a', 'string'];
        self::assertNull(Environment::get(XypClient::ACCESS_TOKEN_ENV));
    }

    public function testAnExplicitTokenWinsAndAnEmptyOneFallsBack(): void
    {
        putenv(XypClient::ACCESS_TOKEN_ENV . '=from-getenv');

        self::assertSame('explicit', $this->sentToken(new XypClient(accessToken: 'explicit', privateKey: Keys::rsa(), transport: $transport = $this->transport()), $transport));
        self::assertSame('from-getenv', $this->sentToken(new XypClient(accessToken: '', privateKey: Keys::rsa(), transport: $transport = $this->transport()), $transport));
    }

    public function testReadsTheKeyFileNamedByTheEnvironment(): void
    {
        $this->keyFile = (string) tempnam(sys_get_temp_dir(), 'xyp-key-');
        file_put_contents($this->keyFile, Keys::pem(Keys::rsa()));
        $_SERVER[XypClient::PRIVATE_KEY_ENV] = $this->keyFile;

        $transport = $this->transport();
        $xyp = new XypClient(accessToken: 'token', transport: $transport);
        $xyp->call('WS100101_getCitizenIDCardInfo');

        $request = $transport->requests[0];
        $public = openssl_pkey_get_public(Keys::publicPem(Keys::rsa()));
        self::assertNotFalse($public);
        self::assertSame(1, openssl_verify('token.' . $request->headers['timeStamp'], (string) base64_decode($request->headers['signature'], true), $public, OPENSSL_ALGO_SHA256));
    }

    private function transport(): FakeTransport
    {
        return FakeTransport::answering(Soap::response(''));
    }

    private function sentToken(XypClient $xyp, FakeTransport $transport): string
    {
        $xyp->call('WS100101_getCitizenIDCardInfo');

        $request = $transport->requests[\count($transport->requests) - 1] ?? self::fail('no request was sent');

        return $request->headers['accessToken'];
    }

    private static function unset(string $name): void
    {
        putenv($name);
        unset($_ENV[$name], $_SERVER[$name]);
    }
}
