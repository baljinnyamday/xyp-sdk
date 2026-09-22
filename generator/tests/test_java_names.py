from __future__ import annotations

import pytest

from xyp_generator.ir import Field, Kind, Service, TypeRef
from xyp_generator.java.emit import (
    HEADER,
    RecordView,
    _group_view,
    _params_view,
    _response_view,
)
from xyp_generator.java.names import (
    decapitalize,
    group_accessor_name,
    group_class_name,
    java_field_name,
    java_package_name,
    java_string,
    java_type_name,
    javadoc,
)


def _field(wire_name: str, kind: Kind = "string", *children: Field) -> Field:
    if kind == "list":
        return Field(wire_name, "", TypeRef("list", item=TypeRef("object")), children)
    return Field(wire_name, "", TypeRef(kind), children)


def _service(
    operation: str,
    inputs: tuple[Field, ...] = (),
    outputs: tuple[Field, ...] = (),
) -> Service:
    return Service(operation, "", "", "sample-1.5.0", "sample", inputs, outputs)


@pytest.mark.parametrize(
    ("word", "expected"),
    [
        ("EDate", "eDate"),  # a run of two before a lower-case letter keeps its last capital
        ("URLValue", "urlValue"),
        ("WS100103", "ws100103"),  # a run followed by a digit is lowered whole
        ("OrgName", "orgName"),
        ("civilId", "civilId"),
        ("ID", "id"),
        ("A", "a"),
        ("", ""),
    ],
)
def test_decapitalize(word: str, expected: str) -> None:
    assert decapitalize(word) == expected


@pytest.mark.parametrize(
    ("wire_name", "expected"),
    [
        ("created_date", "createdDate"),
        ("ttv5_1", "ttv51"),
        ("ttv6_1_1", "ttv611"),
        ("CadastNumber", "cadastNumber"),
        ("EDate", "eDate"),
        ("WS100103", "ws100103"),
        ("civilId", "civilId"),  # XYP's own spelling, not civilID
        ("_private", "private_"),
        ("false", "false_"),  # a literal
        ("record", "record_"),  # contextual keywords
        ("var", "var_"),
        ("yield", "yield_"),
        ("_", "x"),
        ("9lives", "x9lives"),
        ("hashCode", "hashCode_"),  # JLS 8.10.1 forbids these record components
        ("getClass", "getClass_"),
        ("equals", "equals_"),
        ("to", "to"),  # only a keyword inside module-info.java
    ],
)
def test_java_field_names(wire_name: str, expected: str) -> None:
    assert java_field_name(wire_name) == expected


def test_reserved_member_names_get_the_suffix() -> None:
    assert java_field_name("extras", frozenset({"extras"})) == "extras_"
    assert java_field_name("extras") == "extras"


@pytest.mark.parametrize(
    ("wire_name", "expected"),
    [("listAddress", "ListAddress"), ("ttv5_1", "Ttv51"), ("EDate", "EDate"), ("9x", "X9x")],
)
def test_java_type_names(wire_name: str, expected: str) -> None:
    assert java_type_name(wire_name) == expected


def test_group_names() -> None:
    assert java_package_name("labor_welfare") == "laborwelfare"
    assert group_class_name("foreign_service") == "ForeignServiceClient"
    assert group_class_name("citizen") == "CitizenClient"
    assert group_accessor_name("labor_welfare") == "laborWelfare"
    with pytest.raises(ValueError, match="package"):
        java_package_name("default")
    with pytest.raises(ValueError, match="method"):
        group_accessor_name("default")


def test_javadoc_cannot_end_or_escape_the_comment() -> None:
    text = javadoc("a\\u002a/ b */ <b>&amp; @param x\n\t y")
    assert "\\" not in text  # javac decodes that escape even in comments
    assert "*/" not in text
    assert text == ("a&#92;u002a/ b *&#47; &lt;b&gt;&amp;amp; {@literal @}param x y")


def test_javadoc_drops_control_and_line_separator_characters() -> None:
    assert javadoc("one\u2028two\x00three") == "one two three"


def test_java_string_literals() -> None:
    assert java_string('a"b') == '"a\\"b"'
    # An even run of backslashes before `u` is not a Unicode escape (JLS 3.3).
    assert java_string("\\u0022") == '"\\\\u0022"'
    assert java_string("\x01") == '"\\u0001"'
    assert java_string("\U000e0001") == '"\\udb40\\udc01"'


def test_colliding_params_fields_stop_the_generator() -> None:
    service = _service("WS1_sample", inputs=(_field("civil_id"), _field("civilId")))
    with pytest.raises(ValueError, match="civilId"):
        _params_view(service, "Sample")


