<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Xyp\Exception\ConfigException;
use Xyp\Http\CurlTransport;
use Xyp\Http\Transport;

/**
 * The client's options after defaults, environment fallbacks and validation, so
 * the client never has to second-guess its own configuration.
 *
 * @internal
 */
final readonly class Settings
{
    public const ACCESS_TOKEN_ENV = 'XYP_ACCESS_TOKEN';

    public const PRIVATE_KEY_ENV = 'XYP_PRIVATE_KEY';

    public const DEFAULT_BASE_URL = 'https://xyp.gov.mn';

    private function __construct(
        public Signer $signer,
        public string $baseUrl,
        public Transport $transport,
    ) {}

    /**
     * @param \Closure(): int $clock
     *
     * @throws ConfigException
     */
    public static function resolve(
        #[\SensitiveParameter]
        ?string $accessToken,
        #[\SensitiveParameter]
        string|\OpenSSLAsymmetricKey|null $privateKey,
        #[\SensitiveParameter]
        ?string $passphrase,
        string $baseUrl,
        float $timeout,
        bool|string $verify,
        ?Transport $transport,
        \Closure $clock,
    ): self {
        $token = $accessToken !== null && $accessToken !== '' ? $accessToken : Environment::get(self::ACCESS_TOKEN_ENV);
        if ($token === null) {
            throw new ConfigException(sprintf('pass accessToken: to XypClient or set the %s environment variable', self::ACCESS_TOKEN_ENV));
        }
        if (strpbrk($token, "\r\n\0") !== false) {
            // Usually a token read from a file with its trailing newline.
            throw new ConfigException('accessToken holds a line break or a NUL byte');
        }

        if (!is_finite($timeout) || $timeout <= 0) {
            throw new ConfigException('timeout must be a positive number of seconds');
        }
        if ($verify === '') {
            throw new ConfigException('verify must be true, false, a CA file path or PEM text');
        }

        return new self(
            new Signer($token, KeyLoader::load(self::keySource($privateKey), $passphrase), $clock),
            self::baseUrl($baseUrl),
            $transport ?? new CurlTransport($timeout, $verify),
        );
    }

    /**
     * @throws ConfigException
     */
    private static function keySource(#[\SensitiveParameter] string|\OpenSSLAsymmetricKey|null $privateKey): string|\OpenSSLAsymmetricKey
    {
        if ($privateKey !== null && $privateKey !== '') {
            return $privateKey;
        }

        return Environment::get(self::PRIVATE_KEY_ENV) ?? throw new ConfigException(
            sprintf('pass privateKey: to XypClient or point the %s environment variable at the key file', self::PRIVATE_KEY_ENV),
        );
    }

    /**
     * @throws ConfigException
     */
    private static function baseUrl(string $baseUrl): string
    {
        $baseUrl = $baseUrl !== '' ? $baseUrl : self::DEFAULT_BASE_URL;
        $scheme = strtolower(substr($baseUrl, 0, 8));
        if (!str_starts_with($scheme, 'https://') && !str_starts_with($scheme, 'http://')) {
            throw new ConfigException(sprintf('baseUrl must start with https:// (or http://), got "%s"', $baseUrl));
        }

        // A trailing slash would otherwise produce "https://xyp.gov.mn//citizen-1.5.0/ws".
        return rtrim($baseUrl, '/');
    }
}
