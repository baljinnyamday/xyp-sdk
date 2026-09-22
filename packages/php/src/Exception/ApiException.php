<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * XYP processed the request and answered with a non-zero resultCode. Codes:
 * https://developer.xyp.gov.mn/docs/result-code
 *
 * Catch a subclass for a documented group of codes (NotFoundException, ...), or
 * this class for all of them; a code no subclass covers is a plain ApiException.
 * getCode() returns the result code too.
 */
class ApiException extends \RuntimeException implements XypException
{
    /**
     * @param string $requestId what XYP support will ask you for
     */
    final public function __construct(
        public readonly int $resultCode,
        public readonly string $resultMessage,
        public readonly string $requestId = '',
    ) {
        parent::__construct('[' . $resultCode . '] ' . $resultMessage, $resultCode);
    }

    /**
     * Picks the subclass for the result code, grouped the way XYP groups them.
     */
    public static function fromResult(int $resultCode, string $resultMessage, string $requestId): self
    {
        return match (true) {
            $resultCode === 1 => new NotFoundException($resultCode, $resultMessage, $requestId),
            $resultCode === 2 => new InternalException($resultCode, $resultMessage, $requestId),
            $resultCode === 3 => new InvalidRequestException($resultCode, $resultMessage, $requestId),
            $resultCode >= 200 && $resultCode <= 202 => new AuthRequiredException($resultCode, $resultMessage, $requestId),
            $resultCode === 203, $resultCode === 501 => new AccessDeniedException($resultCode, $resultMessage, $requestId),
            $resultCode >= 301 && $resultCode <= 304 => new FingerprintException($resultCode, $resultMessage, $requestId),
            $resultCode >= 401 && $resultCode <= 402 => new CitizenDataException($resultCode, $resultMessage, $requestId),
            $resultCode >= 601 && $resultCode <= 605 => new SignatureException($resultCode, $resultMessage, $requestId),
            $resultCode >= 801 && $resultCode <= 802 => new ProviderException($resultCode, $resultMessage, $requestId),
            default => new self($resultCode, $resultMessage, $requestId),
        };
    }

    final public function origin(): Origin
    {
        return Origin::Xyp;
    }
}
