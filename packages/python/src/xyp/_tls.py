"""TLS trust for xyp.gov.mn.

XYP's certificate is issued by the Mongolian national CA, which no operating
system or Python trust store ships. Instead of turning verification off (what
most integrations do), we trust exactly the national root + issuing CA and
nothing else. See docs/tls.md in the repository.
"""

from __future__ import annotations

import os
import ssl
from importlib import resources

Verify = bool | str | os.PathLike[str] | ssl.SSLContext

_BUNDLED_CERTIFICATES = ("MNRCA-2021.pem", "MNICA-2022.pem")


def bundled_ca_pem() -> str:
    certs = resources.files("xyp") / "certs"
    return "".join((certs / name).read_text(encoding="ascii") for name in _BUNDLED_CERTIFICATES)


def build_verify(verify: Verify) -> ssl.SSLContext | bool:
    """Translate the client's `verify` option into what httpx expects.

    True (default) -> bundled Mongolian national CAs only
    path           -> your own CA bundle (PEM file)
    SSLContext     -> used as is
    False          -> no verification; only for proxies/tests, never the default
    """
    if isinstance(verify, ssl.SSLContext):
        return verify
    if verify is False:
        return False
    if verify is True:
        context = ssl.create_default_context(cadata=bundled_ca_pem())
        return context
    return ssl.create_default_context(cafile=os.fspath(verify))
