"""Export request envelopes from the Python SDK as fixtures for the other SDKs' tests.

    uv run --project ../python python scripts/make_fixtures.py

Run it from packages/typescript after `pnpm install`: the TypeScript copy is formatted
with the package's own biome, because `pnpm lint` checks it.

The Python SDK's XML is verified against zeep (the SOAP library the known-working XYP
clients use) for every operation in spec/wsdl. The TypeScript and Go SDKs must produce
the same bytes for the same input, which makes them zeep-verified too.
"""

from __future__ import annotations

import json
import re
import subprocess
from pathlib import Path
from typing import Any
from xml.etree.ElementTree import parse

from xyp import CitizenAuth, OperatorAuth
from xyp._operations import NAMESPACES
from xyp._soap import build_envelope

ROOT = Path(__file__).resolve().parents[3]
OUTPUTS = (
    ROOT / "packages" / "typescript" / "tests" / "fixtures" / "envelopes.json",
    ROOT / "packages" / "go" / "xyp" / "testdata" / "envelopes.json",
)
XS = "{http://www.w3.org/2001/XMLSchema}"
REGNUM = "РД00000000"
SAMPLES: dict[str, Any] = {
    "string": 'Бат-Эрдэнэ <&> "quoted"',
    "int": 7,
    "long": 7,
    "boolean": True,
    "dateTime": "2024-01-31T12:00:00Z",
}
AUTH = {
    "citizen": {"regnum": REGNUM, "otp": 123456, "authType": 1},
    "operator": {"regnum": "ОП11111111", "authType": 3, "fingerprintBase64": "AAFzY2Fu"},
}


def _request_fields(wsdl: Path) -> dict[str, dict[str, str]]:
    """operation -> {field: xs type}, in schema order, for fields we have samples of."""
    root = parse(wsdl).getroot()  # noqa: S314 - reviewed files from this repository
    types = {node.get("name"): node for node in root.iter(f"{XS}complexType")}

    def fields(name: str | None) -> dict[str, str]:
        node = types.get(name)
        if node is None:
            return {}
        extension = node.find(f"{XS}complexContent/{XS}extension")
        base = fields(extension.get("base", "").rpartition(":")[2]) if extension is not None else {}
        own = {
            element.get("name", ""): element.get("type", "").rpartition(":")[2]
            for element in (extension if extension is not None else node).iter(f"{XS}element")
        }
        return {**base, **own}

    operations: dict[str, dict[str, str]] = {}
    for name, node in types.items():
        request = next(
            (el for el in node.iter(f"{XS}element") if el.get("name") == "request"), None
        )
        if name and request is not None:
            operations[name] = fields(request.get("type", "").rpartition(":")[2])
    return operations


def main() -> None:
    cases = []
    for wsdl in sorted((ROOT / "spec" / "wsdl").glob("*.wsdl")):
        namespace = NAMESPACES[wsdl.stem]
        for operation, schema in sorted(_request_fields(wsdl).items()):
            if not re.match(r"^[A-Z]{2}\d+_", operation):
                continue
            accepts_auth = "auth" in schema
            params = {name: SAMPLES[kind] for name, kind in schema.items() if kind in SAMPLES}
            python_params = {
                name: _python_value(schema[name], value) for name, value in params.items()
            }
            citizen = CitizenAuth.with_otp(regnum=REGNUM, otp=123456) if accepts_auth else None
            operator = (
                OperatorAuth.with_fingerprint(regnum="ОП11111111", fingerprint=b"\x00\x01scan")
                if accepts_auth
                else None
            )
            envelope = build_envelope(operation, namespace, python_params, citizen, operator)
            cases.append(
                {
                    "operation": operation,
                    "namespace": namespace,
                    "params": params,
                    # JSON objects have no order in Go, so the schema order is kept apart.
                    "paramOrder": list(params),
                    "dateFields": [name for name in params if schema[name] == "dateTime"],
                    "auth": AUTH if accepts_auth else None,
                    "envelope": envelope.decode("utf-8"),
                }
            )
    payload = json.dumps(cases, ensure_ascii=False, indent=1) + "\n"
    for output in OUTPUTS:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(payload, encoding="utf-8")
        print(f"wrote {len(cases)} envelopes to {output}")
    # `pnpm lint` checks tests/ too, so the TypeScript copy gets the package's own formatting.
    subprocess.run(
        ["pnpm", "exec", "biome", "format", "--write", str(OUTPUTS[0])],
        cwd=OUTPUTS[0].parents[2],
        check=True,
        capture_output=True,
    )


def _python_value(kind: str, value: Any) -> Any:
    from datetime import datetime

    return datetime.fromisoformat(value.replace("Z", "+00:00")) if kind == "dateTime" else value


if __name__ == "__main__":
    main()
