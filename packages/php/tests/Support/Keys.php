<?php

declare(strict_types=1);

namespace Xyp\Tests\Support;

/**
 * Throwaway keys generated for the test run. Real keys never belong in a
 * repository, not even in tests.
 */
final class Keys
{
    private static ?\OpenSSLAsymmetricKey $rsa = null;

    /** One key per run: generating a 2048-bit key takes a noticeable moment. */
    public static function rsa(): \OpenSSLAsymmetricKey
    {
        return self::$rsa ??= self::generate(['private_key_type' => OPENSSL_KEYTYPE_RSA, 'private_key_bits' => 2048]);
    }

    public static function freshRsa(): \OpenSSLAsymmetricKey
    {
        return self::generate(['private_key_type' => OPENSSL_KEYTYPE_RSA, 'private_key_bits' => 2048]);
    }

    public static function ec(): \OpenSSLAsymmetricKey
    {
        return self::generate(['private_key_type' => OPENSSL_KEYTYPE_EC, 'curve_name' => 'prime256v1']);
    }

    /** PKCS#8 PEM, optionally encrypted. */
    public static function pem(\OpenSSLAsymmetricKey $key, ?string $passphrase = null): string
    {
        if (!openssl_pkey_export($key, $pem, $passphrase)) {
            throw new \RuntimeException('openssl could not export the key');
        }
        \assert(is_string($pem));

        return $pem;
    }

    public static function publicPem(\OpenSSLAsymmetricKey $key): string
    {
        $details = openssl_pkey_get_details($key);
        if (!is_array($details) || !is_string($details['key'] ?? null)) {
            throw new \RuntimeException('openssl could not read the public key');
        }

        return $details['key'];
    }

    /** The DER bytes inside a PEM block. */
    public static function der(string $pem): string
    {
        $body = preg_replace('/-----[^-]+-----|\s/', '', $pem);
        $der = base64_decode((string) $body, true);
        if ($der === false) {
            throw new \RuntimeException('not PEM');
        }

        return $der;
    }

    /** PKCS#1 ("RSA PRIVATE KEY"), which OpenSSL 3 no longer exports by default. */
    public static function pkcs1Pem(\OpenSSLAsymmetricKey $key): string
    {
        $der = self::pkcs1Der(self::der(self::pem($key)));

        return "-----BEGIN RSA PRIVATE KEY-----\n" . chunk_split(base64_encode($der), 64, "\n") . "-----END RSA PRIVATE KEY-----\n";
    }

    /**
     * Unwraps PKCS#8: SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING { PKCS#1 } }.
     */
    private static function pkcs1Der(string $pkcs8): string
    {
        $offset = 0;
        self::readHeader($pkcs8, $offset); // outer SEQUENCE
        $offset += self::readHeader($pkcs8, $offset); // version INTEGER
        $offset += self::readHeader($pkcs8, $offset); // AlgorithmIdentifier
        $length = self::readHeader($pkcs8, $offset); // OCTET STRING

        return substr($pkcs8, $offset, $length);
    }

    /** Moves $offset past a DER tag and length, and returns the length. */
    private static function readHeader(string $der, int &$offset): int
    {
        ++$offset; // tag
        $length = ord($der[$offset++]);
        if ($length > 0x7F) {
            $bytes = $length & 0x7F;
            $length = 0;
            for ($i = 0; $i < $bytes; ++$i) {
                $length = ($length << 8) | ord($der[$offset++]);
            }
        }

        return $length;
    }

    /**
     * @param array<string, int|string> $options
     */
    private static function generate(array $options): \OpenSSLAsymmetricKey
    {
        $key = openssl_pkey_new($options);
        if ($key === false) {
            throw new \RuntimeException('openssl could not generate a key');
        }

        return $key;
    }
}
