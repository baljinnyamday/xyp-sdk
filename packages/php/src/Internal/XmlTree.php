<?php

declare(strict_types=1);

namespace Xyp\Internal;

use Xyp\Exception\ResponseException;

/**
 * Turns a response document into nested PHP values: a leaf becomes its trimmed
 * text (null when empty), an element with children becomes an array keyed by
 * local name, and repeated sibling names become a list. Namespace prefixes and
 * attributes are ignored; xsi:nil="true" (or "1") is the one exception, and
 * reads as null.
 *
 * Objects and lists are both PHP arrays and never clash: an element name is never
 * an integer key, and a list only exists once a name repeats, so it has at least
 * two items.
 *
 * @internal
 */
final class XmlTree
{
    /** Go's strings.TrimSpace: Unicode white space, not just ASCII. */
    private const SPACE = '/^[\s\x{85}\p{Z}]+|[\s\x{85}\p{Z}]+$/u';

    /**
     * @return array<string, mixed> the root element (or elements) by name
     *
     * @throws ResponseException when the payload is not well-formed XML or holds a DTD
     */
    public static function parse(string $payload): array
    {
        if (trim($payload) === '') {
            throw self::invalid();
        }
        $internalErrors = libxml_use_internal_errors(true);
        try {
            // LIBXML_NONET, and no LIBXML_NOENT or DTD loading: nothing in a response
            // reaches the network or expands an entity.
            $reader = \XMLReader::XML($payload, null, LIBXML_NONET | LIBXML_COMPACT);
            if (!$reader instanceof \XMLReader) {
                throw self::invalid();
            }
            $document = self::read($reader);
            $failed = libxml_get_errors() !== [];
            $reader->close();
        } finally {
            libxml_clear_errors();
            libxml_use_internal_errors($internalErrors);
        }
        if ($failed) {
            throw self::invalid();
        }

        return $document;
    }

    public static function invalid(): ResponseException
    {
        return new ResponseException('XYP returned a response that is not valid XML');
    }

    /**
     * Reads the stream with an explicit stack instead of recursion, so a deeply
     * nested payload cannot exhaust PHP's stack.
     *
     * @return array<string, mixed>
     */
    private static function read(\XMLReader $reader): array
    {
        /** @var list<XmlFrame> $stack */
        $stack = [];
        $document = [];
        while ($reader->read()) {
            switch ($reader->nodeType) {
                case \XMLReader::DOC_TYPE:
                case \XMLReader::ENTITY_REF:
                    // SOAP forbids DTDs, and refusing them outright rules out
                    // entity-expansion attacks.
                    throw self::invalid();
                case \XMLReader::ELEMENT:
                    $frame = new XmlFrame($reader->localName, self::isNil($reader));
                    if ($reader->isEmptyElement) {
                        self::close($stack, $document, $frame);
                    } else {
                        $stack[] = $frame;
                    }
                    break;
                case \XMLReader::TEXT:
                case \XMLReader::CDATA:
                case \XMLReader::WHITESPACE:
                case \XMLReader::SIGNIFICANT_WHITESPACE:
                    if ($stack !== []) {
                        $stack[count($stack) - 1]->text .= $reader->value;
                    }
                    break;
                case \XMLReader::END_ELEMENT:
                    $frame = array_pop($stack);
                    if ($frame === null) {
                        throw self::invalid();
                    }
                    self::close($stack, $document, $frame);
                    break;
            }
        }
        if ($stack !== []) {
            throw self::invalid(); // the document ended inside an element
        }

        return $document;
    }

    /**
     * @param list<XmlFrame>       $stack
     * @param array<string, mixed> $document
     */
    private static function close(array $stack, array &$document, XmlFrame $frame): void
    {
        $value = match (true) {
            $frame->nil => null,
            $frame->children !== [] => $frame->children,
            default => self::trim($frame->text),
        };
        if ($stack === []) {
            XmlFrame::addChild($document, $frame->name, $value);
        } else {
            XmlFrame::addChild($stack[count($stack) - 1]->children, $frame->name, $value);
        }
    }

    private static function isNil(\XMLReader $reader): bool
    {
        $nil = false;
        if ($reader->hasAttributes) {
            while ($reader->moveToNextAttribute()) {
                if ($reader->localName === 'nil' && ($reader->value === 'true' || $reader->value === '1')) {
                    $nil = true;
                }
            }
            $reader->moveToElement();
        }

        return $nil;
    }

    private static function trim(string $text): ?string
    {
        $trimmed = preg_replace(self::SPACE, '', $text) ?? trim($text);

        return $trimmed === '' ? null : $trimmed;
    }
}
