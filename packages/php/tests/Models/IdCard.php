<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

use Xyp\Extras;

final readonly class IdCard
{
    public function __construct(
        public ?string $firstname = null,
        public ?string $regnum = null,
        public ?int $age = null,
        public Extras $xyp = new Extras(),
    ) {}
}