def test_params_members_are_not_field_names() -> None:
    service = _service("WS1_sample", inputs=(_field("builder"), _field("toParams")))
    names = [field.name for field in _params_view(service, "Sample").fields]
    assert names == ["builder_", "toParams_"]


def _nested(record: RecordView) -> list[str]:
    return [nested.name for nested in record.records]


def test_extras_is_reserved_on_the_root_only() -> None:
    inner = _field("inner", "list", _field("extras"))
    record = _response_view(_service("WS1_sample", outputs=(_field("extras"), inner)), "S").record
    assert [c.name for c in record.components] == ["extras_", "inner", "extras"]
    assert [c.name for c in record.records[0].components] == ["extras"]


def test_nested_record_names_avoid_shadowing_and_enclosing_types() -> None:
    outputs = (
        _field("list", "list", _field("a")),  # `List` would shadow java.util.List
        _field("listItem", "list", _field("a")),  # ListItem is taken by now
        _field("data", "list", _field("data", "list", _field("a"))),  # Data inside Data
        _field("listData", "list", _field("a")),
        _field("listdata", "list", _field("a")),  # differs only in case from a sibling
        _field("value", "object", _field("value", "object", _field("a"))),
    )
    record = _response_view(_service("WS1_sample", outputs=outputs), "Sample").record
    assert _nested(record) == [
        "ListItem",
        "ListItemItem",
        "Data",
        "ListData",
        "ListdataItem",
        "Value",
    ]
    assert _nested(record.records[2]) == ["DataItem"]
    assert _nested(record.records[5]) == ["ValueValue"]
    assert record.components[0].type == "List<ListItem>"
    assert record.components[5].type == "Value"
    assert record.components[5].decoder == 'reader.get("value", Decoders.object(Value::decode))'


def test_nested_record_named_like_the_response_is_renamed() -> None:
    outputs = (_field("sampleResponse", "list", _field("a")),)
    record = _response_view(_service("WS1_sample", outputs=outputs), "Sample").record
    assert _nested(record) == ["SampleResponseItem"]


def test_colliding_response_components_stop_the_generator() -> None:
    service = _service("WS1_sample", outputs=(_field("OrgName"), _field("orgName")))
    with pytest.raises(ValueError, match="orgName"):
        _response_view(service, "Sample")


def test_method_names_follow_the_go_rules_case_insensitively() -> None:
    services = (
        _service("WS1_getInfo", outputs=(_field("a"),)),
        _service("WS2_GetInfo", outputs=(_field("a"),)),  # same name to a reader
        _service("WS3_new"),  # a keyword
        _service("WS4_toString"),  # would override Object.toString()
        _service("WS5_QRCode", inputs=(_field("a"),)),
        _service("WS6_request", inputs=(_field("a"),)),  # RequestParams is imported
    )
    group = _group_view("sample", services)
    assert [(op.method, op.type_names) for op in group.operations] == [
        ("getInfo", ("GetInfoResponse",)),
        ("getInfoWS2", ("GetInfoWS2Response",)),
        ("newWS3", ()),
        ("toStringWS4", ()),
        ("qrCode", ("QRCodeParams",)),
        ("request", ("RequestWS6Params",)),
    ]


def test_overloads_follow_inputs_outputs_and_auth() -> None:
    both = _group_view("sample", (_service("WS1_both", (_field("a"),), (_field("b"),)),))
    first, second = both.operations[0].overloads
    assert first.parameters == "BothParams params"
    assert first.body == "both(params, CallOptions.none())"
    assert second.parameters == "BothParams params, CallOptions options"
    assert second.body == ('client.invoke("WS1_both", params, options, BothResponse::decode)')

    bare = _group_view("sample", (_service("WS1_bare"),)).operations[0]
    assert [(o.parameters, o.body) for o in bare.overloads] == [
        ("", "bare(CallOptions.none())"),
        ("CallOptions options", 'client.call("WS1_bare", null, options)'),
    ]
    assert bare.return_type == "Object"

    no_auth = Service("WS1_open", "", "", "e-1", "sample", (_field("a"),), (), False)
    (only,) = _group_view("sample", (no_auth,)).operations[0].overloads
    assert only.parameters == "OpenParams params"
    assert only.body == 'client.call("WS1_open", params, CallOptions.none())'


def test_header_matches_the_other_sdks() -> None:
    assert HEADER == "// Code generated by xyp-generator from spec/services.json. DO NOT EDIT."
