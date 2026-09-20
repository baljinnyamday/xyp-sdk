"""SOAP 1.1 document/literal envelopes for XYP. Pure functions, no I/O.

Request shape (only the operation element is namespaced):

    <soap:Envelope><soap:Body>
      <tns:WS100101_getCitizenIDCardInfo>
        <request> <auth><citizen/><operator/></auth> ...fields... </request>
      </tns:WS100101_getCitizenIDCardInfo>
    </soap:Body></soap:Envelope>

Response shape:

    <return> <requestId/> <resultCode/> <resultMessage/> <response>...</response> </return>
"""

from __future__ import annotations

from base64 import b64encode
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any, cast
from xml.etree.ElementTree import Element, ParseError, SubElement, tostring

from defusedxml import DefusedXmlException
from defusedxml.ElementTree import fromstring

from xyp.auth import CitizenAuth, OperatorAuth
from xyp.errors import XypResponseError

_SOAP_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"
_XSI_NIL = "{http://www.w3.org/2001/XMLSchema-instance}nil"


@dataclass(frozen=True)
class ServiceResult:
    request_id: str | None
    result_code: int
    message: str
    data: Any


def build_envelope(
    operation: str,
    namespace: str,
    params: Mapping[str, Any],
    citizen: CitizenAuth | None = None,
    operator: OperatorAuth | None = None,
) -> bytes:
    envelope = Element("soap:Envelope", {"xmlns:soap": _SOAP_NAMESPACE, "xmlns:tns": namespace})
    body = SubElement(envelope, "soap:Body")
    request = SubElement(SubElement(body, f"tns:{operation}"), "request")
    # <auth> comes first: every request type extends `serviceRequest`, so that is its
    # position in the schema and where zeep (the known-working client) puts it.
    if citizen or operator:
        auth = {
            "citizen": citizen.to_wire() if citizen else None,
            "operator": operator.to_wire() if operator else None,
        }
        _append_fields(request, {"auth": auth})
    _append_fields(request, params)
    return tostring(envelope, encoding="utf-8", xml_declaration=True)


def _append_fields(parent: Element, fields: Mapping[str, Any]) -> None:
    for name, value in fields.items():
        _append_value(parent, name, value)


def _append_value(parent: Element, name: str, value: Any) -> None:
    if value is None:
        return
    if isinstance(value, Mapping):
        _append_fields(SubElement(parent, name), cast("Mapping[str, Any]", value))
        return
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        for item in cast("Sequence[Any]", value):
            _append_value(parent, name, item)
        return
    SubElement(parent, name).text = _to_text(value)


def _to_text(value: Any) -> str:
    if isinstance(value, bool):
        # "1"/"0" is valid for xs:boolean and xs:int alike; the portal calls some
        # xs:int flags "boolean", so this form is accepted whichever the server declares.
        return "1" if value else "0"
    if isinstance(value, (bytes, bytearray)):
        return b64encode(value).decode("ascii")
    if isinstance(value, datetime):
        # Same lexical form zeep (the known-working client) sends: UTC is written as "Z".
        return value.isoformat().replace("+00:00", "Z")
    if isinstance(value, date):
        return value.isoformat()
    return str(value)


def parse_response(payload: bytes) -> ServiceResult:
    try:
        root = fromstring(payload)
    except (ParseError, DefusedXmlException) as error:
        raise XypResponseError("XYP returned a response that is not valid XML") from error

    fault = _find_local(root, "Fault")
    if fault is not None:
        reason = _child_text(fault, "faultstring") or "unknown SOAP fault"
        raise XypResponseError(f"XYP returned a SOAP fault: {reason}")

    result = _find_local(root, "return")
    if result is None:
        raise XypResponseError("XYP response has no <return> element")

    code_text = _child_text(result, "resultCode")
    if code_text is None or not code_text.lstrip("-").isdigit():
        raise XypResponseError("XYP response has no numeric <resultCode>")

    response = _find_child(result, "response")
    return ServiceResult(
        request_id=_child_text(result, "requestId"),
        result_code=int(code_text),
        message=_child_text(result, "resultMessage") or "",
        data=None if response is None else _to_python(response),
    )


def _local_name(element: Element) -> str:
    return element.tag.rpartition("}")[2]


def _find_local(root: Element, name: str) -> Element | None:
    return next((element for element in root.iter() if _local_name(element) == name), None)


def _find_child(parent: Element, name: str) -> Element | None:
    return next((child for child in parent if _local_name(child) == name), None)


def _child_text(parent: Element, name: str) -> str | None:
    child = _find_child(parent, name)
    text = (child.text or "").strip() if child is not None else ""
    return text or None


def _to_python(element: Element) -> Any:
    """Leaf -> str | None, element with children -> dict, repeated tags -> list."""
    if element.get(_XSI_NIL) in ("true", "1"):
        return None
    children = list(element)
    if not children:
        return (element.text or "").strip() or None
    result: dict[str, Any] = {}
    for child in children:
        name = _local_name(child)
        value = _to_python(child)
        if name not in result:
            result = {**result, name: value}
            continue
        result = {**result, name: [*_as_list(result[name]), value]}
    return result


def _as_list(value: Any) -> list[Any]:
    return cast("list[Any]", value) if isinstance(value, list) else [value]
