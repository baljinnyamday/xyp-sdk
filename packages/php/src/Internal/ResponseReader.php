<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Xyp\Exception\ApiException;
use Xyp\Exception\ResponseException;
use Xyp\Http\Response;

/**
 * Reads a SOAP 1.1 response:
 *
 *     <return> <requestId/> <resultCode/> <resultMessage/> <response>...</response> </return>
 *
 * @internal
 */
final class ResponseReader
{
    /** The only result code that is not an error. */
    private const RESULT_OK = 0;

    /** From this status up, a reply stops being a service response. */
    public const HTTP_ERROR_STATUS = 400;

    /**
     * The data of a successful call.
     *
     * @throws ApiException      when XYP answered with a non-zero resultCode
     * @throws ResponseException when the reply is not a service response
     */
    public static function unwrap(Response $response): mixed
    {
        try {
            $result = self::parse($response->body);
        } catch (ResponseException $error) {
            if ($response->status >= self::HTTP_ERROR_STATUS) {
                // JAX-WS sends SOAP faults with HTTP 500: keep the fault text, it is the
                // only diagnostic the caller gets, and add the status to it.
                throw new ResponseException(
                    sprintf('XYP answered with HTTP %d: %s', $response->status, $error->getMessage()),
                    $response->status,
                    $error,
                );
            }

            throw $error;
        }
        if ($result->resultCode !== self::RESULT_OK) {
            throw ApiException::fromResult($result->resultCode, $result->message, $result->requestId);
        }

        return $result->data;
    }

    /**
     * @throws ResponseException
     */
    public static function parse(string $payload): ServiceResult
    {
        $document = XmlTree::parse($payload);
        $fault = self::find($document, 'Fault');
        if ($fault !== null) {
            $reason = self::text(self::child($fault[0], 'faultstring'));

            throw new ResponseException('XYP returned a SOAP fault: ' . ($reason !== '' ? $reason : 'unknown SOAP fault'));
        }
        $result = self::find($document, 'return');
        if ($result === null) {
            throw new ResponseException('XYP response has no <return> element');
        }
        $code = self::text(self::child($result[0], 'resultCode'));
        $resultCode = preg_match('/^-?\d+$/D', $code) === 1 ? Scalars::parseInteger($code) : null;
        if ($resultCode === null) {
            throw new ResponseException('XYP response has no numeric <resultCode>');
        }

        return new ServiceResult(
            requestId: self::text(self::child($result[0], 'requestId')),
            resultCode: $resultCode,
            message: self::text(self::child($result[0], 'resultMessage')),
            data: self::child($result[0], 'response'),
        );
    }

    /**
     * A depth-first search for the first element called $name. Keys are visited in
     * sorted order, as the Go SDK does, so both pick the same element.
     *
     * @return ?array{mixed} the element's value, wrapped so a null value still counts as found
     */
    private static function find(mixed $node, string $name): ?array
    {
        if (!is_array($node)) {
            return null;
        }
        if (!array_is_list($node) && array_key_exists($name, $node)) {
            return [$node[$name]];
        }
        if (!array_is_list($node)) {
            ksort($node, SORT_STRING);
        }
        foreach ($node as $child) {
            $found = self::find($child, $name);
            if ($found !== null) {
                return $found;
            }
        }

        return null;
    }

    private static function child(mixed $node, string $name): mixed
    {
        return is_array($node) && !array_is_list($node) ? $node[$name] ?? null : null;
    }

    private static function text(mixed $node): string
    {
        return is_string($node) ? $node : '';
    }
}
