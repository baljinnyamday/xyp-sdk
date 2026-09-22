<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * Whose side a problem is on, so logs and dashboards can route it.
 */
enum Origin: string
{
    /** How the client was set up: a bad key, an unknown operation, a bad parameter. */
    case Config = 'config';
    /** The VPN, the hosts entry, DNS, TLS or a timeout. */
    case Network = 'network';
    /** XYP or the data provider answering with an error. */
    case Xyp = 'xyp';
    /** A gap in this SDK. Please report it. */
    case Sdk = 'sdk';
}
