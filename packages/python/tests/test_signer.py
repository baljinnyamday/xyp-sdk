from __future__ import annotations

from base64 import b64decode
from pathlib import Path

import pytest
from conftest import TEST_TOKEN
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey

from xyp._signer import Signer
from xyp.errors import XypConfigError

FIXED_TIMESTAMP = 1_700_000_000


def test_signature_verifies_with_the_public_key(
    private_key: RSAPrivateKey, private_key_pem: bytes
) -> None:
    headers = Signer(TEST_TOKEN, private_key_pem).headers(FIXED_TIMESTAMP)

    assert headers["accessToken"] == TEST_TOKEN
    assert headers["timeStamp"] == str(FIXED_TIMESTAMP)
    private_key.public_key().verify(
        b64decode(headers["signature"]),
        f"{TEST_TOKEN}.{FIXED_TIMESTAMP}".encode(),
        padding.PKCS1v15(),
        hashes.SHA256(),
    )


def test_signature_equals_the_official_sample_algorithm(
    private_key: RSAPrivateKey, private_key_pem: bytes
) -> None:
    """The official XypSign.py hashes first and signs the digest (Prehashed). Same bytes."""
    import hashlib
    from base64 import b64encode

    from cryptography.hazmat.primitives.asymmetric import utils

    digest = hashlib.sha256(f"{TEST_TOKEN}.{FIXED_TIMESTAMP}".encode()).digest()
    reference = private_key.sign(digest, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))

    headers = Signer(TEST_TOKEN, private_key_pem).headers(FIXED_TIMESTAMP)
    assert headers["signature"] == b64encode(reference).decode()


def test_timestamp_defaults_to_now_and_is_integer_seconds(private_key_pem: bytes) -> None:
    assert Signer(TEST_TOKEN, private_key_pem).headers()["timeStamp"].isdigit()


def test_key_can_be_path_pem_text_or_der(
    tmp_path: Path, private_key: RSAPrivateKey, private_key_pem: bytes
) -> None:
    key_file = tmp_path / "private.key"
    key_file.write_bytes(private_key_pem)
    der = private_key.private_bytes(
        serialization.Encoding.DER,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    expected = Signer(TEST_TOKEN, private_key_pem).headers(FIXED_TIMESTAMP)

    for source in (key_file, str(key_file), private_key_pem.decode(), der):
        assert Signer(TEST_TOKEN, source).headers(FIXED_TIMESTAMP) == expected


def test_bad_inputs_raise_config_errors_without_leaking_the_key(private_key_pem: bytes) -> None:
    with pytest.raises(XypConfigError, match="access_token"):
        Signer("", private_key_pem)
    with pytest.raises(XypConfigError, match="Cannot read"):
        Signer(TEST_TOKEN, "/no/such/key.pem")
    with pytest.raises(XypConfigError, match="not a valid") as raised:
        Signer(
            TEST_TOKEN, b"-----BEGIN PRIVATE KEY-----\nsecret-material\n-----END PRIVATE KEY-----"
        )
    assert "secret-material" not in str(raised.value)


def test_non_rsa_key_is_rejected() -> None:
    ec_pem = ec.generate_private_key(ec.SECP256R1()).private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    with pytest.raises(XypConfigError, match="RSA"):
        Signer(TEST_TOKEN, ec_pem)


def test_repr_hides_secrets(private_key_pem: bytes) -> None:
    assert TEST_TOKEN not in repr(Signer(TEST_TOKEN, private_key_pem))
