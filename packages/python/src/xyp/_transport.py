"""HTTP transports. All protocol logic lives in `_Protocol`; the sync and async
transports only differ in how they send bytes."""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any

import httpx

from xyp._operations import NAMESPACES, OPERATIONS
from xyp._signer import Signer
from xyp._soap import build_envelope, parse_response
from xyp.auth import CitizenAuth, OperatorAuth
from xyp.errors import (
    RESULT_CODE_OK,
    XypConfigError,
    XypConnectionError,
    XypResponseError,
    XypTimeoutError,
    api_error,
)

DEFAULT_BASE_URL = "https://xyp.gov.mn"
DEFAULT_TIMEOUT_SECONDS = 30.0

_SOAP_HEADERS = {"Content-Type": "text/xml; charset=utf-8", "SOAPAction": '""'}
_TARGET_NAMESPACE = re.compile(rb'targetNamespace="([^"]+)"')
_HTTP_ERROR_STATUS = 400


class _Protocol:
    """Everything about an XYP call that does not involve sending bytes."""

    def __init__(self, signer: Signer, base_url: str) -> None:
        self._signer = signer
        self._base_url = base_url.rstrip("/")
        # Seeded with namespaces verified from WSDLs; others are discovered once per endpoint.
        self._namespaces: dict[str, str] = dict(NAMESPACES)

    def endpoint_for(self, operation: str, endpoint: str | None) -> str:
        resolved = endpoint or OPERATIONS.get(operation)
        if resolved is None:
            raise XypConfigError(
                f"Unknown operation {operation!r}. Pass endpoint='<name>-<version>' "
                "to call a service this SDK version does not know about."
            )
        return resolved

    def url(self, endpoint: str) -> str:
        return f"{self._base_url}/{endpoint}/ws"

    def wsdl_url(self, endpoint: str) -> str:
        return f"{self.url(endpoint)}?WSDL"

    def known_namespace(self, endpoint: str) -> str | None:
        return self._namespaces.get(endpoint)

    def learn_namespace(self, endpoint: str, status_code: int, wsdl: bytes) -> str:
        match = _TARGET_NAMESPACE.search(wsdl)
        if status_code >= _HTTP_ERROR_STATUS or match is None:
            raise XypResponseError(
                f"Could not read the WSDL of endpoint {endpoint!r}", status_code=status_code
            )
        namespace = match.group(1).decode("utf-8")
        self._namespaces = {**self._namespaces, endpoint: namespace}
        return namespace

    def headers(self) -> dict[str, str]:
        return {**_SOAP_HEADERS, **self._signer.headers()}

    @staticmethod
    def unwrap(status_code: int, content: bytes) -> Any:
        try:
            result = parse_response(content)
        except XypResponseError as error:
            if status_code >= _HTTP_ERROR_STATUS:
                # JAX-WS sends SOAP faults with HTTP 500: keep the fault text, it is the
                # only diagnostic the caller gets, and add the status to it.
                raise XypResponseError(
                    f"XYP answered with HTTP {status_code}: {error}", status_code=status_code
                ) from error
            raise
        if result.result_code != RESULT_CODE_OK:
            raise api_error(result.result_code, result.message, result.request_id)
        return result.data


def _translate(error: httpx.HTTPError) -> XypConnectionError:
    if isinstance(error, httpx.TimeoutException):
        return XypTimeoutError("XYP did not answer in time")
    return XypConnectionError(
        f"Could not reach XYP ({type(error).__name__}). Check the VPN connection, "
        "the hosts entry for xyp.gov.mn and the TLS settings."
    )


class SyncTransport:
    def __init__(self, signer: Signer, base_url: str, client: httpx.Client) -> None:
        self._protocol = _Protocol(signer, base_url)
        self._client = client

    def call(
        self,
        operation: str,
        params: Mapping[str, Any],
        citizen: CitizenAuth | None = None,
        operator: OperatorAuth | None = None,
        endpoint: str | None = None,
    ) -> Any:
        protocol = self._protocol
        resolved = protocol.endpoint_for(operation, endpoint)
        try:
            namespace = protocol.known_namespace(resolved)
            if namespace is None:
                wsdl = self._client.get(protocol.wsdl_url(resolved))
                namespace = protocol.learn_namespace(resolved, wsdl.status_code, wsdl.content)
            response = self._client.post(
                protocol.url(resolved),
                content=build_envelope(operation, namespace, params, citizen, operator),
                headers=protocol.headers(),
            )
        except httpx.HTTPError as error:
            raise _translate(error) from error
        return protocol.unwrap(response.status_code, response.content)

    def close(self) -> None:
        self._client.close()


class AsyncTransport:
    def __init__(self, signer: Signer, base_url: str, client: httpx.AsyncClient) -> None:
        self._protocol = _Protocol(signer, base_url)
        self._client = client

    async def call(
        self,
        operation: str,
        params: Mapping[str, Any],
        citizen: CitizenAuth | None = None,
        operator: OperatorAuth | None = None,
        endpoint: str | None = None,
    ) -> Any:
        protocol = self._protocol
        resolved = protocol.endpoint_for(operation, endpoint)
        try:
            namespace = protocol.known_namespace(resolved)
            if namespace is None:
                wsdl = await self._client.get(protocol.wsdl_url(resolved))
                namespace = protocol.learn_namespace(resolved, wsdl.status_code, wsdl.content)
            response = await self._client.post(
                protocol.url(resolved),
                content=build_envelope(operation, namespace, params, citizen, operator),
                headers=protocol.headers(),
            )
        except httpx.HTTPError as error:
            raise _translate(error) from error
        return protocol.unwrap(response.status_code, response.content)

    async def close(self) -> None:
        await self._client.aclose()
