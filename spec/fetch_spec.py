"""Fetch the latest XYP service catalog from the public developer portal.

Usage: uv run spec/fetch_spec.py [version]

Writes spec/services.json, sorted by operation name so diffs stay readable.
The portal is a Next.js app: the catalog is served by a `searchServices`
server action whose id changes on every portal deploy, so we discover it
from the page's JS chunks first.
"""

import json
import re
import sys
import urllib.request
from pathlib import Path

PORTAL = "https://developer.xyp.gov.mn"
DEFAULT_VERSION = "1.5.0"
ACTION_NAME = "searchServices"
PAGE_SIZE = 20  # the portal caps larger pages
TIMEOUT_SECONDS = 60
OUTPUT = Path(__file__).parent / "services.json"

CHUNK_PATTERN = re.compile(r'/_next/static/chunks/[^"\\]+\.js')
ACTION_PATTERN = re.compile(r'createServerReference\)\("([0-9a-f]+)"[^"]*"' + ACTION_NAME + '"')


def http(url: str, headers: dict[str, str] | None = None, body: bytes | None = None) -> str:
    request = urllib.request.Request(url, data=body, headers=headers or {})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return response.read().decode("utf-8")


def find_action_id(page_url: str) -> str:
    chunk_paths = sorted(set(CHUNK_PATTERN.findall(http(page_url))))
    for path in chunk_paths:
        match = ACTION_PATTERN.search(http(PORTAL + path))
        if match:
            return match.group(1)
    raise RuntimeError(f"'{ACTION_NAME}' server action not found; the portal may have changed")


def fetch_page(page_url: str, action_id: str, version: str, page: int) -> dict:
    payload = [{"query": "", "page": page, "pageSize": PAGE_SIZE, "version": version}]
    headers = {
        "Next-Action": action_id,
        "Accept": "text/x-component",
        "Content-Type": "text/plain;charset=UTF-8",
    }
    text = http(page_url, headers, json.dumps(payload).encode("utf-8"))
    for line in text.splitlines():
        _, _, value = line.partition(":")
        if value.startswith('{"rows"'):
            return json.loads(value)
    raise RuntimeError(f"Unexpected response for page {page}: {text[:200]}")


def fetch_services(version: str) -> list[dict]:
    page_url = f"{PORTAL}/docs/{version}/services"
    action_id = find_action_id(page_url)
    first = fetch_page(page_url, action_id, version, 0)
    page_count = -(-first["total"] // PAGE_SIZE)
    rest = [fetch_page(page_url, action_id, version, page)["rows"] for page in range(1, page_count)]
    rows = [row for rows in [first["rows"], *rest] for row in rows]
    if len(rows) != first["total"]:
        raise RuntimeError(f"Expected {first['total']} services, got {len(rows)}")
    return sorted(rows, key=lambda row: row["operationName"])


def main() -> None:
    version = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_VERSION
    services = fetch_services(version)
    OUTPUT.write_text(json.dumps(services, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    sys.stdout.write(f"Wrote {len(services)} services to {OUTPUT}\n")


if __name__ == "__main__":
    main()
