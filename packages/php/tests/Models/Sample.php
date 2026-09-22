<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

use Xyp\Attribute\Bytes;
use Xyp\Attribute\Decimal;
use Xyp\Attribute\ListOf;
use Xyp\Date;
use Xyp\Extras;

/** Every field kind the decoder knows, shaped like a generated response class. */
final readonly class Sample
{
    /**
     * @param list<SampleYear> $listData
     */
    public function __construct(
        public ?string $firstName = null,
        public ?int $age = null,
        public ?bool $active = null,
        public ?Date $born = null,
        #[Bytes]
        public ?string $photo = null,
        #[Decimal]
        public ?string $amount = null,
        public ?float $ratio = null,
        #[ListOf(SampleYear::class)]
        public array $listData = [],
        public ?SampleAddress $address = null,
        public mixed $anything = null,
        public Extras $xyp = new Extras(),
    ) {}
}
