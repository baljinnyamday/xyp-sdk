<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

use Xyp\Attribute\Wire;

/** A wire name PHP cannot use as-is, and a class that refers to itself. */
final readonly class Renamed
{
    public function __construct(
        #[Wire('this')]
        public ?string $this_ = null,
        public ?Renamed $child = null,
    ) {}
}
