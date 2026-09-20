"""Python-specific identifier rules."""

from __future__ import annotations

import keyword
from collections.abc import Iterable

from xyp_generator.naming import snake_case

# Attribute names pydantic's BaseModel already uses; a field with the same name would shadow them.
_PYDANTIC_RESERVED = frozenset(
    {
        "copy",
        "dict",
        "json",
        "schema",
        "schema_json",
        "validate",
        "construct",
        "parse_obj",
        "parse_raw",
        "parse_file",
        "from_orm",
        "update_forward_refs",
        "register",
        "mro",
    }
)
# A field called `list` would shadow the builtin inside the class body and break `list[...]`.
_ANNOTATION_BUILTINS = frozenset({"list", "str", "int", "float", "bool", "bytes"})
_PROTECTED_PREFIX = "model_"


def python_name(wire_name: str, reserved: Iterable[str] = ()) -> str:
    name = snake_case(wire_name) or "field"
    if name[0].isdigit():
        name = f"field_{name}"
    needs_suffix = (
        keyword.iskeyword(name)
        or name in _PYDANTIC_RESERVED
        or name in _ANNOTATION_BUILTINS
        or name in set(reserved)
        or name.startswith(_PROTECTED_PREFIX)
    )
    return f"{name}_" if needs_suffix else name


def unique_names(wire_names: Iterable[str], reserved: Iterable[str] = ()) -> dict[str, str]:
    """wire name -> python name, suffixing `_2`, `_3`... when snake_case makes two names equal."""
    taken: dict[str, str] = {}
    used: frozenset[str] = frozenset()
    for wire_name in wire_names:
        base = python_name(wire_name, reserved)
        candidate = base
        counter = 2
        while candidate in used:
            candidate = f"{base}_{counter}"
            counter += 1
        taken = {**taken, wire_name: candidate}
        used = used | {candidate}
    return taken
