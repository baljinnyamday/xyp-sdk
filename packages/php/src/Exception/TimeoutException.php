<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * The request ran out of time, which makes it worth retrying, unlike a refused
 * connection. Still a ConnectionException, so one catch block covers both.
 */
final class TimeoutException extends ConnectionException
{
    public static function timedOut(?\Throwable $previous = null): self
    {
        return new self(self::describe('timeout'), 0, $previous);
    }
}
