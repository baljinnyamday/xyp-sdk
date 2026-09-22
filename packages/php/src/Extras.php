<?php

declare(strict_types=1);

namespace Xyp;

/**
 * What a typed response carries besides its declared fields, as its ->xyp.
 */
final readonly class Extras
{
    /**
     * @param list<Mismatch> $mismatches normally empty. Each entry is a field that
     *                                   did not fit the SDK's model; it kept its
     *                                   default value and the call still succeeded.
     * @param mixed          $raw        the whole parsed response (nested arrays,
     *                                   strings and nulls), for fields the generated
     *                                   model does not declare
     */
    public function __construct(
        public array $mismatches = [],
        public mixed $raw = null,
    ) {}
}
