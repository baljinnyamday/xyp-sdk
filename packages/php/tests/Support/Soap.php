<?php

declare(strict_types=1);

namespace Xyp\Tests\Support;

final class Soap
{
    public const REGNUM = 'РД00000000';

    public const ID_CARD = '<firstname>Бат</firstname><regnum>' . self::REGNUM . '</regnum>';

    public const REQUEST_ID = '4fd9aa5f-1984-4b61-b379-13c1bcbd29c7';

    private const XSI = 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"';

    /** Shaped like the sample on developer.xyp.gov.mn/docs/result-code. */
    public static function response(string $inner, int $code = 0, string $message = 'ok'): string
    {
        return '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <ns2:WS100101_getCitizenIDCardInfoResponse xmlns:ns2="http://citizen.xyp.gov.mn/">
      <return>
        <request ' . self::XSI . ' xsi:type="ns2:citizenRequestData"/>
        <requestId>' . self::REQUEST_ID . '</requestId>
        <response ' . self::XSI . ' xsi:type="ns2:citizenData">' . $inner . '</response>
        <resultCode>' . $code . '</resultCode>
        <resultMessage>' . $message . '</resultMessage>
      </return>
    </ns2:WS100101_getCitizenIDCardInfoResponse>
  </soap:Body>
</soap:Envelope>';
    }

    public static function fault(string $reason): string
    {
        return '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>'
            . '<soap:Fault><faultcode>soap:Client</faultcode><faultstring>' . $reason
            . '</faultstring></soap:Fault></soap:Body></soap:Envelope>';
    }
}
