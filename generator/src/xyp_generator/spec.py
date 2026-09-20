"""Load spec/services.json (+ any WSDLs on disk) into the IR."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from xyp_generator.ir import Api, Service
from xyp_generator.naming import endpoint_of, group_of
from xyp_generator.normalize import build_inputs, build_outputs
from xyp_generator.wsdl import WsdlRequest, merge_inputs, read_requests

_TARGET_NAMESPACE = re.compile(r'targetNamespace="([^"]+)"')
_WSDL_HEAD_BYTES = 4096


def load_api(spec_dir: Path) -> Api:
    rows: list[dict[str, Any]] = json.loads(
        (spec_dir / "services.json").read_text(encoding="utf-8")
    )
    requests = _read_wsdl_requests(spec_dir / "wsdl")
    services = tuple(_to_service(row, requests.get(row["operationName"])) for row in rows)
    return Api(
        services=tuple(sorted(services, key=lambda service: service.operation)),
        namespaces=_read_namespaces(spec_dir / "wsdl"),
    )


def _to_service(row: dict[str, Any], request: WsdlRequest | None) -> Service:
    endpoint = endpoint_of(row["endpoint"])
    return Service(
        operation=row["operationName"],
        doc=(row.get("operationDetail") or "").strip(),
        organization=(row.get("orgName") or "").strip(),
        endpoint=endpoint,
        group=group_of(endpoint),
        inputs=merge_inputs(
            build_inputs(row.get("input") or []), request.fields if request else None
        ),
        outputs=build_outputs(row.get("output") or []),
        accepts_auth=request.accepts_auth if request else True,
    )


def _read_namespaces(wsdl_dir: Path) -> dict[str, str]:
    """File name is the endpoint: spec/wsdl/citizen-1.5.0.wsdl -> {'citizen-1.5.0': 'http://…/'}."""
    namespaces: dict[str, str] = {}
    for path in sorted(wsdl_dir.glob("*.wsdl")):
        head = path.read_text(encoding="utf-8")[:_WSDL_HEAD_BYTES]
        match = _TARGET_NAMESPACE.search(head)
        if match:
            namespaces = {**namespaces, path.stem: match.group(1)}
    return namespaces


def _read_wsdl_requests(wsdl_dir: Path) -> dict[str, WsdlRequest]:
    requests: dict[str, WsdlRequest] = {}
    for path in sorted(wsdl_dir.glob("*.wsdl")):
        requests = {**requests, **read_requests(path)}
    return requests
