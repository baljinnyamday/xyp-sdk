<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

use Xyp\Attribute\ListOf;

/** Not decodable, one level down: the check has to follow nested classes. */
final readonly class WithBadNested
{
    /**
     * @param list<NoDefault> $items
     */
    public function __construct(
        #[ListOf(NoDefault::class)]
        public array $items = [],
    ) {}
}
