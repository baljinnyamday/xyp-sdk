"""Language-neutral description of the XYP API, built from spec/services.json."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

Kind = Literal[
    "string", "int", "float", "bool", "decimal", "bytes", "date", "any", "object", "list"
]


@dataclass(frozen=True)
class TypeRef:
    """A field type. `item` is set for lists; objects carry their fields on `Field.children`."""

    kind: Kind
    item: TypeRef | None = None


@dataclass(frozen=True)
class Field:
    wire_name: str
    doc: str
    type: TypeRef
    children: tuple[Field, ...] = ()


@dataclass(frozen=True)
class Service:
    operation: str  # e.g. "WS100101_getCitizenIDCardInfo"
    doc: str
    organization: str
    endpoint: str  # e.g. "citizen-1.5.0"
    group: str  # e.g. "citizen"
    inputs: tuple[Field, ...]
    outputs: tuple[Field, ...]
    accepts_auth: bool = True  # False only when a WSDL shows the request has no <auth>


@dataclass(frozen=True)
class Api:
    services: tuple[Service, ...]
    namespaces: dict[str, str]  # endpoint -> verified XML namespace (from WSDLs on disk)

    @property
    def groups(self) -> dict[str, tuple[Service, ...]]:
        names = sorted({service.group for service in self.services})
        return {
            name: tuple(service for service in self.services if service.group == name)
            for name in names
        }
