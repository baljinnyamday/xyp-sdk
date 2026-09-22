<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

/** Not decodable: XYP can leave any field out, so every parameter needs a default. */
final readonly class NoDefault
{
    public function __construct(public ?string $name) {}
}
