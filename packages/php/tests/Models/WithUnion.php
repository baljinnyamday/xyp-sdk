<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

/** Not decodable: a union type says nothing about which member to build. */
final readonly class WithUnion
{
    public function __construct(public int|string|null $value = null) {}
}
