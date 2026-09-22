<?php

declare(strict_types=1);

namespace Xyp\Attribute;

/**
 * Marks a ?string that holds binary data: base64 on the wire, raw bytes in PHP.
 */
#[\Attribute(\Attribute::TARGET_PROPERTY | \Attribute::TARGET_PARAMETER)]
final class Bytes {}
