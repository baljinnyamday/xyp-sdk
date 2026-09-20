from __future__ import annotations

import ssl
from xml.etree.ElementTree import fromstring

import httpx
import pytest
import respx
from conftest import TEST_TOKEN, soap_response

from xyp import (
    AccessDeniedError,
    AsyncXyp,
    CitizenAuth,
    NotFoundError,
    Xyp,
    XypApiError,
    XypConfigError,
    XypConnectionError,
    XypModelMismatchWarning,
    XypResponseError,
    XypTimeoutError,
)
from xyp._tls import build_verify, bundled_ca_pem
from xyp.errors import api_error

CITIZEN_URL = "https://xyp.gov.mn/citizen-1.5.0/ws"
ID_CARD = "<firstname>Бат</firstname><regnum>РД00000000</regnum>"


@respx.mock
def test_typed_call_end_to_end(private_key_pem: bytes) -> None:
    route = respx.post(CITIZEN_URL).respond(content=soap_response(ID_CARD))

    with Xyp(access_token=TEST_TOKEN, private_key=private_key_pem) as xyp:
        card = xyp.citizen.get_citizen_id_card_info(
            regnum="РД00000000", auth=CitizenAuth.with_otp(regnum="РД00000000", otp=1234)
        )

    assert card.firstname == "Бат"
    request = route.calls.last.request
    assert request.headers["accessToken"] == TEST_TOKEN
    assert request.headers["timeStamp"].isdigit()
    assert request.headers["signature"]
    assert request.headers["content-type"] == "text/xml; charset=utf-8"
    body = fromstring(request.content)
    assert body.find(".//{http://citizen.xyp.gov.mn/}WS100101_getCitizenIDCardInfo") is not None
    assert body.findtext(".//request/regnum") == "РД00000000"
    assert body.findtext(".//auth/citizen/otp") == "1234"


@respx.mock
async def test_async_typed_call(private_key_pem: bytes) -> None:
    respx.post(CITIZEN_URL).respond(content=soap_response(ID_CARD))

    async with AsyncXyp(access_token=TEST_TOKEN, private_key=private_key_pem) as xyp:
        card = await xyp.citizen.get_citizen_id_card_info(regnum="РД00000000")

    assert card.regnum == "РД00000000"


@respx.mock
def test_call_by_original_name_returns_raw_data(private_key_pem: bytes) -> None:
    respx.post(CITIZEN_URL).respond(content=soap_response(ID_CARD))
    xyp = Xyp(access_token=TEST_TOKEN, private_key=private_key_pem)

    data = xyp.call("WS100101_getCitizenIDCardInfo", {"regnum": "РД00000000"})

    assert data == {"firstname": "Бат", "regnum": "РД00000000"}
    with pytest.raises(XypConfigError, match="Unknown operation"):
        xyp.call("WS999999_doesNotExist")


@respx.mock
def test_unverified_namespace_is_read_from_the_wsdl_once(private_key_pem: bytes) -> None:
    url = "https://xyp.gov.mn/insurance-1.5.0/ws"
    wsdl = respx.get(f"{url}?WSDL").respond(
        content=b'<wsdl:definitions targetNamespace="http://insurance.example/">'
    )
    post = respx.post(url).respond(content=soap_response("<isPensioner>true</isPensioner>"))
    xyp = Xyp(access_token=TEST_TOKEN, private_key=private_key_pem)

    first = xyp.insurance.get_citizen_pension_inquiry(regnum="РД00000000", start_year=2020)
    xyp.insurance.get_citizen_pension_inquiry(regnum="РД00000000")

    assert first.is_pensioner is True
    assert wsdl.call_count == 1
    assert b'xmlns:tns="http://insurance.example/"' in post.calls.last.request.content


@respx.mock
def test_result_codes_become_exceptions(private_key_pem: bytes) -> None:
    respx.post(CITIZEN_URL).respond(content=soap_response("", code=1, message="олдсонгүй"))
    xyp = Xyp(access_token=TEST_TOKEN, private_key=private_key_pem)

    with pytest.raises(NotFoundError) as raised:
        xyp.citizen.get_citizen_id_card_info(regnum="РД00000000")

    assert raised.value.result_code == 1
    assert raised.value.message == "олдсонгүй"
    assert raised.value.request_id == "4fd9aa5f-1984-4b61-b379-13c1bcbd29c7"


