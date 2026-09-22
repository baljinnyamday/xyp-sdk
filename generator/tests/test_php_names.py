from __future__ import annotations

from pathlib import Path
from typing import Literal

import pytest

from xyp_generator.ir import Api, Field, Kind, Service, TypeRef
from xyp_generator.php import emit as php_emit
from xyp_generator.php.emit import (
    HEADER,
    PREAMBLE,
    GroupView,
    _group_view,
    _require_unique_paths,
    docblock,
    php_string,
)
from xyp_generator.php.names import (
    class_part,
    group_namespace,
    group_property,
    method_name,
    property_name,
)

STRING = TypeRef("string")


def _field(name: str, kind: Kind | Literal["objects"] = "string", *children: Field) -> Field:
    """`objects` is a list of objects, the shape XYP gives every nested record."""
    if kind == "objects":
        return Field(name, "", TypeRef("list", TypeRef("object")), children)
    return Field(name, "", TypeRef(kind), children)


def _service(
    operation: str,
    inputs: tuple[Field, ...] = (),
    outputs: tuple[Field, ...] = (_field("regnum"),),
    group: str = "citizen",
) -> Service:
    return Service(
        operation=operation,
        doc="Sample",
        organization="Org",
        endpoint=f"{group}-1.5.0",
        group=group,
        inputs=inputs,
        outputs=outputs,
    )


def _group(*services: Service) -> GroupView:
    return _group_view(services[0].group, services)


@pytest.mark.parametrize(
    ("wire_name", "expected"),
    [
        ("created_date", "CreatedDate"),
        ("civilId", "CivilId"),  # XYP's own spelling, as in Go
        ("listAddress", "ListAddress"),
        ("ttv6_2_1", "Ttv621"),
        ("WSName", "WSName"),
        ("_private", "Private"),
        ("first-name", "FirstName"),
    ],
)
def test_nested_class_parts_follow_the_go_struct_names(wire_name: str, expected: str) -> None:
    assert class_part(wire_name) == expected


def test_group_names() -> None:
    assert group_property("labor_welfare") == "laborWelfare"  # as in TypeScript
    assert group_namespace("labor_welfare") == "LaborWelfare"
    assert group_property("citizen") == "citizen"


@pytest.mark.parametrize(
    ("wire_name", "taken", "expected"),
    [
        ("regnum", frozenset[str](), "regnum"),
        ("civil__id", frozenset[str](), "civil__id"),  # a valid name is never rewritten
        ("class", frozenset[str](), "class"),  # keywords are fine as property names
        ("this", frozenset[str](), "this_"),  # $this is not
        ("xyp", frozenset({"xyp"}), "xyp_"),
        ("first-name", frozenset[str](), "first_name"),
        ("2nd", frozenset[str](), "_2nd"),
    ],
)
def test_property_names(wire_name: str, taken: frozenset[str], expected: str) -> None:
    assert property_name(wire_name, taken) == expected


@pytest.mark.parametrize("short", ["__construct", "get-info", "9lives"])
def test_unusable_method_names_stop_the_generator(short: str) -> None:
    with pytest.raises(ValueError, match="not a usable PHP method name"):
        method_name(short)


def test_method_name_is_the_short_name_as_is() -> None:
    group = _group(_service("WS100101_getCitizenIDCardInfo"))
    (operation,) = group.operations
    assert operation.method == "getCitizenIDCardInfo"
    assert operation.class_names == (
        "GetCitizenIDCardInfoParams",
        "GetCitizenIDCardInfoResponse",
    )
    assert group.service == "CitizenService"


def test_methods_differing_only_in_case_are_qualified() -> None:
    """PHP method and class names are case-insensitive: getInfo and GetInfo are one."""
    group = _group(_service("WS100001_getInfo"), _service("WS100002_GetInfo"))
    first, second = group.operations
    assert first.method == "getInfo"
    assert second.method == "GetInfoWS100002"
    assert second.class_names == ("GetInfoWS100002Params", "GetInfoWS100002Response")


def test_colliding_classes_are_qualified_but_the_method_is_kept() -> None:
    # getInfo's `listResponse` records are GetInfoListResponse, which getInfoList needs too.
    info = _service("WS100001_getInfo", outputs=(_field("listResponse", "objects", _field("a")),))
    group = _group(info, _service("WS100002_getInfoList"))
    assert group.operations[0].class_names == (
        "GetInfoListResponse",
        "GetInfoParams",
        "GetInfoResponse",
    )
    assert group.operations[1].method == "getInfoList"
    assert group.operations[1].class_names == (
        "GetInfoListWS100002Params",
        "GetInfoListWS100002Response",
    )


