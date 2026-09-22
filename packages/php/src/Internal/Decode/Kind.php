<?php

declare(strict_types=1);

namespace Xyp\Internal\Decode;

/**
 * How the decoder fills one field. The value is the name a mismatch's problem
 * text uses ("not a valid int").
 *
 * @internal
 */
enum Kind: string
{
    case Any = 'any';
    case String = 'string';
    case Decimal = 'decimal';
    case Bytes = 'bytes';
    case Date = 'date';
    case Int = 'int';
    case Float = 'float';
    case Bool = 'bool';
    case Object = 'object';
    case List = 'list';

    /**
     * The kind a #[ListOf] item type names, or null for a class-string.
     */
    public static function ofListItem(string $type): ?self
    {
        return match ($type) {
            'string', 'int', 'float', 'bool', 'decimal', 'bytes', 'date', 'any' => self::from($type),
            default => null,
        };
    }
}
