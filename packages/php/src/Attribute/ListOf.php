<?php

declare(strict_types=1);

namespace Xyp\Attribute;

/**
 * Says what an array field holds, which PHP's type system cannot: one of
 * 'string', 'int', 'float', 'bool', 'decimal', 'bytes', 'date', 'any', or the
 * class-string of a response class.
 */
#[\Attribute(\Attribute::TARGET_PROPERTY | \Attribute::TARGET_PARAMETER)]
final class ListOf
{
    public function __construct(public readonly string $type) {}
}
