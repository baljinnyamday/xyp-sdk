"""Java-specific identifier, comment and literal rules."""

from __future__ import annotations

import re

# JLS 3.9 reserved keywords, the three literals (3.10.3, 3.10.7), and the
# contextual keywords that cannot name everything an identifier can (`var`,
# `yield`, `record` are no type names and `yield(...)` cannot be called
# unqualified; `_` is no identifier at all since Java 9). `sealed`, `permits` and
# `when` are legal names, but a field called `sealed` next to a sealed class reads
# like a mistake, so they are kept out too. The module-only words (`module`,
# `requires`, `to`, `with`, ...) are ordinary identifiers outside module-info.java,
# and `to` or `with` are plausible wire names, so they are deliberately allowed.
JAVA_KEYWORDS: frozenset[str] = frozenset(
    {
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while",
        "true", "false", "null",
        "_", "var", "yield", "record", "sealed", "permits", "when",
    }
)  # fmt: skip

# JLS 8.10.1 forbids these record component names, because their accessors would
# collide with java.lang.Object's methods. `equals` is legal on paper, but an
# `equals()` accessor next to `equals(Object)` is a trap for every reader, so the
# same rule covers classes' accessors too.
OBJECT_METHODS: frozenset[str] = frozenset(
    {
        "clone", "equals", "finalize", "getClass", "hashCode", "notify",
        "notifyAll", "toString", "wait",
    }
)  # fmt: skip

_UPPER_RUN = re.compile(r"^[A-Z]+")
_IDENTIFIER = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")
_DIGIT_PREFIX_FIELD = "x"
_DIGIT_PREFIX_TYPE = "X"
_RESERVED_SUFFIX = "_"


def upper_first(text: str) -> str:
    return text[:1].upper() + text[1:]


def decapitalize(word: str) -> str:
    """Lower the leading upper-case run, keeping its last capital when that capital
    starts the next word: 'EDate' -> 'eDate', 'URLValue' -> 'urlValue',
    'WS100103' -> 'ws100103', 'OrgName' -> 'orgName', 'civilId' -> 'civilId'.

    Plain lower_first gets 'eDate' right but turns 'URLValue' into 'uRLValue' and
    'QRCodeGeneration...' into 'qRCodeGeneration...', which nobody writes by hand.
    """
    match = _UPPER_RUN.match(word)
    if not match:
        return word
    run = match.end()
    if run >= 2 and run < len(word) and word[run].islower():
        run -= 1
    return word[:run].lower() + word[run:]


def _parts(wire_name: str) -> list[str]:
    return [part for part in wire_name.split("_") if part]


def java_field_name(wire_name: str, reserved: frozenset[str] = frozenset()) -> str:
    """'created_date' -> 'createdDate', 'ttv5_1' -> 'ttv51', 'EDate' -> 'eDate',
    'false' -> 'false_'.

    XYP's spelling is kept inside each word (civilId stays civilId), so the Java
    name still resembles the wire name the generated code sends. A name Java will
    not accept, or one in `reserved` (what the enclosing type already declares),
    gets a trailing '_', which no wire name in the catalog ends with.
    """
    head, *tail = _parts(wire_name) or [""]
    name = decapitalize(head) + "".join(upper_first(part) for part in tail)
    if not name or name[0].isdigit():
        name = f"{_DIGIT_PREFIX_FIELD}{name}"
    if name in JAVA_KEYWORDS or name in OBJECT_METHODS or name in reserved:
        name = f"{name}{_RESERVED_SUFFIX}"
    return name


def java_type_name(wire_name: str) -> str:
    """'listAddress' -> 'ListAddress', 'ttv5_1' -> 'Ttv51'. No Java keyword starts
    with a capital, so only a leading digit needs handling."""
    name = "".join(upper_first(part) for part in _parts(wire_name))
    if not name or name[0].isdigit():
        name = f"{_DIGIT_PREFIX_TYPE}{name}"
    return name


def java_package_name(group: str) -> str:
    """'labor_welfare' -> 'laborwelfare', the same package name as the Go SDK."""
    name = group.replace("_", "").lower()
    if not _IDENTIFIER.match(name) or name in JAVA_KEYWORDS:
        raise ValueError(f"Group {group!r} does not make a Java package name")
    return name


def group_class_name(group: str) -> str:
    """'foreign_service' -> 'ForeignServiceClient'."""
    return f"{java_type_name(group)}Client"


def group_accessor_name(group: str) -> str:
    """'labor_welfare' -> 'laborWelfare', the XypClient method that returns the group."""
    name = java_field_name(group)
    if name.endswith(_RESERVED_SUFFIX):
        raise ValueError(f"Group {group!r} does not make a Java method name")
    return name


def is_identifier(name: str) -> bool:
    """Spelled like an ASCII Java identifier; keywords are the caller's business."""
    return bool(_IDENTIFIER.match(name))


def javadoc(text: str) -> str:
    """Catalog text made safe for a Javadoc comment, on one line like Go's `_doc`.

    javac turns every \\uXXXX into its character before it even finds the
    comments, so a backslash must never reach the source: '\\u002a/' in a
    description would end the comment. '*/' ends it directly. '<', '>' and '&'
    would be read as HTML, and '@' as the start of a block or inline tag.
    Order matters: '&' first, so the entities added later are not escaped again.
    """
    collapsed = " ".join("".join(ch if ch.isprintable() else " " for ch in text).split())
    return (
        collapsed.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\\", "&#92;")
        .replace("@", "{@literal @}")
        .replace("*/", "*&#47;")
    )


def java_string(value: str) -> str:
    """A Java string literal. Escaping each backslash also stops javac from reading
    a '\\u' sequence as a Unicode escape (JLS 3.3: it needs an even run before it)."""
    escapes = {"\\": "\\\\", '"': '\\"', "\n": "\\n", "\r": "\\r", "\t": "\\t"}
    return '"' + "".join(escapes.get(ch) or _literal_char(ch) for ch in value) + '"'


def _literal_char(ch: str) -> str:
    if ch.isprintable():
        return ch
    # A Java char is a UTF-16 unit, so a code point above U+FFFF is two escapes.
    units = ch.encode("utf-16-be")
    return "".join(
        f"\\u{int.from_bytes(units[i : i + 2], 'big'):04x}" for i in range(0, len(units), 2)
    )
