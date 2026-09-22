<?php

declare(strict_types=1);

namespace Xyp\Tests;

use PHPUnit\Framework\TestCase;
use Xyp\Auth;
use Xyp\AuthType;
use Xyp\Bytes;
use Xyp\Date;
use Xyp\Extras;
use Xyp\Http\Request;
use Xyp\Mismatch;

final class ValuesTest extends TestCase
{
    public function testAuthConstructorsSetTheApprovalType(): void
    {
        self::assertEquals(new Auth(regnum: 'R', authType: AuthType::SmsOtp, otp: 12), Auth::otp('R', 12));
        self::assertEquals(new Auth(regnum: 'R', authType: AuthType::SsoOtp, otp: 12), Auth::ssoOtp('R', 12));
        self::assertEquals(new Auth(regnum: 'R', authType: AuthType::DigitalSignature, signature: 'S', certFingerprint: 'F'), Auth::signature('R', 'S', 'F'));
        self::assertEquals(new Auth(regnum: 'R', authType: AuthType::Fingerprint, fingerprint: "\x00"), Auth::fingerprint('R', "\x00"));
        self::assertEquals(new Auth(regnum: 'R', authType: AuthType::DanApp), Auth::danApp('R'));
        self::assertSame(0, (new Auth())->otp);
        self::assertNull((new Auth())->authType);
    }

    public function testAuthKeepsProofOfConsentOutOfDumps(): void
    {
        $auth = new Auth(regnum: 'R', otp: 123456, fingerprint: 'FINGERPRINT', signature: 'SIGNATURE', appAuthToken: 'APPTOKEN');
        $printed = print_r($auth, true);

        foreach (['123456', 'FINGERPRINT', 'SIGNATURE', 'APPTOKEN'] as $secret) {
            self::assertStringNotContainsString($secret, $printed);
        }
        self::assertStringContainsString('R', $printed);
    }

    public function testMismatchNeverPrintsItsValue(): void
    {
        $mismatch = new Mismatch('age', 'not a valid int', 'РД00000000');

        self::assertSame('age (not a valid int)', (string) $mismatch);
        self::assertSame(['path' => 'age', 'problem' => 'not a valid int'], $mismatch->__debugInfo());
        self::assertStringNotContainsString('РД00000000', print_r($mismatch, true));
        self::assertStringNotContainsString('РД00000000', print_r([$mismatch], true));
        ob_start();
        var_dump(new Extras([$mismatch]));
        self::assertStringNotContainsString('РД00000000', (string) ob_get_clean());
        self::assertSame('РД00000000', $mismatch->value(), 'the caller can still ask for it');
        self::assertSame('<response> (expected an object)', (string) new Mismatch('', 'expected an object', 'x'));
    }

    public function testDateStringifiesToItsRawText(): void
    {
        $date = new Date('31.01.2024');

        self::assertSame('31.01.2024', (string) $date);
        self::assertNull($date->time);
        self::assertSame('', (string) new Date());
    }

    public function testExtrasDefaultsToNothing(): void
    {
        $extras = new Extras();

        self::assertSame([], $extras->mismatches);
        self::assertNull($extras->raw);
        self::assertSame("\x00", (new Bytes("\x00"))->data);
    }

    public function testRequestKeepsCredentialsOutOfDumps(): void
    {
        $request = new Request('POST', 'https://xyp.gov.mn/ws', ['accessToken' => 'TOKEN', 'signature' => 'SIG', 'timeStamp' => '1'], 'body');
        $printed = print_r($request, true);

        self::assertStringNotContainsString('TOKEN', $printed);
        self::assertStringNotContainsString('SIG', $printed);
        self::assertStringContainsString('timeStamp', $printed);
        self::assertSame('TOKEN', $request->headers['accessToken']);
    }
}
