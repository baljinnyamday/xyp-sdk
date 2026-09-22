<?php

declare(strict_types=1);

namespace Xyp\Internal;

/**
 * Reads a setting from the environment the way PHP applications set it: the
 * process environment first, then $_ENV and $_SERVER, which is where dotenv
 * loaders and PHP-FPM's env[] put values that getenv() may not see. An empty
 * value counts as unset.
 *
 * @internal
 */
final class Environment
{
    public static function get(string $name): ?string
    {
        foreach ([getenv($name), $_ENV[$name] ?? null, $_SERVER[$name] ?? null] as $value) {
            if (is_string($value) && $value !== '') {
                return $value;
            }
        }

        return null;
    }
}
