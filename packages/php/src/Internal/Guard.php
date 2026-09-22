<?php

declare(strict_types=1);

namespace Xyp\Internal;

/**
 * Runs an ext function with PHP warnings turned into an \ErrorException, so its
 * complaint becomes a typed SDK exception instead of output, and nobody needs @.
 * Callers must not copy the ErrorException's message into theirs when it can
 * hold a path or key material.
 *
 * @internal
 */
final class Guard
{
    /**
     * @template T
     *
     * @param callable(): T $operation
     *
     * @return T
     *
     * @throws \ErrorException
     */
    public static function run(callable $operation): mixed
    {
        set_error_handler(static function (int $severity, string $message, string $file, int $line): never {
            throw new \ErrorException($message, 0, $severity, $file, $line);
        });
        try {
            return $operation();
        } finally {
            restore_error_handler();
        }
    }
}
