<?php

declare(strict_types=1);

namespace Xyp\Internal;

/**
 * Names what is wrong with a file by its class only. The path and the contents
 * stay out of messages: a key file's path can be sensitive, its contents are.
 *
 * @internal
 */
final class FileProblem
{
    /**
     * @return ?string null when the file looks readable
     */
    public static function of(string $path): ?string
    {
        clearstatcache(true, $path);

        return match (true) {
            $path === '' || str_contains($path, "\0") || !file_exists($path) => 'no such file',
            is_dir($path) => 'unreadable',
            !is_readable($path) => 'permission denied',
            default => null,
        };
    }
}
