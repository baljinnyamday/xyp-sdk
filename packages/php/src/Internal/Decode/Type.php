<?php

declare(strict_types=1);

namespace Xyp\Internal\Decode;

/**
 * What one field or list item decodes into.
 *
 * @internal
 */
final readonly class Type
{
    /**
     * @param ?class-string $class the response class of an Object
     * @param ?Type         $item  what a List holds
     */
    public function __construct(
        public Kind $kind,
        public ?string $class = null,
        public ?Type $item = null,
    ) {}

    /**
     * @return list<class-string> the response classes this type reaches
     */
    public function classes(): array
    {
        return match (true) {
            $this->class !== null => [$this->class],
            $this->item !== null => $this->item->classes(),
            default => [],
        };
    }
}
