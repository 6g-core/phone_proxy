from __future__ import annotations

import asyncio
from contextlib import closing
import json
from pathlib import Path
import socket
import sys


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from phone_proxy_core.config import ProxyConfig, ProxyRule
from phone_proxy_core.laptop_demo import DemoConfig, LaptopDemo
from phone_proxy_core.proxy import PhoneProxyApp


def _find_free_port() -> int:
    with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as tcp_sock:
        tcp_sock.bind(("127.0.0.1", 0))
        port = tcp_sock.getsockname()[1]
    with closing(socket.socket(socket.AF_INET, socket.SOCK_DGRAM)) as udp_sock:
        udp_sock.bind(("127.0.0.1", port))
    return port


async def _tcp_probe(port: int) -> dict[str, object]:
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    writer.write(b"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
    await writer.drain()
    response = await reader.read(-1)
    writer.close()
    await writer.wait_closed()
    body = response.split(b"\r\n\r\n", 1)[1]
    return json.loads(body.decode("utf-8"))


async def _udp_probe(port: int) -> dict[str, object]:
    loop = asyncio.get_running_loop()
    future: asyncio.Future[bytes] = loop.create_future()

    class ClientProtocol(asyncio.DatagramProtocol):
        def connection_made(self, transport: asyncio.BaseTransport) -> None:
            transport = transport  # keep local name
            cast_transport = transport
            cast_transport.sendto(b"hello over udp")

        def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
            if not future.done():
                future.set_result(data)

    transport, _ = await loop.create_datagram_endpoint(
        lambda: ClientProtocol(),
        remote_addr=("127.0.0.1", port),
    )
    try:
        payload = await asyncio.wait_for(future, timeout=3)
        return json.loads(payload.decode("utf-8"))
    finally:
        transport.close()


async def main() -> None:
    demo_port = _find_free_port()
    proxy_port = _find_free_port()

    demo = LaptopDemo(DemoConfig(host="127.0.0.1", port=demo_port))
    proxy = PhoneProxyApp(
        ProxyConfig(
            udp_session_idle_timeout_seconds=5,
            rules=[
                ProxyRule(
                    id="smoke-local",
                    name="smoke-local",
                    listen_ip="127.0.0.1",
                    listen_port=proxy_port,
                    target_ip="127.0.0.1",
                    target_port=demo_port,
                    enabled=True,
                )
            ],
        )
    )

    await demo.start()
    await proxy.start()
    try:
        tcp_payload = await _tcp_probe(proxy_port)
        udp_payload = await _udp_probe(proxy_port)
    finally:
        await proxy.stop()
        await demo.stop()

    print("TCP OK:", tcp_payload["protocol"], tcp_payload["service"])
    print("UDP OK:", udp_payload["protocol"], udp_payload["service"])


if __name__ == "__main__":
    asyncio.run(main())
