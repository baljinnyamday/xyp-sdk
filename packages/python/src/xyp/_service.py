"""Base classes of the generated service groups."""

from __future__ import annotations

from xyp._transport import AsyncTransport, SyncTransport


class SyncService:
    def __init__(self, transport: SyncTransport) -> None:
        self._transport = transport


class AsyncService:
    def __init__(self, transport: AsyncTransport) -> None:
        self._transport = transport
