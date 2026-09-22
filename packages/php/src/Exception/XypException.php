<?php

declare(strict_types=1);

namespace Xyp\Exception;

/**
 * Implemented by every exception this SDK throws, so one catch block covers them
 * all and origin() says whose side the problem is on.
 */
interface XypException extends \Throwable
{
    public function origin(): Origin;
}
