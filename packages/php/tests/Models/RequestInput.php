<?php

declare(strict_types=1);

namespace Xyp\Tests\Models;

use Xyp\Attribute\Bytes;
use Xyp\Attribute\Wire;

/** Shaped like a generated params class, plus a private field that stays off the wire. */
final class RequestInput
{
    /**
     * @param list<int>                 $ids
     * @param array<string, mixed>|null $nested
     */
    public function __construct(
        public ?string $skipped = null,
        public ?bool $flag = null,
        public array $ids = [],
        public ?array $nested = null,
        #[Bytes]
        public ?string $photo = null,
        #[Wire('class')]
        public ?string $className = null,
        private string $secret = 'not on the wire',
    ) {}

    public function secret(): string
    {
        return $this->secret;
    }
}
