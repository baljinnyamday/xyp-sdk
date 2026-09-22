<?php

declare(strict_types=1);

namespace Xyp\Internal\Decode;

use Xyp\Attribute\Bytes;
use Xyp\Attribute\Decimal;
use Xyp\Attribute\ListOf;
use Xyp\Attribute\Wire;
use Xyp\Date;
use Xyp\Exception\ConfigException;
use Xyp\Extras;

/**
 * Reads a response class's constructor once and remembers it. A class the decoder
 * cannot fill is a mistake in the caller's code, not in XYP's answer, so it is a
 * ConfigException, raised before any request is sent.
 *
 * @internal
 */
final class Schemas
{
    /** @var array<class-string, Schema> */
    private static array $schemas = [];

    /** @var array<class-string, true> classes whose whole graph has been checked */
    private static array $checked = [];

    /**
     * @param class-string $class
     *
     * @throws ConfigException
     */
    public static function of(string $class): Schema
    {
        if (!isset(self::$checked[$class])) {
            $seen = [];
            self::check($class, $seen);
            self::$checked += $seen;
        }

        return self::$schemas[$class];
    }

    /**
     * @param class-string              $class
     * @param array<class-string, true> $seen
     */
    private static function check(string $class, array &$seen): void
    {
        if (isset($seen[$class]) || isset(self::$checked[$class])) {
            return;
        }
        $seen[$class] = true;
        $schema = self::$schemas[$class] ??= self::build($class);
        foreach ($schema->fields as $field) {
            foreach ($field->type->classes() as $nested) {
                self::check($nested, $seen);
            }
        }
    }

    /**
     * @param class-string $class
     */
    private static function build(string $class): Schema
    {
        if (!class_exists($class)) {
            throw new ConfigException(sprintf('%s is not a class', $class));
        }
        $reflection = new \ReflectionClass($class);
        // An internal class such as DateTimeImmutable would silently decode into an
        // empty value; an abstract class or an enum cannot be built at all.
        if ($reflection->isInternal() || !$reflection->isInstantiable()) {
            throw new ConfigException(sprintf('%s is not a response class the SDK can build', $class));
        }
        $parameters = $reflection->getConstructor()?->getParameters() ?? [];
        if ($parameters === []) {
            throw new ConfigException(sprintf('%s has no constructor parameters the SDK could fill', $class));
        }
        $fields = [];
        $extras = null;
        foreach ($parameters as $parameter) {
            $where = sprintf('%s::__construct() parameter $%s', $class, $parameter->getName());
            if (!$parameter->isDefaultValueAvailable()) {
                throw new ConfigException($where . ' needs a default value: XYP can leave any field out');
            }
            $type = $parameter->getType();
            if ($type instanceof \ReflectionNamedType && $type->getName() === Extras::class) {
                $extras = $parameter->getName();

                continue;
            }
            $wire = $parameter->getAttributes(Wire::class)[0] ?? null;
            $fields[] = new Field(
                $parameter->getName(),
                $wire?->newInstance()->name ?? $parameter->getName(),
                self::type($parameter, $type, $reflection, $where),
            );
        }

        return new Schema($reflection, $fields, $extras);
    }

    /**
     * @param \ReflectionClass<object> $declaring
     */
    private static function type(\ReflectionParameter $parameter, ?\ReflectionType $type, \ReflectionClass $declaring, string $where): Type
    {
        if ($type === null) {
            return new Type(Kind::Any);
        }
        if (!$type instanceof \ReflectionNamedType) {
            throw new ConfigException($where . ' has a union or intersection type, which the SDK cannot decode into');
        }
        $name = $type->getName();

        return match ($name) {
            'mixed' => new Type(Kind::Any),
            'string' => new Type(match (true) {
                $parameter->getAttributes(Decimal::class) !== [] => Kind::Decimal,
                $parameter->getAttributes(Bytes::class) !== [] => Kind::Bytes,
                default => Kind::String,
            }),
            'int' => new Type(Kind::Int),
            'float' => new Type(Kind::Float),
            'bool' => new Type(Kind::Bool),
            'array' => self::listType($parameter, $where),
            Date::class => new Type(Kind::Date),
            'self' => new Type(Kind::Object, $declaring->getName()),
            default => $type->isBuiltin()
                ? throw new ConfigException(sprintf(
                    '%s is typed %s; use ?string, ?int, ?float, ?bool, ?Xyp\Date, mixed, a response class or an array with #[ListOf]',
                    $where,
                    $name,
                ))
                : self::objectType($name, $where),
        };
    }

    private static function listType(\ReflectionParameter $parameter, string $where): Type
    {
        $listOf = $parameter->getAttributes(ListOf::class)[0] ?? null;
        if ($listOf === null) {
            throw new ConfigException($where . ' is an array without #[ListOf(...)] saying what it holds');
        }
        $item = $listOf->newInstance()->type;
        $kind = Kind::ofListItem($item);

        return new Type(Kind::List, item: $kind !== null ? new Type($kind) : self::objectType($item, $where));
    }

    private static function objectType(string $class, string $where): Type
    {
        if (!class_exists($class)) {
            throw new ConfigException(sprintf('%s names %s, which is not a class', $where, $class));
        }

        return new Type(Kind::Object, $class);
    }
}
