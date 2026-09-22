<?php

declare(strict_types=1);

namespace Xyp\Tests;

use PHPUnit\Framework\TestCase;
use Xyp\Internal\CertificateAuthorities;

final class CertificatesTest extends TestCase
{
    /**
     * The fingerprints published in docs/tls.md. If one of these ever changes, the
     * bundled file changed with it, and that has to be a deliberate act.
     */
    private const FINGERPRINTS = [
        'MNRCA-2021.pem' => 'CBE7F3FE1F048037C215DA321E58CAA4F363DE9E54BBC442A3BFD62FAD834482',
        'MNICA-2022.pem' => '9423640DD74561D1AF1EA8A093860BDA7DF5B5620BB617921395DC0D1A1F980D',
    ];

    public function testBundledFingerprintsMatchTheDocumentation(): void
    {
        foreach (CertificateAuthorities::BUNDLED as $name) {
            $fingerprint = self::FINGERPRINTS[$name];
            $certificate = openssl_x509_read((string) file_get_contents(CertificateAuthorities::directory() . '/' . $name));
            self::assertNotFalse($certificate, $name);
            self::assertSame(strtolower($fingerprint), openssl_x509_fingerprint($certificate, 'sha256'), $name);
        }
    }

    public function testTheIssuingCaChainsToTheRoot(): void
    {
        $issuing = (string) file_get_contents(CertificateAuthorities::directory() . '/MNICA-2022.pem');
        $root = openssl_pkey_get_public((string) file_get_contents(CertificateAuthorities::directory() . '/MNRCA-2021.pem'));
        self::assertNotFalse($root);

        self::assertSame(1, openssl_x509_verify($issuing, $root));
    }

    public function testTheBundledFilesAreTheSameAsTheOtherSdks(): void
    {
        $others = [dirname(__DIR__, 2) . '/python/src/xyp/certs', dirname(__DIR__, 2) . '/go/xyp/certs'];
        foreach ($others as $directory) {
            if (!is_dir($directory)) {
                // A downloaded package holds only its own files.
                self::markTestSkipped($directory . ' is not part of this checkout');
            }
            foreach (CertificateAuthorities::BUNDLED as $name) {
                self::assertFileEquals($directory . '/' . $name, CertificateAuthorities::directory() . '/' . $name);
            }
        }
    }
}
