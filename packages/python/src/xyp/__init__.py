"""Typed Python SDK for XYP (ХУР), Mongolia's government data exchange system.

from xyp import Xyp

xyp = Xyp(access_token="...", private_key="private.key")
card = xyp.citizen.get_citizen_id_card_info(regnum="...")
"""

from xyp._client import AsyncXyp, Xyp
from xyp._models import Mismatch, XypModel, XypModelMismatchWarning
from xyp.auth import AuthType, CitizenAuth, OperatorAuth
from xyp.errors import (
    AccessDeniedError,
    AuthRequiredError,
    CitizenDataError,
    FingerprintError,
    InternalError,
    InvalidRequestError,
    NotFoundError,
    ProviderError,
    SignatureError,
    XypApiError,
    XypConfigError,
    XypConnectionError,
    XypError,
    XypResponseError,
    XypTimeoutError,
    XypValidationError,
)

__version__ = "0.1.0a1"

__all__ = [
    "AccessDeniedError",
    "AsyncXyp",
    "AuthRequiredError",
    "AuthType",
    "CitizenAuth",
    "CitizenDataError",
    "FingerprintError",
    "InternalError",
    "InvalidRequestError",
    "Mismatch",
    "NotFoundError",
    "OperatorAuth",
    "ProviderError",
    "SignatureError",
    "Xyp",
    "XypApiError",
    "XypConfigError",
    "XypConnectionError",
    "XypError",
    "XypModel",
    "XypModelMismatchWarning",
    "XypResponseError",
    "XypTimeoutError",
    "XypValidationError",
]
