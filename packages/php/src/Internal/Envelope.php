<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Xyp\Auth;
use Xyp\Bytes;
use Xyp\Exception\ConfigException;

/**
 * SOAP 1.1 document/literal request envelopes for XYP. Pure functions, no I/O.
 * Only the operation element is namespaced:
 *
 *     <soap:Envelope><soap:Body><tns:OP>
 *       <request> <auth><citizen/><operator/></auth> ...fields... </request>
 *     </tns:OP></soap:Body></soap:Envelope>
 *
 * @internal
 */
final class Envelope
{
    public const SOAP_NAMESPACE = 'http://schemas.xmlsoap.org/soap/envelope/';

    private const DECLARATION = "<?xml version='1.0' encoding='utf-8'?>\n";

    /**
     * @param array<mixed>|object|null $params
     *
     * @throws ConfigException
     */
    public static function build(string $operation, string $namespace, array|object|null $params, ?Auth $citizen = null, ?Auth $operator = null): string
    {
        return self::wrap($namespace, self::operation($operation, $params, $citizen, $operator));
    }

    /**
     * The <tns:OP> element, which needs no namespace yet: the client builds it before
     * it looks one up, so a bad parameter never costs a request.
     *
     * @param array<mixed>|object|null $params
     *
     * @throws ConfigException
     */
    public static function operation(string $operation, array|object|null $params, ?Auth $citizen, ?Auth $operator): string
    {
        Encoder::checkName($operation);
        $request = Encoder::wrap('request', self::auth($citizen, $operator) . Encoder::fields($params));

        return Encoder::wrap('tns:' . $operation, $request);
    }

    public static function wrap(string $namespace, string $operation): string
    {
        $attributes = 'xmlns:soap="' . self::SOAP_NAMESPACE . '" xmlns:tns="' . Encoder::escapeAttribute($namespace) . '"';

        return self::DECLARATION . '<soap:Envelope ' . $attributes . '>'
            . Encoder::wrap('soap:Body', $operation) . '</soap:Envelope>';
    }

    /**
     * <auth> comes first: every request type extends serviceRequest, so that is its
     * position in the schema and where zeep (the known-working client) puts it.
     */
    private static function auth(?Auth $citizen, ?Auth $operator): string
    {
        $fields = [];
        if ($citizen !== null) {
            $fields['citizen'] = self::authFields($citizen);
        }
        if ($operator !== null) {
            $fields['operator'] = self::authFields($operator);
        }

        return $fields === [] ? '' : Encoder::element('auth', $fields);
    }

    /**
     * The fields in the order the WSDL declares them. authType is left out when no
     * approval type was chosen; otp is always sent (see Auth).
     *
     * @return array<string, mixed>
     */
    private static function authFields(Auth $auth): array
    {
        return [
            'appAuthToken' => $auth->appAuthToken,
            'authAppName' => $auth->authAppName,
            'authType' => $auth->authType?->value,
            'certFingerprint' => $auth->certFingerprint,
            'civilId' => $auth->civilId,
            'fingerprint' => $auth->fingerprint === null ? null : new Bytes($auth->fingerprint),
            'otp' => $auth->otp,
            'regnum' => $auth->regnum,
            'signature' => $auth->signature,
        ];
    }
}
