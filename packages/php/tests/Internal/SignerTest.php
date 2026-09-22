<?php

declare(strict_types=1);

namespace Xyp\Tests\Internal;

use PHPUnit\Framework\TestCase;
use Xyp\Internal\Signer;
use Xyp\Tests\Support\Keys;

final class SignerTest extends TestCase
{
    private const TOKEN = 'test-access-token';

    private const FIXED_TIME = 1_700_000_000;

    public function testSignsTokenDotTimestampWithRsaSha256(): void
    {
        $key = Keys::rsa();
        $headers = (new Signer(self::TOKEN, $key, static fn(): int => self::FIXED_TIME))->headers();

        self::assertSame(self::TOKEN, $headers['accessToken']);
        self::assertSame('1700000000', $headers['timeStamp']);
        $signature = base64_decode($headers['signature'], true);
        self::assertIsString($signature, 'the signature is not base64');
        $public = openssl_pkey_get_public(Keys::publicPem($key));
        self::assertNotFalse($public);
        self::assertSame(1, openssl_verify(self::TOKEN . '.1700000000', $signature, $public, OPENSSL_ALGO_SHA256));
        // PKCS#1 v1.5 is deterministic: the same bytes as any other correct implementation.
        self::assertTrue(openssl_sign(self::TOKEN . '.1700000000', $reference, $key, OPENSSL_ALGO_SHA256));
        self::assertSame($reference, $signature);
    }

    public function testUsesTheClockOnEveryCall(): void
    {
        $ticks = self::FIXED_TIME;
        $signer = new Signer(self::TOKEN, Keys::rsa(), static function () use (&$ticks): int {
            return ++$ticks;
        });

        $first = $signer->headers();
        $second = $signer->headers();

        self::assertNotSame($first['timeStamp'], $second['timeStamp']);
        self::assertNotSame($first['signature'], $second['signature'], 'XYP rejects stale timestamps, so a signature is never reused');
    }

    public function testNeverRevealsTheTokenOrTheKey(): void
    {
        $signer = new Signer(self::TOKEN, Keys::rsa(), static fn(): int => self::FIXED_TIME);

        self::assertStringNotContainsString(self::TOKEN, var_export($signer, true));
        self::assertStringNotContainsString(self::TOKEN, print_r($signer, true));
        self::assertStringNotContainsString(self::TOKEN, print_r(['signer' => $signer], true));
    }
}
