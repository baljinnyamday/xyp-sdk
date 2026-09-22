<?php

declare(strict_types=1);

namespace Xyp\Internal;

/**
 * One open element while XmlTree reads a document.
 *
 * @internal
 */
final class XmlFrame
{
    public string $text = '';

    /** @var array<string, mixed> */
    public array $children = [];

    public function __construct(
        public readonly string $name,
        public readonly bool $nil,
    ) {}

    /**
     * Records a child; a name seen before turns into a list of its values. An
     * element's own value is never a list, so a list found here is one built here.
     *
     * @param array<string, mixed> $parent
     */
    public static function addChild(array &$parent, string $name, mixed $value): void
    {
        if (!array_key_exists($name, $parent)) {
            $parent[$name] = $value;

            return;
        }
        $existing = $parent[$name];
        if (is_array($existing) && array_is_list($existing)) {
            $existing[] = $value;
            $parent[$name] = $existing;

            return;
        }
        $parent[$name] = [$existing, $value];
    }
}
