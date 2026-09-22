<?php

declare(strict_types=1);

namespace Xyp\Internal\Decode;

/**
 * How to build one response class from a parsed element.
 *
 * @internal
 */
final readonly class Schema
{
    /**
     * @param \ReflectionClass<object> $class
     * @param list<Field>              $fields
     * @param ?string                  $extras the parameter typed Extras, if any
     */
    public function __construct(
        public \ReflectionClass $class,
        public array $fields,
        public ?string $extras,
    ) {}
}
