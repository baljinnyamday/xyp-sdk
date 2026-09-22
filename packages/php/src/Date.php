<?php

declare(strict_types=1);

namespace Xyp;

/**
 * A date field as XYP sent it. Providers fill these by hand and the formats are
 * not documented, so nothing is ever rejected: $raw always holds the original
 * text, and $time is set only when $raw is ISO 8601. A value without a zone is
 * read as UTC.
 *
 *     $born = $card->birthDate?->time ?? $card->birthDate?->raw;
 */
final readonly class Date implements \Stringable
{
    public function __construct(
        public string $raw = '',
        public ?\DateTimeImmutable $time = null,
    ) {}

    public function __toString(): string
    {
        return $this->raw;
    }
}
