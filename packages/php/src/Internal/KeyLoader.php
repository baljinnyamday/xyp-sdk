<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Xyp\Exception\ConfigException;

/**
 * Loads the RSA private key that signs every request, from a file (PEM or DER),
 * PEM text, DER bytes or an OpenSSLAsymmetricKey.
 *
 * No message ever carries OpenSSL's own error text, the key or the path's
 * contents: any of those can end up in a log.
 *
 * @internal
 */
final class KeyLoader
{
    private const PEM_MARKER = '-----BEGIN';

    /**
     * @param string|\OpenSSLAsymmetricKey $key a path, PEM text, DER bytes (they always hold
     *                                          a NUL byte, which no path does) or a loaded key
     *
     * @throws ConfigException
     */
    public static function load(
        #[\SensitiveParameter]
        string|\OpenSSLAsymmetricKey $key,
        #[\SensitiveParameter]
        ?string $passphrase = null,
    ): \OpenSSLAsymmetricKey {
        $loaded = match (true) {
            $key instanceof \OpenSSLAsymmetricKey => $key,
            str_contains($key, self::PEM_MARKER) => self::fromPem($key, $passphrase),
            str_contains($key, "\0") => self::fromDer($key, $passphrase),
            default => self::fromFile($key, $passphrase),
        };

        return self::checkRsaPrivateKey($loaded);
    }

    private static function fromFile(string $path, #[\SensitiveParameter] ?string $passphrase): \OpenSSLAsymmetricKey
    {
        $problem = FileProblem::of($path);
        if ($problem === null) {
            try {
                $data = Guard::run(static fn(): string|false => file_get_contents($path));
            } catch (\ErrorException) {
                $data = false;
            }
            if (is_string($data)) {
                return str_contains($data, self::PEM_MARKER) ? self::fromPem($data, $passphrase) : self::fromDer($data, $passphrase);
            }
            $problem = 'unreadable';
        }

        // Only the failure class, never the path or its contents.
        throw new ConfigException(sprintf('cannot read the private key file (%s)', $problem));
    }

    private static function fromPem(#[\SensitiveParameter] string $pem, #[\SensitiveParameter] ?string $passphrase): \OpenSSLAsymmetricKey
    {
        $encrypted = str_contains($pem, '-----BEGIN ENCRYPTED PRIVATE KEY-----')
            || preg_match('/^Proc-Type:\s*4,\s*ENCRYPTED/mi', $pem) === 1;
        if ($encrypted && ($passphrase === null || $passphrase === '')) {
            throw new ConfigException(
                'the private key is encrypted: pass its passphrase to XypClient as passphrase:, or decrypt it '
                . 'once with `openssl pkey -in encrypted.key -out private.key`',
            );
        }
        $key = self::parse($pem, $encrypted ? $passphrase : null);
        if ($key === null) {
            throw new ConfigException($encrypted
                ? 'the private key could not be decrypted with the given passphrase'
                : 'privateKey is not a valid PEM or DER private key');
        }

        return $key;
    }

    /**
     * DER names no format, so the common encodings are tried in turn, wrapped as the
     * PEM that OpenSSL reads.
     */
    private static function fromDer(#[\SensitiveParameter] string $der, #[\SensitiveParameter] ?string $passphrase): \OpenSSLAsymmetricKey
    {
        $body = chunk_split(base64_encode($der), 64, "\n");
        foreach (['PRIVATE KEY', 'RSA PRIVATE KEY'] as $label) {
            $key = self::parse("-----BEGIN {$label}-----\n{$body}-----END {$label}-----\n", null);
            if ($key !== null) {
                return $key;
            }
        }
        if ($passphrase !== null && $passphrase !== '') {
            $key = self::parse("-----BEGIN ENCRYPTED PRIVATE KEY-----\n{$body}-----END ENCRYPTED PRIVATE KEY-----\n", $passphrase);
            if ($key !== null) {
                return $key;
            }
        }

        throw new ConfigException('privateKey is not a valid PEM or DER private key');
    }

    private static function parse(#[\SensitiveParameter] string $pem, #[\SensitiveParameter] ?string $passphrase): ?\OpenSSLAsymmetricKey
    {
        try {
            // Never null: with no passphrase, OpenSSL's default callback asks for one on
            // the terminal and blocks a CLI worker on stdin. An empty one just fails.
            $key = Guard::run(static fn(): \OpenSSLAsymmetricKey|false => openssl_pkey_get_private($pem, $passphrase ?? ''));
        } catch (\ErrorException) {
            $key = false;
        }
        OpenSsl::clearErrors();

        return $key === false ? null : $key;
    }

    private static function checkRsaPrivateKey(\OpenSSLAsymmetricKey $key): \OpenSSLAsymmetricKey
    {
        $details = openssl_pkey_get_details($key);
        OpenSsl::clearErrors();
        if (!is_array($details) || ($details['type'] ?? null) !== OPENSSL_KEYTYPE_RSA) {
            throw new ConfigException('privateKey must be an RSA key');
        }
        if (!is_array($details['rsa'] ?? null) || !isset($details['rsa']['d'])) {
            throw new ConfigException('privateKey is an RSA public key; XYP needs the private key to sign requests');
        }

        return $key;
    }
}
