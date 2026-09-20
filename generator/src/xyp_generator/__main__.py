"""Usage: uv run xyp-generate  (from the generator/ directory)."""

from __future__ import annotations

import sys
from pathlib import Path

from xyp_generator.python.emit import emit as emit_python
from xyp_generator.spec import load_api

REPO_ROOT = Path(__file__).resolve().parents[3]


def main() -> None:
    api = load_api(REPO_ROOT / "spec")
    written = emit_python(api, REPO_ROOT / "packages" / "python" / "src" / "xyp")
    summary = f"{len(api.services)} services in {len(api.groups)} groups"
    sys.stdout.write(f"python: {summary} -> {len(written)} files\n")


if __name__ == "__main__":
    main()
