<?php

declare(strict_types=1);

namespace Xyp\Attribute;

/**
 * The element name on the wire, for a field whose PHP name has to differ from it.
 */
#[\Attribute(\Attribute::TARGET_PROPERTY | \Attribute::TARGET_PARAMETER)]
final class Wire
{
    public function __construct(public readonly string $name) {}
}
