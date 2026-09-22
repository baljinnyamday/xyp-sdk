<?php

declare(strict_types=1);

namespace Xyp;

/**
 * One response field that did not fit the SDK's model. The field kept its
 * default value and the call still succeeded.
 *
 * The raw value is citizen data, so nothing prints it: not __toString, not
 * var_dump, not print_r. Ask for it with value().
 */
final class Mismatch implements \Stringable
{
    /**
     * @internal built by the decoder
     *
     * @param string $path    e.g. "listData[1].year"; "" is the response itself
     * @param string $problem what did not fit, e.g. "not a valid int"
     */
    public function __construct(
        public readonly string $path,
        public readonly string $problem,
        #[\SensitiveParameter]
        private readonly mixed $value,
    ) {}

    /** The raw value XYP sent for this field. */
    public function value(): mixed
    {
        return $this->value;
    }

    /** "path (problem)", never the value. */
    public function __toString(): string
    {
        return ($this->path === '' ? '<response>' : $this->path) . ' (' . $this->problem . ')';
    }

    /**
     * @return array{path: string, problem: string}
     */
    public function __debugInfo(): array
    {
        return ['path' => $this->path, 'problem' => $this->problem];
    }
}
