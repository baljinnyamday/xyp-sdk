<?php

declare(strict_types=1);

namespace Xyp\Internal\Decode;

use Xyp\Exception\ConfigException;
use Xyp\Extras;
use Xyp\Internal\Scalars;
use Xyp\Mismatch;

/**
 * Soft validation of responses.
 *
 * Response classes are generated from XYP's public catalog, which is typed by
 * hand, so real data can disagree with them. A successful call must never be lost
 * to that: a value that does not fit leaves its field at its default, its raw
 * value is kept in the mismatches, and a warning says which field it was and that
 * the SDK's model (not XYP, not the caller) is wrong.
 *
 * @internal
 */
final class Decoder
{
    /** @var list<Mismatch> */
    private array $mismatches = [];

    private function __construct() {}

    /**
     * Builds $class from the parsed response. Fails only when $class itself is wrong.
     *
     * @template T of object
     *
     * @param class-string<T> $class
     *
     * @return array{T, list<Mismatch>}
     *
     * @throws ConfigException
     */
    public static function decode(string $class, mixed $tree): array
    {
        $schema = Schemas::of($class);
        $decoder = new self();
        $fields = [];
        if (self::isObject($tree)) {
            $fields = $tree;
        } elseif ($tree !== null) {
            $decoder->report('', 'expected an object', $tree);
        }
        $arguments = $decoder->arguments($schema, $fields, '');
        if ($schema->extras !== null) {
            $arguments[$schema->extras] = new Extras($decoder->mismatches, $tree);
        }

        return [(new \ReflectionClass($class))->newInstanceArgs($arguments), $decoder->mismatches];
    }

    /**
     * Decodes every declared field. A field that does not fit, or that XYP left
     * out, is not passed, so it keeps its default; fields XYP sent that the class
     * does not declare stay reachable through Extras::$raw.
     *
     * @param array<mixed> $fields
     *
     * @return array<string, mixed>
     */
    private function arguments(Schema $schema, array $fields, string $path): array
    {
        $arguments = [];
        foreach ($schema->fields as $field) {
            [$fits, $value] = $this->value($field->type, $fields[$field->wire] ?? null, $path === '' ? $field->wire : $path . '.' . $field->wire);
            if ($fits && $value !== null) {
                $arguments[$field->parameter] = $value;
            }
        }

        return $arguments;
    }

    /**
     * @return array{bool, mixed} whether the node fit, and its value (null: keep the
     *                            default). When it did not fit, the mismatch has
     *                            already been reported.
     */
    private function value(Type $type, mixed $node, string $path): array
    {
        if ($node === null) {
            return [true, null];
        }

        return match ($type->kind) {
            Kind::Any => [true, $node],
            Kind::List => $this->list($type, $node, $path),
            Kind::Object => $this->object($type, $node, $path),
            default => $this->scalar($type->kind, $node, $path),
        };
    }

    /**
     * @return array{bool, mixed}
     */
    private function object(Type $type, mixed $node, string $path): array
    {
        if (!self::isObject($node) || $type->class === null) {
            return $this->reject('expected an object', $node, $path);
        }
        $schema = Schemas::of($type->class);

        return [true, $schema->class->newInstanceArgs($this->arguments($schema, $node, $path))];
    }

    /**
     * @return array{bool, mixed}
     */
    private function list(Type $type, mixed $node, string $path): array
    {
        // XML cannot tell a one-item list from a single value; the class can.
        $items = is_array($node) && array_is_list($node) ? $node : [$node];
        $list = [];
        $index = 0;
        $fits = true;
        foreach ($items as $item) {
            // An empty or nil item carries no data and would break the list<T> promise.
            if ($item === null || $type->item === null) {
                continue;
            }
            [$itemFits, $value] = $this->value($type->item, $item, $path . '[' . $index . ']');
            ++$index;
            if (!$itemFits) {
                $fits = false;

                continue;
            }
            $list[] = $value;
        }

        // A list never holds a default standing in for a rejected item: when one item
        // does not fit, the whole list is dropped and the raw items stay in the
        // mismatches.
        return $fits ? [true, $list] : [false, null];
    }

    /**
     * @return array{bool, mixed}
     */
    private function scalar(Kind $kind, mixed $node, string $path): array
    {
        if (!is_string($node)) {
            $shape = is_array($node) && array_is_list($node) ? 'a list' : 'an object';

            return $this->reject('expected ' . $kind->value . ', got ' . $shape, $node, $path);
        }
        $value = match ($kind) {
            Kind::String => $node,
            // Kept as text: no precision loss, whatever the caller parses it with.
            Kind::Decimal => Scalars::isDecimal($node) ? $node : null,
            // Never rejected: an unreadable date is kept as text.
            Kind::Date => Scalars::date($node),
            Kind::Bytes => Scalars::bytes($node),
            Kind::Int => Scalars::integer($node),
            Kind::Float => Scalars::float($node),
            Kind::Bool => Scalars::boolean($node),
            default => null,
        };

        return $value === null ? $this->reject('not a valid ' . $kind->value, $node, $path) : [true, $value];
    }

    /**
     * @return array{false, null}
     */
    private function reject(string $problem, mixed $node, string $path): array
    {
        $this->report($path, $problem, $node);

        return [false, null];
    }

    private function report(string $path, string $problem, mixed $value): void
    {
        $this->mismatches[] = new Mismatch($path, $problem, $value);
    }

    /**
     * @phpstan-assert-if-true array<mixed> $node
     */
    private static function isObject(mixed $node): bool
    {
        return is_array($node) && ($node === [] || !array_is_list($node));
    }
}
