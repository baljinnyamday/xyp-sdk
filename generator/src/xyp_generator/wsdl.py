"""Read request field types from the WSDLs checked into spec/wsdl/.

The portal catalog is hand-typed and sometimes wrong about inputs (it lists the
OTP flags as boolean; the WSDL says xs:int). Where we have the WSDL it wins.
Responses are `xs:anyType` in every WSDL, so outputs always come from the portal.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from xml.etree.ElementTree import Element, parse

from xyp_generator.ir import Field, Kind, TypeRef

_XS = "{http://www.w3.org/2001/XMLSchema}"
_AUTH_FIELD = "auth"  # modelled separately by every SDK, never a plain input
_XS_KINDS: dict[str, Kind] = {
    "string": "string",
    "int": "int",
    "integer": "int",
    "long": "int",
    "short": "int",
    "double": "float",
    "float": "float",
    "decimal": "decimal",
    "boolean": "bool",
    "dateTime": "date",
    "date": "date",
    "base64Binary": "bytes",
}


@dataclass(frozen=True)
class WsdlRequest:
    fields: tuple[Field, ...]  # in schema order, without `auth`
    accepts_auth: bool


def read_requests(wsdl_path: Path) -> dict[str, WsdlRequest]:
    """operation name -> what its <request> element accepts."""
    root = parse(wsdl_path).getroot()
    complex_types = {
        name: node for node in root.iter(f"{_XS}complexType") if (name := node.get("name"))
    }
    operations: dict[str, WsdlRequest] = {}
    for name, node in complex_types.items():
        request = next(
            (el for el in node.iter(f"{_XS}element") if el.get("name") == "request"), None
        )
        request_type = _local(request.get("type")) if request is not None else None
        if request_type in complex_types:
            fields = _fields_of(request_type, complex_types, frozenset())
            shape = WsdlRequest(
                fields=tuple(field for field in fields if field.wire_name != _AUTH_FIELD),
                accepts_auth=any(field.wire_name == _AUTH_FIELD for field in fields),
            )
            operations = {**operations, name: shape}
    return operations


def _local(qualified: str | None) -> str:
    return (qualified or "").rpartition(":")[2]


def _fields_of(
    type_name: str, complex_types: dict[str, Element], visited: frozenset[str]
) -> tuple[Field, ...]:
    node = complex_types.get(type_name)
    if node is None or type_name in visited:
        return ()
    extension = node.find(f"{_XS}complexContent/{_XS}extension")
    inherited = (
        _fields_of(_local(extension.get("base")), complex_types, visited | {type_name})
        if extension is not None
        else ()
    )
    own = tuple(
        Field(wire_name=name, doc="", type=_type_of(element))
        for element in (extension if extension is not None else node).iter(f"{_XS}element")
        if (name := element.get("name"))
    )
    return (*inherited, *own)


def _type_of(element: Element) -> TypeRef:
    kind = _XS_KINDS.get(_local(element.get("type")), "any")
    scalar = TypeRef(kind)
    return TypeRef("list", item=scalar) if element.get("maxOccurs") == "unbounded" else scalar


def merge_inputs(portal: tuple[Field, ...], wsdl: tuple[Field, ...] | None) -> tuple[Field, ...]:
    """WSDL fields (authoritative names and types) with the portal's descriptions,
    followed by portal-only fields in case the WSDL copy is older than the portal."""
    if wsdl is None:
        return portal
    docs = {field.wire_name: field.doc for field in portal}
    known = {field.wire_name for field in wsdl}
    described = tuple(
        Field(field.wire_name, docs.get(field.wire_name, ""), field.type) for field in wsdl
    )
    return (*described, *(field for field in portal if field.wire_name not in known))
