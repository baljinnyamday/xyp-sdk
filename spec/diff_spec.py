"""Summarise how spec/services.json changed, as Markdown for a pull request body.

Usage: uv run spec/diff_spec.py OLD.json NEW.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

MAX_LISTED = 40

Service = dict[str, Any]


def _load(path: str) -> dict[str, Service]:
    rows: list[Service] = json.loads(Path(path).read_text(encoding="utf-8"))
    return {row["operationName"]: row for row in rows}


def _fields(service: Service, key: str, name_key: str, type_key: str) -> dict[str, str]:
    return {str(row.get(name_key)): str(row.get(type_key)) for row in service.get(key) or []}


def _shape(service: Service) -> dict[str, Any]:
    """The parts of a service that end up in generated code."""
    return {
        "endpoint": service.get("endpoint"),
        "description": service.get("operationDetail"),
        "input": _fields(service, "input", "wsInputName", "wsInputDatatype"),
        "output": _fields(service, "output", "wsResponseName", "wsResponseDatatype"),
    }


def _describe_change(old: Service, new: Service) -> str:
    before, after = _shape(old), _shape(new)
    parts: list[str] = []
    if before["endpoint"] != after["endpoint"]:
        parts = [*parts, f"endpoint `{before['endpoint']}` → `{after['endpoint']}`"]
    for side in ("input", "output"):
        added = sorted(set(after[side]) - set(before[side]))
        removed = sorted(set(before[side]) - set(after[side]))
        retyped = sorted(
            name
            for name in set(before[side]) & set(after[side])
            if before[side][name] != after[side][name]
        )
        parts = [
            *parts,
            *([f"{side} added: {', '.join(added)}"] if added else []),
            *([f"{side} removed: {', '.join(removed)}"] if removed else []),
            *([f"{side} retyped: {', '.join(retyped)}"] if retyped else []),
        ]
    return "; ".join(parts) or "description only"


def _section(title: str, lines: list[str]) -> list[str]:
    if not lines:
        return []
    shown = lines[:MAX_LISTED]
    more = [f"- …and {len(lines) - MAX_LISTED} more"] if len(lines) > MAX_LISTED else []
    return [f"### {title} ({len(lines)})", *shown, *more, ""]


def summarise(old: dict[str, Service], new: dict[str, Service]) -> str:
    added = [
        f"- `{name}` — {new[name].get('operationDetail') or ''}"
        for name in sorted(new.keys() - old.keys())
    ]
    removed = [f"- `{name}`" for name in sorted(old.keys() - new.keys())]
    changed = [
        f"- `{name}`: {_describe_change(old[name], new[name])}"
        for name in sorted(old.keys() & new.keys())
        if _shape(old[name]) != _shape(new[name])
    ]
    header = f"XYP's public catalog changed: **{len(old)} → {len(new)} services**.\n"
    sections = [
        *_section("Added", added),
        *_section("Removed", removed),
        *_section("Changed", changed),
    ]
    return "\n".join([header, *sections]) if sections else ""


def main() -> None:
    old_path, new_path = sys.argv[1:3]
    sys.stdout.write(summarise(_load(old_path), _load(new_path)))


if __name__ == "__main__":
    main()
