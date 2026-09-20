"""Functional check of an INSTALLED xyp package against a local fake XYP server.

    uv run --isolated --no-project --with dist/xyp-*.whl python scripts/release_check.py
    uv run --isolated --no-project --prerelease allow --with dist/xyp-*.whl python scripts/release_check.py

The second form is what `pip install --pre` does to users: it enables pre-releases of
every dependency. 0.1.0a1 broke there (httpx 1.0.dev has a different API), which the
unit tests could not see because they run against the locked, stable dependencies.
"""

import asyncio, threading, warnings, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from importlib.metadata import version
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

import xyp
from xyp import AsyncXyp, CitizenAuth, NotFoundError, Xyp, XypConnectionError

XSI = 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"'


def reply(inner, code=0, msg="ok"):
    return (
        f'<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>'
        f'<ns2:R xmlns:ns2="http://citizen.xyp.gov.mn/"><return><requestId>req-1</requestId>'
        f'<response {XSI} xsi:type="ns2:citizenData">{inner}</response>'
        f"<resultCode>{code}</resultCode><resultMessage>{msg}</resultMessage></return></ns2:R>"
        f"</soap:Body></soap:Envelope>"
    ).encode()


seen = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        self._send(b'<wsdl:definitions targetNamespace="http://insurance.xyp.gov.mn/">')

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"])).decode()
        seen.append((self.path, dict(self.headers), body))
        if "NOTFOUND" in body:
            return self._send(reply("", 1, "олдсонгүй"))
        if "MISMATCH" in body:
            return self._send(reply("<firstname>Бат</firstname><birthDate><x>1</x></birthDate>"))
        if "getCitizenPensionInquiry" in body:
            return self._send(reply("<isPensioner>true</isPensioner>"))
        self._send(
            reply("<firstname>Бат</firstname><regnum>РД00000000</regnum><image>AAE=</image>")
        )

    def _send(self, payload):
        self.send_response(200)
        self.send_header("Content-Type", "text/xml; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


server = HTTPServer(("127.0.0.1", 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
base = f"http://127.0.0.1:{server.server_port}"
key = rsa.generate_private_key(public_exponent=65537, key_size=2048).private_bytes(
    serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
)

results = []


def check(name, ok):
    results.append(ok)
    print(("PASS" if ok else "FAIL"), name)


print(f"xyp {version('xyp')} on Python {sys.version.split()[0]}")
with Xyp(access_token="SECRET-TOKEN-123", private_key=key, base_url=base) as client:
    card = client.citizen.get_citizen_id_card_info(
        regnum="РД00000000", auth=CitizenAuth.with_otp(regnum="РД00000000", otp=1234)
    )
    path, headers, body = seen[-1]
    check("typed call returns a model", card.firstname == "Бат" and card.image == b"\x00\x01")
    check("posts to the right endpoint", path == "/citizen-1.5.0/ws")
    check(
        "signed headers present",
        all(h in headers for h in ("accessToken", "timeStamp", "signature")),
    )
    check(
        "auth block first, otp sent",
        "<request><auth><citizen>" in body and "<otp>1234</otp>" in body,
    )
    check(
        "raw call by original name",
        client.call("WS100101_getCitizenIDCardInfo", {"regnum": "x"})["firstname"] == "Бат",
    )
    pension = client.insurance.get_citizen_pension_inquiry(regnum="x", start_year=2020)
    check(
        "namespace learned from WSDL for non-citizen endpoint",
        pension.is_pensioner is True and 'xmlns:tns="http://insurance.xyp.gov.mn/"' in seen[-1][2],
    )
    try:
        client.citizen.get_citizen_id_card_info(regnum="NOTFOUND")
        check("resultCode 1 raises NotFoundError", False)
    except NotFoundError as error:
        check(
            "resultCode 1 raises NotFoundError with request id",
            error.result_code == 1 and error.request_id == "req-1" and error.origin == "xyp",
        )
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        soft = client.citizen.get_citizen_id_card_info(regnum="MISMATCH")
    check(
        "soft validation: call succeeds, bad field is None, raw kept, warning emitted",
        soft.firstname == "Бат"
        and soft.birth_date is None
        and soft.xyp_mismatches[0].path == "birthDate"
        and len(caught) == 1,
    )


async def run_async():
    async with AsyncXyp(access_token="SECRET-TOKEN-123", private_key=key, base_url=base) as client:
        return await client.citizen.get_citizen_id_card_info(regnum="x")


check("async client", asyncio.run(run_async()).firstname == "Бат")
try:
    Xyp(
        access_token="SECRET-TOKEN-123", private_key=key, base_url="http://127.0.0.1:1"
    ).citizen.get_citizen_id_card_info(regnum="x")
    check("unreachable host raises XypConnectionError", False)
except XypConnectionError as error:
    check("unreachable host raises XypConnectionError", error.origin == "network")
from xyp._tls import build_verify

check("wheel ships both national CAs", len(build_verify(True).get_ca_certs()) == 2)
check(
    "secrets hidden in repr",
    "SECRET-TOKEN-123" not in repr(Xyp(access_token="SECRET-TOKEN-123", private_key=key)),
)
print(f"{sum(results)}/{len(results)} passed")
sys.exit(0 if all(results) else 1)
