"""The two entry points: `Xyp` (sync) and `AsyncXyp` (async)."""

from __future__ import annotations

import os
from collections.abc import Mapping
from types import TracebackType
from typing import Any

import httpx
from typing_extensions import Self

from xyp._groups import AsyncGroups, SyncGroups
from xyp._signer import PrivateKeySource, Signer
from xyp._tls import Verify, build_verify
from xyp._transport import DEFAULT_BASE_URL, DEFAULT_TIMEOUT_SECONDS, AsyncTransport, SyncTransport
from xyp.auth import CitizenAuth, OperatorAuth
from xyp.errors import XypConfigError

ACCESS_TOKEN_ENV = "XYP_ACCESS_TOKEN"
PRIVATE_KEY_ENV = "XYP_PRIVATE_KEY"


def _signer_from(
    access_token: str | None,
    private_key: PrivateKeySource | None,
    private_key_password: bytes | None,
) -> Signer:
    token = access_token or os.environ.get(ACCESS_TOKEN_ENV)
    key = private_key or os.environ.get(PRIVATE_KEY_ENV)
    if not token:
        raise XypConfigError(
            f"Pass access_token= or set the {ACCESS_TOKEN_ENV} environment variable"
        )
    if not key:
        raise XypConfigError(
            f"Pass private_key= (path, PEM text or bytes) or set {PRIVATE_KEY_ENV} to the key path"
        )
    return Signer(token, key, private_key_password)


class Xyp(SyncGroups):
    """Synchronous XYP client.

        xyp = Xyp(access_token="...", private_key="path/to/private.key")
        card = xyp.citizen.get_citizen_id_card_info(regnum="...")

    Services are grouped by XYP endpoint (`xyp.citizen`, `xyp.insurance`, ...).
    Reuse one client: it keeps a connection pool. Use it as a context manager or
    call `close()` when done.

    Args:
        access_token: Token issued by the National Data Center. Falls back to `XYP_ACCESS_TOKEN`.
        private_key: RSA key as a file path, PEM text or PEM/DER bytes.
            Falls back to the path in `XYP_PRIVATE_KEY`.
        private_key_password: Password of an encrypted key.
        base_url: Change only when you reach XYP through your own proxy.
        timeout: Seconds to wait for XYP.
        verify: `True` verifies against the bundled Mongolian national CAs. Pass a CA
            bundle path or an `ssl.SSLContext` for a proxy, or `False` to disable
            verification (see docs/tls.md before you do).
        http_client: Bring your own `httpx.Client`; `timeout` and `verify` are then yours
            to configure and the client is not closed by this object.
    """

    def __init__(
        self,
        *,
        access_token: str | None = None,
        private_key: PrivateKeySource | None = None,
        private_key_password: bytes | None = None,
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        verify: Verify = True,
        http_client: httpx.Client | None = None,
    ) -> None:
        signer = _signer_from(access_token, private_key, private_key_password)
        self._owns_client = http_client is None
        client = http_client or httpx.Client(timeout=timeout, verify=build_verify(verify))
        self._transport = SyncTransport(signer, base_url, client)

    def call(
        self,
        operation: str,
        params: Mapping[str, Any] | None = None,
        *,
        auth: CitizenAuth | None = None,
        operator: OperatorAuth | None = None,
        endpoint: str | None = None,
    ) -> Any:
        """Call a service by its original XYP name and get the raw response data.

            xyp.call("WS100101_getCitizenIDCardInfo", {"regnum": "..."})

        `params` uses XYP's own field names. Pass `endpoint="citizen-1.5.0"` for a
        service that is newer than this SDK version.
        """
        return self._transport.call(operation, params or {}, auth, operator, endpoint)

    def close(self) -> None:
        if self._owns_client:
            self._transport.close()

    def __enter__(self) -> Self:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()

    def __repr__(self) -> str:
        return "Xyp(access_token=***)"


class AsyncXyp(AsyncGroups):
    """Asynchronous XYP client. Same options and services as `Xyp`, awaited:

    async with AsyncXyp(access_token="...", private_key="private.key") as xyp:
        card = await xyp.citizen.get_citizen_id_card_info(regnum="...")
    """

    def __init__(
        self,
        *,
        access_token: str | None = None,
        private_key: PrivateKeySource | None = None,
        private_key_password: bytes | None = None,
        base_url: str = DEFAULT_BASE_URL,
        timeout: float = DEFAULT_TIMEOUT_SECONDS,
        verify: Verify = True,
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        signer = _signer_from(access_token, private_key, private_key_password)
        self._owns_client = http_client is None
        client = http_client or httpx.AsyncClient(timeout=timeout, verify=build_verify(verify))
        self._transport = AsyncTransport(signer, base_url, client)

    async def call(
        self,
        operation: str,
        params: Mapping[str, Any] | None = None,
        *,
        auth: CitizenAuth | None = None,
        operator: OperatorAuth | None = None,
        endpoint: str | None = None,
    ) -> Any:
        """Async twin of `Xyp.call`."""
        return await self._transport.call(operation, params or {}, auth, operator, endpoint)

    async def close(self) -> None:
        if self._owns_client:
            await self._transport.close()

    async def __aenter__(self) -> Self:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.close()

    def __repr__(self) -> str:
        return "AsyncXyp(access_token=***)"