def test_nested_classes_are_named_like_go_structs() -> None:
    address = _field("address", "objects", _field("street"), _field("geo", "objects", _field("x")))
    group = _group(_service("WS100001_getInfo", outputs=(address,)))
    views = {view.name: view for view in group.operations[0].classes}
    assert list(views) == ["GetInfoAddressGeo", "GetInfoAddress", "GetInfoResponse"]
    (listed,) = views["GetInfoResponse"].properties[:1]
    assert listed.attributes == ("ListOf(GetInfoAddress::class)",)
    assert listed.doc_type == "list<GetInfoAddress>"
    assert views["GetInfoAddressGeo"].doc == ("`address.geo` in the response of WS100001_getInfo.",)


def test_nested_classes_differing_only_in_case_stop_the_generator() -> None:
    outputs = (
        _field("civilId", "objects", _field("a")),
        _field("civilID", "objects", _field("b")),
    )
    with pytest.raises(ValueError, match=r"case-insensitively.*GetInfoCivilID"):
        _group(_service("WS100001_getInfo", outputs=outputs))


def test_a_nested_class_clashing_with_the_response_stops_the_generator() -> None:
    outputs = (_field("response", "objects", _field("a")),)
    with pytest.raises(ValueError, match="classes twice"):
        _group(_service("WS100001_getInfo", outputs=outputs))


def test_a_class_clashing_with_the_group_service_class_is_qualified() -> None:
    # Operation 'citizen' with field 'service' would declare Citizen\CitizenService.
    outputs = (_field("service", "objects", _field("a")),)
    (operation,) = _group(_service("WS100001_citizen", outputs=outputs)).operations
    assert operation.method == "citizen"
    assert "CitizenWS100001Service" in operation.class_names


def test_wire_is_used_only_when_the_wire_name_cannot_be_the_property_name() -> None:
    outputs = (
        _field("regnum"),
        _field("this"),
        _field("xyp"),
        _field("nested", "objects", _field("xyp")),
    )
    group = _group(_service("WS100001_getInfo", inputs=(_field("xyp"),), outputs=outputs))
    operation = group.operations[0]
    nested, response = operation.classes
    properties = {item.wire_name: item for item in response.properties}
    assert properties["regnum"].attributes == ()
    assert (properties["this"].name, properties["this"].attributes) == ("this_", ("Wire('this')",))
    assert (properties["xyp"].name, properties["xyp"].attributes) == ("xyp_", ("Wire('xyp')",))
    assert response.properties[-1].name == "xyp"  # the Extras
    assert "Xyp\\Attribute\\Wire" in response.imports
    # `xyp` is only taken on the response itself.
    assert nested.properties[0].attributes == ()
    assert operation.params.properties[0].attributes == ()
    assert operation.params.imports == ()


def test_properties_colliding_after_renaming_stop_the_generator() -> None:
    outputs = (_field("this"), _field("this_"))
    with pytest.raises(ValueError, match=r"properties twice.*this_"):
        _group(_service("WS100001_getInfo", outputs=outputs))


def test_property_names_are_case_sensitive() -> None:
    group = _group(_service("WS100001_getInfo", outputs=(_field("civilId"), _field("civilID"))))
    names = [item.name for item in group.operations[0].classes[-1].properties]
    assert names == ["civilId", "civilID", "xyp"]


