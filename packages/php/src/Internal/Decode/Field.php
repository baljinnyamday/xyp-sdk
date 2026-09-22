<?php

declare(strict_types=1);

namespace Xyp\Internal\Decode;

/**
 * One constructor parameter of a response class and the element it reads.
 *
 * @internal
 */
final readonly class Field
{
    public function __construct(
        public string $parameter,
        public string $wire,
        public Type $type,
    ) {}
}
