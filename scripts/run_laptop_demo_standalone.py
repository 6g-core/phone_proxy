from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import logging
import socket
from typing import cast


LOGGER = logging.getLogger("laptop_demo")


@dataclass(slots=True)
class DemoConfig:
    host: str
    port: int


class DemoUdpProtocol(asyncio.DatagramProtocol):
    def __init__(self, config: DemoConfig) -> None:
        self.config = config
        self.transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.transport = cast(asyncio.DatagramTransport, transport)
        LOGGER.info(
            "udp demo listening on %s:%s",
            self.config.host,
            self.config.port,
        )

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        payload = {
            "service": "laptop-demo",
            "protocol": "udp",
            "host": socket.gethostname(),
            "listen_address": f"{self.config.host}:{self.config.port}",
            "remote_address": f"{addr[0]}:{addr[1]}",
            "timestamp": _utc_timestamp(),
            "payload_length": len(data),
            "payload_preview": data[:64].decode("utf-8", errors="replace"),
        }
        assert self.transport is not None
        self.transport.sendto(json.dumps(payload).encode("utf-8"), addr)


class LaptopDemo:
    def __init__(self, config: DemoConfig) -> None:
        self.config = config
        self._tcp_server: asyncio.AbstractServer | None = None
        self._udp_transport: asyncio.DatagramTransport | None = None

    async def start(self) -> None:
        self._tcp_server = await asyncio.start_server(
            self._handle_http,
            host=self.config.host,
            port=self.config.port,
        )
        loop = asyncio.get_running_loop()
        udp_transport, _ = await loop.create_datagram_endpoint(
            lambda: DemoUdpProtocol(self.config),
            local_addr=(self.config.host, self.config.port),
        )
        self._udp_transport = cast(asyncio.DatagramTransport, udp_transport)
        LOGGER.info("tcp demo listening on %s:%s", self.config.host, self.config.port)

    async def stop(self) -> None:
        if self._tcp_server is not None:
            self._tcp_server.close()
            await self._tcp_server.wait_closed()
            self._tcp_server = None
        if self._udp_transport is not None:
            self._udp_transport.close()
            self._udp_transport = None

    async def _handle_http(
        self,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        peer = writer.get_extra_info("peername")
        try:
            request = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=5)
        except asyncio.IncompleteReadError as exc:
            request = exc.partial
        except (asyncio.LimitOverrunError, asyncio.TimeoutError):
            request = b""

        first_line = request.splitlines()[0].decode("utf-8", errors="replace") if request else ""
        payload = {
            "service": "laptop-demo",
            "protocol": "tcp",
            "host": socket.gethostname(),
            "listen_address": f"{self.config.host}:{self.config.port}",
            "remote_address": _format_peer(peer),
            "timestamp": _utc_timestamp(),
            "request_line": first_line,
        }
        body = json.dumps(payload, ensure_ascii=True, indent=2).encode("utf-8")
        response = (
            b"HTTP/1.1 200 OK\r\n"
            + b"Content-Type: application/json\r\n"
            + f"Content-Length: {len(body)}\r\n".encode("utf-8")
            + b"Connection: close\r\n\r\n"
            + body
        )
        writer.write(response)
        await writer.drain()
        writer.close()
        await writer.wait_closed()


def _utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


def _format_peer(peer: object) -> str:
    if isinstance(peer, tuple) and len(peer) >= 2:
        return f"{peer[0]}:{peer[1]}"
    return "unknown"


async def _run(config: DemoConfig) -> None:
    demo = LaptopDemo(config)
    await demo.start()
    stop_event = asyncio.Event()
    try:
        await stop_event.wait()
    finally:
        await demo.stop()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the laptop demo TCP+UDP service.")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", default=10001, type=int)
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    asyncio.run(_run(DemoConfig(host=args.host, port=args.port)))


if __name__ == "__main__":
    main()
