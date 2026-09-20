from __future__ import annotations

from datetime import date, datetime, timezone
from xml.etree.ElementTree import fromstring

import pytest
from conftest import soap_response

from xyp import CitizenAuth, OperatorAuth
from xyp._soap import build_envelope, parse_response
from xyp.errors import XypResponseError

NAMESPACE = "http://citizen.xyp.gov.mn/"
OPERATION = "WS100101_getCitizenIDCardInfo"
SOAP = "{http://schemas.xmlsoap.org/soap/envelope/}"


def _request(envelope: bytes):
    root = fromstring(envelope)
    operation = root.find(f"{SOAP}Body/{{{NAMESPACE}}}{OPERATION}")
    assert operation is not None, "operation element must be in the endpoint's namespace"
    request = operation.find("request")
    assert request is not None, "<request> must be unqualified"
    return request


def test_envelope_matches_the_documented_shape() -> None:
    request = _request(build_envelope(OPERATION, NAMESPACE, {"regnum": "РД00000000"}))

    assert request.findtext("regnum") == "РД00000000"
    assert request.find("auth") is None


def test_none_values_are_omitted_and_values_are_serialised() -> None:
    request = _request(
        build_envelope(
            OPERATION,
            NAMESPACE,
            {
                "skipped": None,
                "flag": True,
                "year": 2020,
                "photo": b"\x00\x01",
                "day": date(2024, 1, 31),
                "moment": datetime(2024, 1, 31, 12, 0, tzinfo=timezone.utc),
                "ids": [1, 2],
                "nested": {"code": "A", "empty": None},
            },
        )
    )

    assert request.find("skipped") is None
    assert request.findtext("flag") == "1"
    assert request.findtext("year") == "2020"
    assert request.findtext("photo") == "AAE="
    assert request.findtext("day") == "2024-01-31"
    assert request.findtext("moment") == "2024-01-31T12:00:00Z"
    assert [item.text for item in request.findall("ids")] == ["1", "2"]
    assert request.findtext("nested/code") == "A"
    assert request.find("nested/empty") is None


def test_user_input_cannot_inject_xml() -> None:
    payload = "</regnum><admin>true</admin>"
    request = _request(build_envelope(OPERATION, NAMESPACE, {"regnum": payload}))

    assert request.findtext("regnum") == payload
    assert request.find("admin") is None


def test_auth_block() -> None:
    request = _request(
        build_envelope(
            OPERATION,
            NAMESPACE,
            {"regnum": "РД00000000"},
            CitizenAuth.with_otp(regnum="РД00000000", otp=123456),
            OperatorAuth.with_fingerprint(regnum="ОП11111111", fingerprint=b"\x01"),
        )
    )

    assert request.findtext("auth/citizen/authType") == "1"
    assert request.findtext("auth/citizen/otp") == "123456"
    assert request.findtext("auth/citizen/regnum") == "РД00000000"
    assert request.find("auth/citizen/signature") is None
    assert request.findtext("auth/operator/authType") == "3"
    assert request.findtext("auth/operator/fingerprint") == "AQ=="
    assert request.findtext("auth/operator/otp") == "0"  # required by some WSDLs
    assert [child.tag for child in request] == ["auth", "regnum"]  # schema order


def test_parse_success() -> None:
    result = parse_response(
        soap_response(
            "<firstname>Бат</firstname><empty/>"
            '<gone xsi:nil="true"/>'
            "<listData><year>2020</year></listData><listData><year>2021</year></listData>"
        )
    )

    assert result.result_code == 0
    assert result.request_id == "4fd9aa5f-1984-4b61-b379-13c1bcbd29c7"
    assert result.message == "амжилттай"
    assert result.data == {
        "firstname": "Бат",
        "empty": None,
        "gone": None,
        "listData": [{"year": "2020"}, {"year": "2021"}],
    }


def test_parse_empty_response_element() -> None:
    assert parse_response(soap_response("", code=1, message="олдсонгүй")).data is None


def test_soap_fault_and_garbage_raise_response_errors() -> None:
    fault = (
        b'<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>'
        b"<soap:Fault><faultcode>soap:Client</faultcode><faultstring>Unmarshalling Error"
        b"</faultstring></soap:Fault></soap:Body></soap:Envelope>"
    )
    with pytest.raises(XypResponseError, match="Unmarshalling Error"):
        parse_response(fault)
    with pytest.raises(XypResponseError, match="not valid XML"):
        parse_response(b"<html>gateway timeout")
    with pytest.raises(XypResponseError, match="<return>"):
        parse_response(b"<a/>")


def test_entity_expansion_is_refused() -> None:
    bomb = b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><x>&a;</x>'
    with pytest.raises(XypResponseError):
        parse_response(bomb)
