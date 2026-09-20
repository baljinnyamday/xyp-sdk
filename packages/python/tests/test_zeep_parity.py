"""Differential test against zeep, the SOAP library the known-working XYP clients use.

zeep builds requests straight from the WSDL. For every operation in the WSDLs we
have, the same input must produce the same XML from zeep and from this SDK:
same elements, same namespaces, same order, same text. No network involved.
"""

from __future__ import annotations

import importlib
import re
import warnings
from dataclasses import replace
from datetime import datetime, timezone
from functools import cache
from pathlib import Path
from typing import Any
from xml.etree.ElementTree import Element, fromstring

import pytest
import respx
from conftest import TEST_TOKEN, soap_response
from lxml import etree

from xyp import CitizenAuth, OperatorAuth, Xyp
from xyp._operations import NAMESPACES, OPERATIONS

with warnings.catch_warnings():
    warnings.simplefilter("ignore")
    import zeep

WSDL_DIR = Path(__file__).resolve().parents[3] / "spec" / "wsdl"
REGNUM = "РД00000000"
SAMPLES: dict[str, Any] = {
    "String": 'Бат-Эрдэнэ <&> "quoted"',
    "Int": 7,
    "Long": 7,
    "Boolean": True,
    "DateTime": datetime(2024, 1, 31, 12, 0, tzinfo=timezone.utc),
}
CITIZEN = CitizenAuth.with_otp(regnum=REGNUM, otp=123456)
OPERATOR = OperatorAuth.with_fingerprint(regnum="ОП11111111", fingerprint=b"\x00\x01scan")
WIRE_TO_PYTHON = re.compile(r'^\s+"(\w+)": (\w+),$', re.MULTILINE)


@cache
def _zeep_client(endpoint: str) -> Any:
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        return zeep.Client(str(WSDL_DIR / f"{endpoint}.wsdl"))


def _cases() -> list[tuple[str, str]]:
    return [
        (path.stem, operation)
        for path in sorted(WSDL_DIR.glob("*.wsdl"))
        for operation in sorted(_zeep_client(path.stem).service._binding._operations)
    ]


def _sample_params(endpoint: str, operation: str) -> dict[str, Any] | None:
    """Wire name -> sample value, in schema order. None when the operation takes no <request>."""
    element = _zeep_client(endpoint).get_element(f"ns0:{operation}")
    request = dict(element.type.elements).get("request")
    if request is None:
        return None
    return {
        name: SAMPLES[type(field.type).__name__]
        for name, field in request.type.elements
        if name != "auth" and type(field.type).__name__ in SAMPLES
    }


def _auth_for(endpoint: str, operation: str) -> tuple[CitizenAuth | None, OperatorAuth | None]:
    """The auth a schema can express: none for requests without <auth>, and no
    `authType` for WSDL copies that predate that field (zeep drops what a schema lacks)."""
    element = _zeep_client(endpoint).get_element(f"ns0:{operation}")
    auth = dict(dict(element.type.elements)["request"].type.elements).get("auth")
    if auth is None:
        return None, None
    entity_fields = dict(dict(auth.type.elements)["citizen"].type.elements)
    if "authType" in entity_fields:
        return CITIZEN, OPERATOR
    return replace(CITIZEN, auth_type=None), replace(OPERATOR, auth_type=None)


def _canonical(element: Element) -> tuple[str, str, tuple[Any, ...]]:
    """Prefix-independent shape of an element. xs:boolean 'true' and '1' are the same value."""
    text = (element.text or "").strip()
    text = {"true": "1", "false": "0"}.get(text, text)
    return (element.tag, text, tuple(_canonical(child) for child in element))


def _zeep_envelope(endpoint: str, operation: str, params: dict[str, Any]) -> Element:
    client = _zeep_client(endpoint)
    citizen, operator = _auth_for(endpoint, operation)
    auth = (
        {
            "auth": {
                "citizen": _not_none(citizen.to_wire()),
                "operator": _not_none(operator.to_wire()),
            }
        }
        if citizen and operator
        else {}
    )
    message = client.create_message(client.service, operation, {**params, **auth})
    return fromstring(etree.tostring(message))


def _not_none(values: dict[str, object]) -> dict[str, object]:
    return {key: value for key, value in values.items() if value is not None}


