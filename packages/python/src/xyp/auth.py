"""Citizen and operator authentication sent in a request's `<auth>` block.

Some services only answer when the citizen (and sometimes the operator serving
them) has approved the request. Build the approval with one of the `with_*`
constructors and pass it as `auth=` / `operator=`:

    xyp.citizen.get_citizen_id_card_info(
        regnum=regnum,
        auth=CitizenAuth.with_otp(regnum=regnum, otp=123456),
    )
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum

from typing_extensions import Self

_NO_OTP = 0


class AuthType(IntEnum):
    SMS_OTP = 1
    DIGITAL_SIGNATURE = 2
    FINGERPRINT = 3
    SSO_OTP = 4
    DAN_APP = 5


@dataclass(frozen=True)
class _AuthEntity:
    """Mirrors the WSDL's `authorizationEntity`. Prefer the `with_*` constructors."""

    regnum: str | None = None
    civil_id: str | None = None
    auth_type: AuthType | None = None
    otp: int | None = None
    fingerprint: bytes | None = None
    signature: str | None = None
    cert_fingerprint: str | None = None
    app_auth_token: str | None = None
    auth_app_name: str | None = None

    @classmethod
    def with_otp(cls, *, regnum: str, otp: int) -> Self:
        """One-time code the person received by SMS."""
        return cls(regnum=regnum, auth_type=AuthType.SMS_OTP, otp=otp)

    @classmethod
    def with_sso_otp(cls, *, regnum: str, otp: int) -> Self:
        """One-time code issued through the government SSO."""
        return cls(regnum=regnum, auth_type=AuthType.SSO_OTP, otp=otp)

    @classmethod
    def with_signature(cls, *, regnum: str, signature: str, cert_fingerprint: str) -> Self:
        """The person's own digital signature and their certificate's fingerprint."""
        return cls(
            regnum=regnum,
            auth_type=AuthType.DIGITAL_SIGNATURE,
            signature=signature,
            cert_fingerprint=cert_fingerprint,
        )

    @classmethod
    def with_fingerprint(cls, *, regnum: str, fingerprint: bytes) -> Self:
        """A freshly scanned fingerprint image."""
        return cls(regnum=regnum, auth_type=AuthType.FINGERPRINT, fingerprint=fingerprint)

    @classmethod
    def with_dan_app(cls, *, regnum: str) -> Self:
        """Approval through the ДАН mobile app."""
        return cls(regnum=regnum, auth_type=AuthType.DAN_APP)

    def to_wire(self) -> dict[str, object]:
        """Field names and order as declared by the WSDL; unset fields are omitted.

        `otp` is always sent: some XYP WSDLs declare it as a required int, and the
        official samples send 0 when there is no code."""
        return {
            "appAuthToken": self.app_auth_token,
            "authAppName": self.auth_app_name,
            "authType": None if self.auth_type is None else int(self.auth_type),
            "certFingerprint": self.cert_fingerprint,
            "civilId": self.civil_id,
            "fingerprint": self.fingerprint,
            "otp": self.otp if self.otp is not None else _NO_OTP,
            "regnum": self.regnum,
            "signature": self.signature,
        }

    def __repr__(self) -> str:
        kind = self.auth_type.name if self.auth_type else "CUSTOM"
        return f"{type(self).__name__}({kind}, regnum={self.regnum!r}, secrets=***)"


class CitizenAuth(_AuthEntity):
    """Approval from the citizen whose data is requested."""


class OperatorAuth(_AuthEntity):
    """Approval from the staff member (operator) making the request."""
