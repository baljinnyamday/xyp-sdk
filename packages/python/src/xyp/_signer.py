"""Request signing: the three HTTP headers XYP requires on every call."""

from __future__ import annotations

import os
import time
from base64 import b64encode
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.asymmetric.rsa import RSAPrivateKey

from xyp.errors import XypConfigError

PrivateKeySource = str | bytes | os.PathLike[str]

_PEM_MARKER = "-----BEGIN"


class Signer:
    """Holds the access token and the parsed RSA key; produces fresh auth headers."""

    def __init__(
        self,
        access_token: str,
        private_key: PrivateKeySource,
        password: bytes | None = None,
    ) -> None:
        if not access_token:
            raise XypConfigError("access_token must not be empty")
        self._access_token = access_token
        self._key = _load_private_key(private_key, password)

    def __repr__(self) -> str:
        return "Signer(access_token=***, private_key=***)"

    def headers(self, timestamp: int | None = None) -> dict[str, str]:
        """Headers for one request. XYP rejects stale timestamps, so never reuse them."""
        stamp = str(int(time.time()) if timestamp is None else timestamp)
        message = f"{self._access_token}.{stamp}".encode()
        signature = self._key.sign(message, padding.PKCS1v15(), hashes.SHA256())
        return {
            "accessToken": self._access_token,
            "timeStamp": stamp,
            "signature": b64encode(signature).decode("ascii"),
        }


def _read_key_bytes(source: PrivateKeySource) -> bytes:
    if isinstance(source, bytes):
        return source
    if isinstance(source, str) and _PEM_MARKER in source:
        return source.encode("ascii")
    try:
        return Path(source).read_bytes()
    except OSError as error:
        raise XypConfigError(f"Cannot read private key file: {error.strerror}") from error


def _load_private_key(source: PrivateKeySource, password: bytes | None) -> RSAPrivateKey:
    data = _read_key_bytes(source)
    is_pem = _PEM_MARKER.encode("ascii") in data
    loader = serialization.load_pem_private_key if is_pem else serialization.load_der_private_key
    try:
        key = loader(data, password=password)
    except (ValueError, TypeError):
        # Deliberately no detail from `error`: it can echo key material.
        raise XypConfigError(
            "private_key is not a valid PEM/DER private key (or the password is wrong)"
        ) from None
    if not isinstance(key, RSAPrivateKey):
        raise XypConfigError("private_key must be an RSA key")
    return key
