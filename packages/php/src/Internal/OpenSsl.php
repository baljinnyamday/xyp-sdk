<?php

declare(strict_types=1);

namespace Xyp\Internal;

/**
 * @internal
 */
final class OpenSsl
{
    /**
     * Drains OpenSSL's error queue. A failed parse leaves messages behind that the
     * next unrelated openssl_* call in the application would otherwise report.
     */
    public static function clearErrors(): void
    {
        while (openssl_error_string() !== false) {
            // discard
        }
    }
}
