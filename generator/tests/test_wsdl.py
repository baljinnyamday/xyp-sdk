from __future__ import annotations

from pathlib import Path

from xyp_generator.ir import Field, TypeRef
from xyp_generator.wsdl import merge_inputs, read_requests

WSDL_DIR = Path(__file__).resolve().parents[2] / "spec" / "wsdl"


def test_inherited_request_fields_without_auth() -> None:
    request = read_requests(WSDL_DIR / "citizen-1.5.0.wsdl")["WS100101_getCitizenIDCardInfo"]

    assert request.accepts_auth
    assert {field.wire_name: field.type.kind for field in request.fields} == {
        "civilId": "string",
        "regnum": "string",
    }


def test_wsdl_corrects_the_portal_about_otp_flags() -> None:
    request = read_requests(WSDL_DIR / "meta-1.5.0.wsdl")["WS100008_registerOTPRequest"]
    kinds = {field.wire_name: field.type.kind for field in request.fields}

    assert kinds["isSms"] == "int"  # the portal says boolean
    assert kinds["jsonWSList"] == "string"


def test_merge_keeps_portal_docs_and_portal_only_fields() -> None:
    portal = (
        Field("isSms", "SMS-ээр илгээх", TypeRef("bool")),
        Field("brandNew", "added after the WSDL copy", TypeRef("string")),
    )
    wsdl = (Field("isSms", "", TypeRef("int")), Field("phoneNum", "", TypeRef("int")))

    merged = merge_inputs(portal, wsdl)

    assert [(f.wire_name, f.type.kind, f.doc) for f in merged] == [
        ("isSms", "int", "SMS-ээр илгээх"),
        ("phoneNum", "int", ""),
        ("brandNew", "string", "added after the WSDL copy"),
    ]
    assert merge_inputs(portal, None) == portal


def test_requests_that_do_not_extend_service_request_take_no_auth() -> None:
    request = read_requests(WSDL_DIR / "citizen-1.5.0.wsdl")["WS100124_insertDeceaseInfo"]

    assert not request.accepts_auth
    assert "auth" not in {field.wire_name for field in request.fields}
