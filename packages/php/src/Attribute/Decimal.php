<?php

declare(strict_types=1);

namespace Xyp\Attribute;

/**
 * Marks a ?string that holds a BigDecimal. It stays text, because a float cannot
 * hold every value XYP writes into one; parse it with the precision you need.
 */
#[\Attribute(\Attribute::TARGET_PROPERTY | \Attribute::TARGET_PARAMETER)]
final class Decimal {}
