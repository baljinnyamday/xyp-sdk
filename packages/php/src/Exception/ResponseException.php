<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * XYP answered with something that is not a valid service response: a SOAP
 * fault, a gateway error page, or XML the SDK cannot read.
 */
final class ResponseException extends \RuntimeException implements XypException
{
    /**
     * @param ?int $statusCode the HTTP status, or null when the failure was not tied to one
     */
    public function __construct(
        string $message,
        public readonly ?int $statusCode = null,
        ?\Throwable $previous = null,
    ) {
        parent::__construct($message, $statusCode ?? 0, $previous);
    }

    public function origin(): Origin
    {
        return Origin::Xyp;
    }
}
