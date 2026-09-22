<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

final readonly class SampleYear
{
    public function __construct(public ?int $year = null) {}
}
