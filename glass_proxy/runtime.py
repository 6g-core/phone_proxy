from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Awaitable, Callable

from .config import DEFAULT_CONFIG_PATH, GlassProxyConfig, load_config
from .gateway import GlassProxyGateway
from .transport import UsbFrameTransport


class GlassProxyApp:
    def __init__(
        self,
        transport: UsbFrameTransport,
        config_path: str | Path | None = None,
        *,
        open_connection: Callable[[str, int], Awaitable[tuple[asyncio.StreamReader, asyncio.StreamWriter]]] = asyncio.open_connection,
        create_datagram_endpoint: Callable[
            [Callable[[], asyncio.DatagramProtocol], tuple[str, int]],
            Awaitable[tuple[asyncio.DatagramTransport, asyncio.DatagramProtocol]],
        ] | None = None,
    ) -> None:
        self.config_path = Path(config_path) if config_path is not None else DEFAULT_CONFIG_PATH
        self.config: GlassProxyConfig = load_config(self.config_path)
        self.gateway = GlassProxyGateway.from_config(
            transport,
            self.config,
            open_connection=open_connection,
            create_datagram_endpoint=create_datagram_endpoint,
        )

    async def start(self) -> None:
        await self.gateway.start()

    async def stop(self) -> None:
        await self.gateway.stop()
