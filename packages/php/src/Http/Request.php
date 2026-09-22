<?php

declare(strict_types=1);

namespace Xyp\Http;

/**
 * One HTTP request the client wants sent. Header names must be sent verbatim:
 * XYP rejects "Accesstoken" where it expects "accessToken".
 */
final readonly class Request
{
    /** Header values var_dump and print_r must not show. */
    private const SECRET_HEADERS = ['accesstoken', 'signature'];

    /**
     * @param array<string, string> $headers
     */
    public function __construct(
        public string $method,
        public string $url,
        #[\SensitiveParameter]
        public array $headers = [],
        public string $body = '',
    ) {}

    /**
     * Keeps the access token and the signature out of dumps, for the transport a
     * caller writes and debugs with var_dump.
     *
     * @return array<string, mixed>
     */
    public function __debugInfo(): array
    {
        $headers = [];
        foreach ($this->headers as $name => $value) {
            $headers[$name] = in_array(strtolower($name), self::SECRET_HEADERS, true) ? '(hidden)' : $value;
        }

        return ['method' => $this->method, 'url' => $this->url, 'headers' => $headers, 'body' => $this->body];
    }
}
