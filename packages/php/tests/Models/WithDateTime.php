<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

/** Not decodable: an internal class would silently decode into an empty value. */
final readonly class WithDateTime
{
    public function __construct(public ?\DateTimeImmutable $at = null) {}
}
