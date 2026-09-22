<?php

declare(strict_types=1);

namespace Xyp\Http;

/**
 * What came back: the status and the whole body. Any status is a Response; the
 * client decides what a 500 means (JAX-WS sends SOAP faults with one).
 */
final readonly class Response
{
    public function __construct(
        public int $status,
        public string $body,
    ) {}
}
