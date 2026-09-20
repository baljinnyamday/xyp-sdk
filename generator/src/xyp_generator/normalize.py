"""Turn the portal's hand-typed catalog rows into clean IR.

The portal data is entered by people, so it contains typos ("boolena"), Java
modifiers ("private String"), variable names inside the type ("String regnum"),
tab-joined names ("String\\tregnum") and parent ids that point nowhere. Every
rule here exists because of a real row in spec/services.json.
"""

from __future__ import annotations

import re
from typing import Any

from xyp_generator.ir import Field, Kind, TypeRef

_PRIMITIVES: dict[str, Kind] = {
    "string": "string",
    "int": "int",
    "integer": "int",
    "long": "int",
    "byte": "int",
    "double": "float",
    "float": "float",
    "bigdecimal": "decimal",
    "boolean": "bool",
    "boolena": "bool",
    "date": "date",
    "byte[]": "bytes",
}
_LIST_PATTERN = re.compile(r"^List<(.+)>$", re.IGNORECASE)
_IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

ANY = TypeRef("any")


def _type_token(raw: str | None) -> str:
    """'private String' -> 'String', 'boolean isValid =' -> 'boolean', 'byte []' -> 'byte[]'."""
    if not raw:
        return ""
    cleaned = raw.strip().removeprefix("private ").replace(" []", "[]")
    return cleaned.split()[0] if cleaned else ""


def parse_type(raw: str | None, *, has_children: bool) -> TypeRef:
    token = _type_token(raw)
    list_match = _LIST_PATTERN.match(token)
    if list_match:
        return TypeRef("list", item=_scalar_or_object(list_match.group(1), has_children))
    return _scalar_or_object(token, has_children)


def _scalar_or_object(token: str, has_children: bool) -> TypeRef:
    primitive = _PRIMITIVES.get(token.lower())
    if primitive and not has_children:
        return TypeRef(primitive)
    return TypeRef("object") if has_children else ANY


def clean_name(raw: str | None) -> str | None:
    """'String\\tregnum' -> 'regnum'. Returns None for names that are not identifiers ('8')."""
    parts = (raw or "").split()
    if not parts or not _IDENTIFIER_PATTERN.match(parts[-1]):
        return None
    return parts[-1]


def build_inputs(rows: list[dict[str, Any]]) -> tuple[Field, ...]:
    fields = (
        Field(
            wire_name=name,
            doc=(row.get("wsInputDetail") or "").strip(),
            type=parse_type(row.get("wsInputDatatype"), has_children=False),
        )
        for row in rows
        if (name := clean_name(row.get("wsInputName")))
    )
    return _first_per_name(fields)


def build_outputs(rows: list[dict[str, Any]]) -> tuple[Field, ...]:
    known_ids = {row["wsResponseId"] for row in rows}
    children_of: dict[int | None, list[dict[str, Any]]] = {}
    for row in rows:
        parent = row.get("parentId")
        # Orphans (parent id that is not in this service) are treated as top-level.
        key = parent if parent in known_ids and parent != row["wsResponseId"] else None
        children_of = {**children_of, key: [*children_of.get(key, []), row]}
    return _build_level(None, children_of, frozenset())


def _build_level(
    parent: int | None,
    children_of: dict[int | None, list[dict[str, Any]]],
    visited: frozenset[int],
) -> tuple[Field, ...]:
    fields: list[Field] = []
    for row in children_of.get(parent, []):
        name = clean_name(row.get("wsResponseName"))
        row_id = row["wsResponseId"]
        if name is None or row_id in visited:
            continue
        children = _build_level(row_id, children_of, visited | {row_id})
        fields = [
            *fields,
            Field(
                wire_name=name,
                doc=(row.get("wsResponseDetail") or "").strip(),
                type=parse_type(row.get("wsResponseDatatype"), has_children=bool(children)),
                children=children,
            ),
        ]
    return _first_per_name(fields)


def _first_per_name(fields: Any) -> tuple[Field, ...]:
    unique: dict[str, Field] = {}
    for field in fields:
        if field.wire_name not in unique:
            unique = {**unique, field.wire_name: field}
    return tuple(unique.values())
