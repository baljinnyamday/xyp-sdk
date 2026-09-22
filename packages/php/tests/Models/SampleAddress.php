<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

final readonly class SampleAddress
{
    public function __construct(public ?string $city = null) {}
}