def test_types_attributes_and_imports() -> None:
    outputs = (
        _field("amount", "decimal"),
        _field("photo", "bytes"),
        _field("born", "date"),
        _field("count", "int"),
        _field("extra", "any"),
        Field("tags", "", TypeRef("list", STRING)),
        Field("days", "", TypeRef("list", TypeRef("date"))),
    )
    inputs = (
        _field("from", "date"),
        _field("filter", "object"),
        Field("ids", "", TypeRef("list", STRING)),
        Field("rows", "", TypeRef("list", TypeRef("object"))),
        _field("amount", "decimal"),
    )
    operation = _group(_service("WS100001_getInfo", inputs=inputs, outputs=outputs)).operations[0]
    response = {item.name: item for item in operation.classes[-1].properties}
    assert [(p.native_type, p.attributes) for p in response.values()] == [
        ("?string", ("Decimal",)),
        ("?string", ("Bytes",)),
        ("?Date", ()),
        ("?int", ()),
        ("mixed", ()),
        ("array", ("ListOf('string')",)),
        ("array", ("ListOf('date')",)),
        ("Extras", ()),
    ]
    assert response["days"].doc_type == "list<Date>"
    assert operation.classes[-1].imports == (
        "Xyp\\Attribute\\Bytes",
        "Xyp\\Attribute\\Decimal",
        "Xyp\\Attribute\\ListOf",
        "Xyp\\Date",
        "Xyp\\Extras",
    )
    params = {item.name: item for item in operation.params.properties}
    assert params["from"].native_type == "\\DateTimeInterface|string|null"
    assert params["filter"].doc_type == "array<string, mixed>|null"
    assert (params["ids"].native_type, params["ids"].default) == ("array", "[]")
    assert params["ids"].doc_type == "list<string>"
    assert params["rows"].doc_type == "list<array<string, mixed>>"
    assert params["amount"].attributes == ("Decimal",)
    assert operation.params.imports == ("Xyp\\Attribute\\Decimal",)


def test_files_differing_only_in_case_stop_the_generator() -> None:
    with pytest.raises(ValueError, match="files twice"):
        _require_unique_paths([Path("src/Tax/GetInfo.php"), Path("src/Tax/getinfo.php")])


def test_docblocks_stay_docblocks() -> None:
    service = Service("WS100001_getInfo", "Ends */ here\nand wraps", "", "c-1.5.0", "c", (), ())
    operation = _group(service).operations[0]
    assert operation.doc[0] == "Calls WS100001_getInfo: Ends * / here and wraps."
    assert operation.response is None
    assert docblock(("One", "", "Two"), 4) == "    /**\n     * One\n     *\n     * Two\n     */"
    assert php_string("it's a \\") == "'it\\'s a \\\\'"


def test_header_follows_the_strict_types_declaration() -> None:
    expected = f"<?php\n\ndeclare(strict_types=1);\n\n{HEADER}\n"
    assert expected == PREAMBLE
    assert HEADER.endswith(" DO NOT EDIT.")


def _emit(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, api: Api) -> list[Path]:
    monkeypatch.setattr(php_emit, "_format", lambda *_: None)
    return php_emit.emit(api, tmp_path)


def test_emit_replaces_stale_groups_and_keeps_hand_written_files(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = tmp_path / "src"
    stale = source / "OldGroup"
    stale.mkdir(parents=True)
    (stale / "OldGroupService.php").write_text(PREAMBLE, encoding="utf-8")
    hand_written = source / "Http" / "Transport.php"
    hand_written.parent.mkdir()
    hand_written.write_text("<?php\n", encoding="utf-8")
    (source / "Registry.php").write_text(f"{PREAMBLE}// stub\n", encoding="utf-8")

    api = Api((_service("WS100001_getInfo", outputs=(_field("this"),)),), {"citizen-1.5.0": "x"})
    written = _emit(tmp_path, monkeypatch, api)

    assert not stale.exists()
    assert hand_written.read_text(encoding="utf-8") == "<?php\n"
    assert sorted(path.relative_to(source).as_posix() for path in written) == [
        "Citizen/CitizenService.php",
        "Citizen/GetInfoParams.php",
        "Citizen/GetInfoResponse.php",
        "Registry.php",
        "ServiceGroups.php",
    ]
    response = (source / "Citizen" / "GetInfoResponse.php").read_text(encoding="utf-8")
    assert response.startswith(PREAMBLE)
    assert "        #[Wire('this')]\n        public ?string $this_ = null,\n" in response
    assert "use Xyp\\Attribute\\Wire;\nuse Xyp\\Extras;\n" in response
    params = (source / "Citizen" / "GetInfoParams.php").read_text(encoding="utf-8")
    assert "\nuse " not in params
    assert "final readonly class GetInfoParams {}\n" in params


def test_emit_never_overwrites_hand_written_files(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    api = Api((_service("WS100001_getInfo", group="http"),), {})
    (tmp_path / "src" / "Http").mkdir(parents=True)
    with pytest.raises(ValueError, match="not written by xyp-generator"):
        _emit(tmp_path, monkeypatch, api)

    (tmp_path / "src" / "Registry.php").write_text("<?php\n", encoding="utf-8")
    with pytest.raises(ValueError, match=r"Registry\.php exists"):
        _emit(tmp_path, monkeypatch, Api((_service("WS100001_getInfo"),), {}))
