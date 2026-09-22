<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

/** Not decodable: an array has to say what it holds. */
final readonly class UntypedList
{
    /**
     * @param array<mixed> $items
     */
    public function __construct(public array $items = []) {}
}
