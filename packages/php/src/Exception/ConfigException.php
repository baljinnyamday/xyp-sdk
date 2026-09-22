<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * The client, the call or the caller's response class was set up incorrectly.
 * Retrying will not help; the program has to change.
 */
final class ConfigException extends \InvalidArgumentException implements XypException
{
    public function origin(): Origin
    {
        return Origin::Config;
    }
}