def test_every_documented_code_maps_to_a_subclass() -> None:
    documented = [1, 2, 3, 200, 201, 202, 203, 301, 302, 303, 304, 401, 402, 501]
    documented += [601, 602, 603, 604, 605, 801, 802]
    for code in documented:
        assert type(api_error(code, "m", None)) is not XypApiError, code
    assert isinstance(api_error(203, "m", None), AccessDeniedError)
    assert type(api_error(9999, "m", None)) is XypApiError


@respx.mock
def test_transport_failures_are_translated(private_key_pem: bytes) -> None:
    xyp = Xyp(access_token=TEST_TOKEN, private_key=private_key_pem)

    respx.post(CITIZEN_URL).mock(side_effect=httpx.ConnectError("no route"))
    with pytest.raises(XypConnectionError, match="VPN"):
        xyp.citizen.get_citizen_id_card_info(regnum="x")

    respx.post(CITIZEN_URL).mock(side_effect=httpx.ReadTimeout("slow"))
    with pytest.raises(XypTimeoutError):
        xyp.citizen.get_citizen_id_card_info(regnum="x")

    respx.post(CITIZEN_URL).respond(status_code=502, content=b"<html>Bad Gateway</html>")
    with pytest.raises(XypResponseError) as raised:
        xyp.citizen.get_citizen_id_card_info(regnum="x")
    assert raised.value.status_code == 502


@respx.mock
def test_base_url_and_injected_client(private_key_pem: bytes) -> None:
    route = respx.post("https://proxy.example/citizen-1.5.0/ws").respond(
        content=soap_response(ID_CARD)
    )
    own_client = httpx.Client()
    with Xyp(
        access_token=TEST_TOKEN,
        private_key=private_key_pem,
        base_url="https://proxy.example/",
        http_client=own_client,
    ) as xyp:
        xyp.citizen.get_citizen_id_card_info(regnum="x")

    assert route.called
    assert not own_client.is_closed  # the caller owns an injected client
    own_client.close()


def test_credentials_from_environment(
    monkeypatch: pytest.MonkeyPatch, tmp_path, private_key_pem: bytes
) -> None:
    key_file = tmp_path / "private.key"
    key_file.write_bytes(private_key_pem)
    monkeypatch.setenv("XYP_ACCESS_TOKEN", TEST_TOKEN)
    monkeypatch.setenv("XYP_PRIVATE_KEY", str(key_file))
    assert TEST_TOKEN not in repr(Xyp())

    monkeypatch.delenv("XYP_ACCESS_TOKEN")
    with pytest.raises(XypConfigError, match="XYP_ACCESS_TOKEN"):
        Xyp()


def test_tls_defaults_to_the_national_cas_only() -> None:
    context = build_verify(True)

    assert isinstance(context, ssl.SSLContext)
    assert context.verify_mode == ssl.CERT_REQUIRED
    assert context.check_hostname is True
    subjects = [
        dict(pair[0] for pair in ca["subject"])["commonName"] for ca in context.get_ca_certs()
    ]
    assert sorted(subjects) == ["Mongolian National Issuing CA", "Mongolian National Root CA"]
    assert bundled_ca_pem().count("BEGIN CERTIFICATE") == 2
    assert build_verify(False) is False


@respx.mock
def test_mismatch_warning_points_at_the_callers_line(private_key_pem: bytes) -> None:
    body = "<firstname>Бат</firstname><birthDate><unexpected>x</unexpected></birthDate>"
    respx.post(CITIZEN_URL).respond(content=soap_response(body))
    xyp = Xyp(access_token=TEST_TOKEN, private_key=private_key_pem)

    with pytest.warns(XypModelMismatchWarning) as caught:
        card = xyp.citizen.get_citizen_id_card_info(regnum="РД00000000")

    assert card.firstname == "Бат"
    assert card.birth_date is None
    assert card.xyp_mismatches[0].path == "birthDate"
    assert caught[0].filename == __file__
