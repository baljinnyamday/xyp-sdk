<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * XYP could not be reached at all. The transport's own exception, when there is
 * one, stays reachable through getPrevious().
 */
class ConnectionException extends \RuntimeException implements XypException
{
    /**
     * Builds the message every transport uses, so a failure reads the same whether
     * it came from curl or from a PSR-18 client.
     *
     * @param string $cause a short reason such as "Connection refused"
     */
    public static function because(string $cause, ?\Throwable $previous = null): self
    {
        return new self(self::describe($cause), 0, $previous);
    }

    final public function origin(): Origin
    {
        return Origin::Network;
    }

    protected static function describe(string $cause): string
    {
        return 'could not reach XYP (' . $cause . '). Check the VPN connection, '
            . 'the hosts entry for xyp.gov.mn and the TLS settings.';
    }
}
