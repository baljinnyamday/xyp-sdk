"""Exceptions raised by the SDK.

XYP answers every request with a `resultCode`. `0` is success; everything else
is raised as an `XypApiError` subclass so callers can catch exactly what they
care about. Codes are documented at https://developer.xyp.gov.mn/docs/result-code
"""

from __future__ import annotations

from typing import Literal

Origin = Literal["config", "network", "xyp", "sdk"]


class XypError(Exception):
    """Base class for every error raised by this SDK.

    `origin` says whose side the problem is on:
    "config" (how the client was set up), "network" (VPN, DNS, TLS, timeout),
    "xyp" (XYP or the data provider answered with an error), "sdk" (a gap in this SDK).
    """

    origin: Origin = "sdk"


class XypConfigError(XypError):
    """The client was configured incorrectly (bad key, unknown operation, ...)."""

    origin: Origin = "config"


class XypConnectionError(XypError):
    """XYP could not be reached (network, VPN, DNS or TLS failure)."""

    origin: Origin = "network"


class XypTimeoutError(XypConnectionError):
    """XYP did not answer within the configured timeout."""


class XypResponseError(XypError):
    """XYP answered with something that is not a valid service response."""

    origin: Origin = "xyp"

    def __init__(self, message: str, *, status_code: int | None = None) -> None:
        super().__init__(message)
        self.status_code = status_code


class XypValidationError(XypError):
    """Rare: XYP's data could not be fitted into the model even after dropping the
    fields that do not match (normally a mismatch only produces a warning, see
    `XypModel.xyp_mismatches`). The call itself succeeded, so the parsed response
    is kept on `data` and you do not have to call again.

    Deliberately not an `XypResponseError`: code that retries failed responses
    must not repeat a call that already went through."""

    origin: Origin = "sdk"

    def __init__(self, message: str, data: object) -> None:
        super().__init__(message)
        self.data = data


class XypApiError(XypError):
    """XYP processed the request and returned a non-zero `resultCode`."""

    origin: Origin = "xyp"

    def __init__(self, result_code: int, message: str, request_id: str | None = None) -> None:
        super().__init__(f"[{result_code}] {message}")
        self.result_code = result_code
        self.message = message
        self.request_id = request_id


class NotFoundError(XypApiError):
    """1 — the data provider has no record for this request."""


class InternalError(XypApiError):
    """2 — XYP internal error."""


class InvalidRequestError(XypApiError):
    """3 — missing input, wrong endpoint, bad token/timestamp/signature header."""


class AuthRequiredError(XypApiError):
    """200-202 — the `auth` block (citizen and/or operator) is missing."""


class AccessDeniedError(XypApiError):
    """203, 501 — your access token is not allowed to call this service."""


class FingerprintError(XypApiError):
    """301-304 — fingerprint not registered, not matched, or matching failed."""


class CitizenDataError(XypApiError):
    """401-402 — the citizen must visit the registry, or is not the owner."""


class SignatureError(XypApiError):
    """601-605 — the citizen's digital signature or certificate was rejected."""


class ProviderError(XypApiError):
    """801-802 — the data provider's database is unreachable or timed out."""


RESULT_CODE_OK = 0

_ERROR_BY_CODE: dict[int, type[XypApiError]] = {
    1: NotFoundError,
    2: InternalError,
    3: InvalidRequestError,
    200: AuthRequiredError,
    201: AuthRequiredError,
    202: AuthRequiredError,
    203: AccessDeniedError,
    301: FingerprintError,
    302: FingerprintError,
    303: FingerprintError,
    304: FingerprintError,
    401: CitizenDataError,
    402: CitizenDataError,
    501: AccessDeniedError,
    601: SignatureError,
    602: SignatureError,
    603: SignatureError,
    604: SignatureError,
    605: SignatureError,
    801: ProviderError,
    802: ProviderError,
}


def api_error(result_code: int, message: str, request_id: str | None) -> XypApiError:
    """Build the most specific exception for a non-zero `resultCode`."""
    error_type = _ERROR_BY_CODE.get(result_code, XypApiError)
    return error_type(result_code, message, request_id)
