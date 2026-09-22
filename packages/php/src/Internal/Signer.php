<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Xyp\Exception\ConfigException;

/**
 * Builds the three credential headers XYP requires on every call:
 * signature = base64(RSA-SHA256(accessToken + "." + timeStamp)).
 *
 * The token and the key live only inside a closure, so neither var_dump,
 * var_export nor an (array) cast of the client can reveal them.
 *
 * @internal
 */
final class Signer
{
    /** @var \Closure(string): string signs "token.timestamp" for a timestamp */
    private readonly \Closure $sign;

    /** @var \Closure(): string */
    private readonly \Closure $token;

    /**
     * @param \Closure(): int $clock Unix seconds; tests pin it
     */
    public function __construct(
        #[\SensitiveParameter]
        string $accessToken,
        #[\SensitiveParameter]
        \OpenSSLAsymmetricKey $key,
        private readonly \Closure $clock,
    ) {
        $this->token = static fn(): string => $accessToken;
        $this->sign = static function (string $timestamp) use ($accessToken, $key): string {
            try {
                $signed = Guard::run(static function () use ($accessToken, $timestamp, $key): string|false {
                    $signature = null;
                    $signed = openssl_sign($accessToken . '.' . $timestamp, $signature, $key, OPENSSL_ALGO_SHA256);

                    return $signed && is_string($signature) ? $signature : false;
                });
            } catch (\ErrorException) {
                $signed = false;
            }
            OpenSsl::clearErrors();
            if (!is_string($signed)) {
                throw new ConfigException('the private key could not sign the request');
            }

            return $signed;
        };
    }

    /**
     * Fresh headers for one request: XYP rejects stale timestamps, so they are
     * never reused.
     *
     * @return array{accessToken: string, timeStamp: string, signature: string}
     *
     * @throws ConfigException
     */
    public function headers(): array
    {
        $timestamp = (string) ($this->clock)();

        return [
            'accessToken' => ($this->token)(),
            'timeStamp' => $timestamp,
            'signature' => base64_encode(($this->sign)($timestamp)),
        ];
    }

    /**
     * @return array{}
     */
    public function __debugInfo(): array
    {
        return [];
    }
}
