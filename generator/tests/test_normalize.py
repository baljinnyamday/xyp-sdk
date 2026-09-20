"""Each case is a real row shape found in spec/services.json."""

from __future__ import annotations

import pytest

from xyp_generator.ir import TypeRef
from xyp_generator.normalize import build_inputs, build_outputs, clean_name, parse_type


@pytest.mark.parametrize(
    ("raw", "kind"),
    [
        ("String", "string"),
        ("private String", "string"),
        ("String regnum", "string"),
        ("int", "int"),
        ("Integer", "int"),
        ("Long", "int"),
        ("int timeZone =", "int"),
        ("double", "float"),
        ("BigDecimal", "decimal"),
        ("boolena", "bool"),
        ("boolean isValid =", "bool"),
        ("private Date", "date"),
        ("byte[]", "bytes"),
        ("byte []", "bytes"),
        (None, "any"),
        ("AddressData", "any"),
        ("private Document", "any"),
    ],
)
def test_scalar_types(raw: str | None, kind: str) -> None:
    assert parse_type(raw, has_children=False) == TypeRef(kind)  # type: ignore[arg-type]


def test_lists_and_objects() -> None:
    assert parse_type("List<String>", has_children=False) == TypeRef("list", TypeRef("string"))
    assert parse_type("List<T>", has_children=True) == TypeRef("list", TypeRef("object"))
    assert parse_type("List<t>", has_children=False) == TypeRef("list", TypeRef("any"))
    assert parse_type("List<VaccineData>", has_children=True) == TypeRef("list", TypeRef("object"))
    assert parse_type("AddressData", has_children=True) == TypeRef("object")
    # The portal sometimes types a parent as String; its children win.
    assert parse_type("String", has_children=True) == TypeRef("object")


def test_names() -> None:
    assert clean_name("regnum") == "regnum"
    assert clean_name("String\tregnum") == "regnum"
    assert clean_name("ttv6_2_1") == "ttv6_2_1"
    assert clean_name("8") is None
    assert clean_name(None) is None


def _row(row_id: int, name: str, datatype: str, parent: int | None = None) -> dict[str, object]:
    return {
        "wsResponseId": row_id,
        "wsResponseName": name,
        "wsResponseDatatype": datatype,
        "wsResponseDetail": None,
        "parentId": parent,
    }


def test_output_tree_orphans_and_duplicates() -> None:
    fields = build_outputs(
        [
            _row(1, "listData", "List<T>"),
            _row(2, "year", "int", parent=1),
            _row(3, "orphan", "String", parent=999),
            _row(4, "year", "String", parent=1),
            _row(5, "8", "String"),
        ]
    )

    assert [field.wire_name for field in fields] == ["listData", "orphan"]
    assert fields[0].type == TypeRef("list", TypeRef("object"))
    assert [(child.wire_name, child.type.kind) for child in fields[0].children] == [("year", "int")]


def test_self_referencing_parent_does_not_recurse_forever() -> None:
    assert [field.wire_name for field in build_outputs([_row(1, "loop", "String", parent=1)])] == [
        "loop"
    ]


def test_inputs() -> None:
    fields = build_inputs(
        [
            {"wsInputName": "regnum", "wsInputDatatype": "String", "wsInputDetail": " РД "},
            {"wsInputName": "regnum", "wsInputDatatype": "int", "wsInputDetail": None},
        ]
    )
    assert [(field.wire_name, field.type.kind, field.doc) for field in fields] == [
        ("regnum", "string", "РД")
    ]
