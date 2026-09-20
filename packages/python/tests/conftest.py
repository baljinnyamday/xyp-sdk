from __future__ import annotations

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey

TEST_TOKEN = "test-access-token"
_KEY_SIZE = 2048
_PUBLIC_EXPONENT = 65537


@pytest.fixture(scope="session")
def private_key() -> RSAPrivateKey:
    """A throwaway key generated for the test run. Real keys never belong in tests."""
    return rsa.generate_private_key(public_exponent=_PUBLIC_EXPONENT, key_size=_KEY_SIZE)


@pytest.fixture(scope="session")
def private_key_pem(private_key: RSAPrivateKey) -> bytes:
    return private_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )


_XSI = 'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"'


def soap_response(inner: str, code: int = 0, message: str = "амжилттай") -> bytes:
    """A response shaped like the sample on developer.xyp.gov.mn/docs/result-code."""
    return f"""<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <ns2:WS100101_getCitizenIDCardInfoResponse xmlns:ns2="http://citizen.xyp.gov.mn/">
      <return>
        <request {_XSI} xsi:type="ns2:citizenRequestData"/>
        <requestId>4fd9aa5f-1984-4b61-b379-13c1bcbd29c7</requestId>
        <response {_XSI} xsi:type="ns2:citizenData">{inner}</response>
        <resultCode>{code}</resultCode>
        <resultMessage>{message}</resultMessage>
      </return>
    </ns2:WS100101_getCitizenIDCardInfoResponse>
  </soap:Body>
</soap:Envelope>""".encode()
