<?php

declare(strict_types=1);

namespace Xyp;

/**
 * Binary data for a raw call(): a plain PHP string cannot say whether it is text
 * or bytes, so wrap bytes in this and the SDK sends them base64-encoded.
 * Generated parameter classes mark such fields with #[Attribute\Bytes] instead.
 */
final readonly class Bytes
{
    public function __construct(public string $data) {}
}
