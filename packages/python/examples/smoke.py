"""First-contact check against the real XYP. Run it from a machine on the VPN:

    export XYP_ACCESS_TOKEN=...            # never commit these
    export XYP_PRIVATE_KEY=/path/to/private.key
    uv run python examples/smoke.py

`listAccess` needs no citizen data, so it is the safest first call. It proves
four things at once: TLS against the bundled national CAs, the request
signature, the SOAP envelope, and response parsing.
"""

from __future__ import annotations

import sys

from xyp import Xyp, XypError


def main() -> int:
    try:
        with Xyp() as xyp:
            access = xyp.meta.list_access()
    except XypError as error:
        sys.stderr.write(f"FAILED: {type(error).__name__}: {error}\n")
        return 1
    sys.stdout.write(f"OK: {access.model_dump_json(indent=2)[:2000]}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
