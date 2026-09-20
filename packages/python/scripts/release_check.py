"""Functional check of an INSTALLED xyp package against a local fake XYP server.

    uv run --isolated --no-project --with dist/xyp-*.whl python scripts/release_check.py
    uv run --isolated --no-project --prerelease allow --with dist/xyp-*.whl \\
        python scripts/release_check.py

The second form is what `pip install --pre` does to users: it enables pre-releases of
every dependency. 0.1.0a1 broke there (httpx 1.0.dev has a different API), which the
unit tests could not see because they run against the locked, stable dependencies.
"""

from __future__ import annotations

import asyncio
import sys
import threading
import warnings
from http.server import BaseHTTPRequestHandler, HTTPServer
from importlib.metadata import version

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from xyp import AsyncXyp, CitizenAuth, NotFoundError, Xyp, XypConnectionError
from xyp._tls import build_verify

TOKEN = "SECRET-TOKEN-123"
REGNUM = "РД00000000"
XSI = 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"'
ID_CARD = f"<firstname>Бат</firstname><regnum>{REGNUM}</regnum><image>AAE=</image>"
WSDL = b'<wsdl:definitions targetNamespace="http://insurance.xyp.gov.mn/">'
UNREACHABLE = "http://127.0.0.1:1"


def soap_reply(inner: str, code: int = 0, message: str = "ok") -> bytes:
    return (
        '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>'
        '<ns2:R xmlns:ns2="http://citizen.xyp.gov.mn/"><return><requestId>req-1</requestId>'
        f'<response {XSI} xsi:type="ns2:citizenData">{inner}</response>'
        f"<resultCode>{code}</resultCode><resultMessage>{message}</resultMessage>"
        "</return></ns2:R></soap:Body></soap:Envelope>"
    ).encode()


class FakeXyp(BaseHTTPRequestHandler):
    """Answers like xyp.gov.mn; the request body decides which scenario is played."""

    requests: list[tuple[str, dict[str, str], str]] = []  # noqa: RUF012 - shared on purpose

    def log_message(self, format: str, *args: object) -> None:
        return

    def do_GET(self) -> None:
        self._send(WSDL)

    def do_POST(self) -> None:
        body = self.rfile.read(int(self.headers["Content-Length"])).decode()
        self.requests.append((self.path, dict(self.headers), body))
        if "NOTFOUND" in body:
            self._send(soap_reply("", 1, "олдсонгүй"))
        elif "MISMATCH" in body:
            self._send(soap_reply("<firstname>Бат</firstname><birthDate><x>1</x></birthDate>"))
        elif "getCitizenPensionInquiry" in body:
            self._send(soap_reply("<isPensioner>true</isPensioner>"))
        else:
            self._send(soap_reply(ID_CARD))

    def _send(self, payload: bytes) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def sync_checks(base_url: str, key: bytes) -> dict[str, bool]:
    with Xyp(access_token=TOKEN, private_key=key, base_url=base_url) as client:
        auth = CitizenAuth.with_otp(regnum=REGNUM, otp=1234)
        card = client.citizen.get_citizen_id_card_info(regnum=REGNUM, auth=auth)
        path, headers, body = FakeXyp.requests[-1]
        raw = client.call("WS100101_getCitizenIDCardInfo", {"regnum": "x"})
        pension = client.insurance.get_citizen_pension_inquiry(regnum="x", start_year=2020)
        learned = 'xmlns:tns="http://insurance.xyp.gov.mn/"' in FakeXyp.requests[-1][2]
        try:
            client.citizen.get_citizen_id_card_info(regnum="NOTFOUND")
            not_found = False
        except NotFoundError as error:
            not_found = (error.result_code, error.request_id, error.origin) == (1, "req-1", "xyp")
        with warnings.catch_warnings(record=True) as caught:
            warnings.simplefilter("always")
            soft = client.citizen.get_citizen_id_card_info(regnum="MISMATCH")
        return {
            "typed call returns a model": card.firstname == "Бат" and card.image == b"\x00\x01",
            "posts to the right endpoint": path == "/citizen-1.5.0/ws",
            "signed headers present": {"accessToken", "timeStamp", "signature"} <= set(headers),
            "auth block first, otp sent": "<request><auth><citizen>" in body
            and "<otp>1234</otp>" in body,
            "raw call by original name": raw["firstname"] == "Бат",
            "namespace learned from the WSDL": pension.is_pensioner is True and learned,
            "resultCode 1 raises NotFoundError with the request id": not_found,
            "soft validation keeps the call and the raw value": soft.firstname == "Бат"
            and soft.birth_date is None
            and soft.xyp_mismatches[0].path == "birthDate"
            and len(caught) == 1,
            "secrets hidden in repr": TOKEN not in repr(client),
        }


async def async_check(base_url: str, key: bytes) -> bool:
    async with AsyncXyp(access_token=TOKEN, private_key=key, base_url=base_url) as client:
        card = await client.citizen.get_citizen_id_card_info(regnum="x")
    return card.firstname == "Бат"


def unreachable_check(key: bytes) -> bool:
    client = Xyp(access_token=TOKEN, private_key=key, base_url=UNREACHABLE)
    try:
        client.citizen.get_citizen_id_card_info(regnum="x")
    except XypConnectionError as error:
        return error.origin == "network"
    return False


def main() -> int:
    server = HTTPServer(("127.0.0.1", 0), FakeXyp)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base_url = f"http://127.0.0.1:{server.server_port}"
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048).private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    results = {
        **sync_checks(base_url, key),
        "async client": asyncio.run(async_check(base_url, key)),
        "unreachable host raises XypConnectionError": unreachable_check(key),
        "wheel ships both national CAs": len(build_verify(True).get_ca_certs()) == 2,  # type: ignore[union-attr]
    }
    sys.stdout.write(f"xyp {version('xyp')} on Python {sys.version.split()[0]}\n")
    for name, passed in results.items():
        sys.stdout.write(f"{'PASS' if passed else 'FAIL'} {name}\n")
    sys.stdout.write(f"{sum(results.values())}/{len(results)} passed\n")
    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