@pytest.mark.parametrize(("endpoint", "operation"), _cases())
@respx.mock
def test_request_xml_is_identical_to_zeep(
    endpoint: str, operation: str, private_key_pem: bytes
) -> None:
    params = _sample_params(endpoint, operation)
    if params is None:
        pytest.skip("operation has no <request> element")
    route = respx.post(f"https://xyp.gov.mn/{endpoint}/ws").respond(content=soap_response(""))

    with Xyp(access_token=TEST_TOKEN, private_key=private_key_pem) as xyp:
        citizen, operator = _auth_for(endpoint, operation)
        xyp.call(operation, params, auth=citizen, operator=operator, endpoint=endpoint)

    ours = fromstring(route.calls.last.request.content)
    assert _canonical(ours) == _canonical(_zeep_envelope(endpoint, operation, params))


def _typed_cases() -> list[tuple[str, str]]:
    return [case for case in _cases() if case[1] in OPERATIONS and case[0] in NAMESPACES]


@pytest.mark.parametrize(("endpoint", "operation"), _typed_cases())
@respx.mock
def test_generated_method_sends_the_same_xml_as_zeep(
    endpoint: str, operation: str, private_key_pem: bytes
) -> None:
    """Same check through the generated, typed method: proves names, order and types."""
    params = _sample_params(endpoint, operation)
    if params is None:
        pytest.skip("operation has no <request> element")
    group = endpoint.rsplit("-", 1)[0].replace("-", "_")
    with Xyp(access_token=TEST_TOKEN, private_key=private_key_pem) as xyp:
        service = getattr(xyp, group)
        method_name = next(
            name
            for name in dir(service)
            if not name.startswith("_")
            and (getattr(service, name).__doc__ or "").startswith(operation)
        )
        module = importlib.import_module(f"xyp.operations.{group}.{method_name}")
        python_names = dict(WIRE_TO_PYTHON.findall(Path(module.__file__ or "").read_text("utf-8")))
        missing = set(params) - set(python_names)
        assert not missing, f"generated method lacks WSDL inputs: {missing}"

        route = respx.post(f"https://xyp.gov.mn/{endpoint}/ws").respond(content=soap_response(""))
        kwargs = {python_names[wire]: value for wire, value in params.items()}
        citizen, operator = _auth_for(endpoint, operation)
        getattr(service, method_name)(**kwargs, auth=citizen, operator=operator)

    ours = fromstring(route.calls.last.request.content)
    assert _canonical(ours) == _canonical(_zeep_envelope(endpoint, operation, params))


def _zeep_parse(endpoint: str, operation: str, payload: bytes) -> dict[str, Any]:
    import requests
    from zeep.helpers import serialize_object

    response = requests.Response()
    response.status_code = 200
    response._content = payload
    response.headers["Content-Type"] = "text/xml; charset=utf-8"
    client = _zeep_client(endpoint)
    binding = client.service._binding
    parsed = binding.process_reply(client, binding.get(operation), response)
    return dict(serialize_object(parsed)["response"])


def test_response_is_read_the_same_way_as_zeep() -> None:
    """The WSDL types the ID card response as `citizenData`; zeep decodes it from that.
    Our model is generated from the portal catalog instead, and must agree with both."""
    from xyp._models import parse_model
    from xyp._soap import parse_response
    from xyp.models.citizen import GetCitizenIdCardInfoResponse

    wsdl_type = _zeep_client("citizen-1.5.0").get_type("ns0:citizenData")
    wsdl_fields = [name for name, _ in wsdl_type.elements]
    model_fields = {
        field.alias or name for name, field in GetCitizenIdCardInfoResponse.model_fields.items()
    }
    assert model_fields == set(wsdl_fields)

    values = {
        "birthDate": "1990-05-01T00:00:00+08:00",
        "firstname": "Бат",
        "image": "iVBORyBmYWtlIGltYWdl",
        "lastname": "Дорж",
        "regnum": REGNUM,
    }
    body = "".join(f"<{name}>{values[name]}</{name}>" for name in wsdl_fields if name in values)
    payload = soap_response(body)

    theirs = _zeep_parse("citizen-1.5.0", "WS100101_getCitizenIDCardInfo", payload)
    ours = parse_model(GetCitizenIdCardInfoResponse, parse_response(payload).data)

    assert ours.firstname == theirs["firstname"] == "Бат"
    assert ours.lastname == theirs["lastname"]
    assert ours.regnum == theirs["regnum"]
    assert ours.image == theirs["image"] == b"\x89PNG fake image"
    assert ours.birth_date == theirs["birthDate"]
    assert ours.passport_date is None and theirs["passportDate"] is None
