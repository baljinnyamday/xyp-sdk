<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Xyp\Attribute\Bytes as BytesAttribute;
use Xyp\Attribute\ListOf;
use Xyp\Attribute\Wire;
use Xyp\Bytes;
use Xyp\Date;
use Xyp\Exception\ConfigException;

/**
 * Renders request fields as XML text.
 *
 * DOMDocument and XMLWriter are not used: they escape more characters (and write
 * a line break as &#10;), which would no longer match the bytes zeep, the
 * known-working client, sends.
 *
 * Values: null, '' and [] are left out; a bool is 1 or 0 ("1"/"0" is valid for
 * xs:boolean and xs:int alike, and the portal calls some xs:int flags boolean);
 * a float is written in its shortest form; a DateTimeInterface in UTC; a Date as
 * its raw text; a Bytes value and a #[Attribute\Bytes] property as base64; a list
 * repeats the element; an array keyed by name or an object nests one.
 *
 * @internal
 */
final class Encoder
{
    /**
     * Names reach the envelope unescaped (an element name has no escaped form), so
     * a name taken from an array key, an attribute or a raw call is checked
     * instead: anything else could rewrite the request.
     */
    private const XML_NAME = '/^[A-Za-z_][A-Za-z0-9_.-]*$/D';

    /** @var array<class-string, array<string, array{string, bool}>> wire name and bytes flag per public property */
    private static array $properties = [];

    /**
     * @param array<mixed>|object|null $params
     *
     * @throws ConfigException
     */
    public static function fields(array|object|null $params): string
    {
        if ($params === null) {
            return '';
        }
        if (is_array($params)) {
            if ($params !== [] && array_is_list($params)) {
                throw new ConfigException('params must be an array keyed by element name, an object or null, got a list');
            }

            return self::map($params);
        }

        return self::object($params);
    }

    /**
     * @throws ConfigException
     */
    public static function element(string $name, mixed $value, bool $bytes = false): string
    {
        self::checkName($name);

        return match (true) {
            $value === null, $value === '', $value === [] => '',
            is_string($value) => self::wrap($name, $bytes ? base64_encode($value) : self::escapeText($value)),
            is_bool($value) => self::wrap($name, $value ? '1' : '0'),
            is_int($value) => self::wrap($name, (string) $value),
            is_float($value) => self::wrap($name, self::float($name, $value)),
            is_array($value) && array_is_list($value) => self::sequence($name, $value, $bytes),
            is_array($value) => self::wrap($name, self::map($value)),
            $value instanceof Bytes => self::wrap($name, base64_encode($value->data)),
            $value instanceof Date => self::date($name, $value),
            $value instanceof \DateTimeInterface => self::wrap($name, Scalars::formatTime($value)),
            $value instanceof \BackedEnum => self::element($name, $value->value),
            $value instanceof \UnitEnum, $value instanceof \Closure, !is_object($value) => throw new ConfigException(
                sprintf('cannot send field "%s" of type %s to XYP', $name, get_debug_type($value)),
            ),
            default => self::wrap($name, self::object($value)),
        };
    }

    /**
     * @throws ConfigException
     */
    public static function checkName(string $name): void
    {
        if (preg_match(self::XML_NAME, $name) !== 1) {
            throw new ConfigException(sprintf('"%s" cannot be sent to XYP: it is not a valid XML element name', $name));
        }
    }

    /** Escapes the three characters that are special in element content. */
    public static function escapeText(string $text): string
    {
        return strtr($text, ['&' => '&amp;', '<' => '&lt;', '>' => '&gt;']);
    }

    /** Also escapes the quote that delimits an attribute value. */
    public static function escapeAttribute(string $text): string
    {
        return strtr($text, ['&' => '&amp;', '<' => '&lt;', '>' => '&gt;', '"' => '&quot;']);
    }

    public static function wrap(string $name, string $inner): string
    {
        return $inner === '' ? '<' . $name . ' />' : '<' . $name . '>' . $inner . '</' . $name . '>';
    }

    /**
     * @param array<mixed> $fields
     */
    private static function map(array $fields): string
    {
        $out = '';
        foreach ($fields as $name => $value) {
            $out .= self::element((string) $name, $value);
        }

        return $out;
    }

    /**
     * Public properties in declaration order: the generated classes declare them in
     * the order the service's schema expects.
     */
    private static function object(object $value): string
    {
        $properties = self::properties($value::class);
        $out = '';
        foreach (get_object_vars($value) as $name => $field) {
            [$wire, $bytes] = $properties[$name] ?? [$name, false];
            $out .= self::element($wire, $field, $bytes);
        }

        return $out;
    }

    /**
     * Repeats the element once per item, which is how XML writes a list.
     *
     * @param list<mixed> $items
     */
    private static function sequence(string $name, array $items, bool $bytes): string
    {
        $out = '';
        foreach ($items as $item) {
            $out .= self::element($name, $item, $bytes);
        }

        return $out;
    }

    private static function date(string $name, Date $date): string
    {
        return match (true) {
            $date->raw !== '' => self::wrap($name, self::escapeText($date->raw)),
            $date->time !== null => self::wrap($name, Scalars::formatTime($date->time)),
            default => '',
        };
    }

    private static function float(string $name, float $value): string
    {
        if (!is_finite($value)) {
            throw new ConfigException(sprintf('cannot send field "%s" to XYP: NaN and infinity are not numbers XYP accepts', $name));
        }

        return Scalars::formatFloat($value);
    }

    /**
     * @param class-string $class
     *
     * @return array<string, array{string, bool}>
     */
    private static function properties(string $class): array
    {
        if (isset(self::$properties[$class])) {
            return self::$properties[$class];
        }
        $properties = [];
        foreach ((new \ReflectionClass($class))->getProperties(\ReflectionProperty::IS_PUBLIC) as $property) {
            if ($property->isStatic()) {
                continue;
            }
            $wire = $property->getAttributes(Wire::class)[0] ?? null;
            $listOf = $property->getAttributes(ListOf::class)[0] ?? null;
            $properties[$property->getName()] = [
                $wire?->newInstance()->name ?? $property->getName(),
                $property->getAttributes(BytesAttribute::class) !== [] || $listOf?->newInstance()->type === 'bytes',
            ];
        }

        return self::$properties[$class] = $properties;
    }
}
