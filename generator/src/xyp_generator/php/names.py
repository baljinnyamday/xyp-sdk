"""PHP-specific identifier rules."""

from __future__ import annotations

import re

# PHP also accepts bytes 0x80-0xff in names, but XYP's wire names are ASCII and a
# name that is not plain ASCII is better spelled out through #[Wire].
_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_WORD_SEPARATOR = re.compile(r"[^A-Za-z0-9]+")
_INVALID_CHARACTERS = re.compile(r"[^A-Za-z0-9_]+")

# `$this` is the one variable name PHP refuses for a (promoted constructor) parameter.
_UNUSABLE_VARIABLES = frozenset({"this"})

# Names PHP refuses for a class: keywords, plus the reserved type names. Every
# generated class name carries an operation prefix, so this is a guard, not a rule
# the catalog needs today. Lowercase, because class names are case-insensitive.
RESERVED_CLASS_NAMES = frozenset(
    {
        "abstract",
        "and",
        "array",
        "as",
        "bool",
        "break",
        "callable",
        "case",
        "catch",
        "class",
        "clone",
        "const",
        "continue",
        "declare",
        "default",
        "die",
        "do",
        "echo",
        "else",
        "elseif",
        "empty",
        "enddeclare",
        "endfor",
        "endforeach",
        "endif",
        "endswitch",
        "endwhile",
        "enum",
        "eval",
        "exit",
        "extends",
        "false",
        "final",
        "finally",
        "float",
        "fn",
        "for",
        "foreach",
        "function",
        "global",
        "goto",
        "if",
        "implements",
        "include",
        "include_once",
        "instanceof",
        "insteadof",
        "int",
        "interface",
        "isset",
        "iterable",
        "list",
        "match",
        "mixed",
        "namespace",
        "never",
        "new",
        "null",
        "object",
        "or",
        "parent",
        "print",
        "private",
        "protected",
        "public",
        "readonly",
        "require",
        "require_once",
        "return",
        "self",
        "static",
        "string",
        "switch",
        "throw",
        "trait",
        "true",
        "try",
        "unset",
        "use",
        "var",
        "void",
        "while",
        "xor",
        "yield",
    }
)


def upper_first(text: str) -> str:
    return text[:1].upper() + text[1:]


def is_identifier(name: str) -> bool:
    return bool(_IDENTIFIER.match(name))


def class_part(wire_name: str) -> str:
    """'created_date' -> 'CreatedDate', 'civilId' -> 'CivilId', 'ttv6_2_1' -> 'Ttv621'.

    The Go SDK's rule, so a nested class is named like its Go struct. XYP's own
    spelling is kept inside each word. Only ever appended to an operation name, so a
    leading digit ('2nd' -> '2nd') needs no guard.
    """
    return "".join(upper_first(part) for part in _WORD_SEPARATOR.split(wire_name))


def group_property(group: str) -> str:
    """'labor_welfare' -> 'laborWelfare', the client property (as in TypeScript)."""
    head, *rest = group.split("_")
    return head + "".join(upper_first(part) for part in rest)


def group_namespace(group: str) -> str:
    """'labor_welfare' -> 'LaborWelfare', the namespace segment and directory."""
    return upper_first(group_property(group))


def method_name(short: str) -> str:
    """The XYP short name as-is ('getCitizenIDCardInfo'). PHP accepts keywords as
    method names; anything else that is not an identifier is a catalog problem."""
    if not is_identifier(short) or short.startswith("__"):
        raise ValueError(f"XYP operation name {short!r} is not a usable PHP method name")
    return short


def property_name(wire_name: str, taken: frozenset[str] = frozenset()) -> str:
    """The wire name when PHP can use it as a promoted constructor parameter.

    Otherwise a stand-in that is ('this' -> 'this_', 'first-name' -> 'first_name',
    '2nd' -> '_2nd'); the class then carries #[Wire] with the real name. `taken`
    holds names the class already uses for something else, such as `xyp`.
    """
    name = wire_name
    if not is_identifier(name):
        name = _INVALID_CHARACTERS.sub("_", name) or "_"
        name = f"_{name}" if name[0].isdigit() else name
    if name in _UNUSABLE_VARIABLES or name in taken:
        name = f"{name}_"
    return name
