<?php

declare(strict_types=1);

namespace Xyp\Internal;

/**
 * The <return> element every XYP service answers with.
 *
 * @internal
 */
final readonly class ServiceResult
{
    public function __construct(
        public string $requestId,
        public int $resultCode,
        public string $message,
        public mixed $data,
    ) {}
}
