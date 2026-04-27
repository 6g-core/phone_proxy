from __future__ import annotations

import asyncio
from contextlib import closing
import json
import socket
import unittest

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


async def _tcp_request(port: int) -> dict[str, object]:
    reader, writer = await asyncio.open_connection("127.0.0.1", port)
    writer.write(b"GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
    await writer.drain()
    response = await reader.read(-1)
    writer.close()
    await writer.wait_closed()
    body = response.split(b"\r\n\r\n", 1)[1]
    return json.loads(body.decode("utf-8"))


async def _udp_request(port: int) -> dict[str, object]:
    loop = asyncio.get_running_loop()
    future: asyncio.Future[bytes] = loop.create_future()

    class ClientProtocol(asyncio.DatagramProtocol):
        def connection_made(self, transport: asyncio.BaseTransport) -> None:
            transport = transport
            transport.sendto(b"udp test payload")

        def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
            if not future.done():
                future.set_result(data)

    transport, _ = await loop.create_datagram_endpoint(
        lambda: ClientProtocol(),
        remote_addr=("127.0.0.1", port),
    )
    try:
        data = await asyncio.wait_for(future, timeout=3)
        return json.loads(data.decode("utf-8"))
    finally:
        transport.close()


class PhoneProxyIntegrationTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.demo_port = _find_free_port()
        self.proxy_port = _find_free_port()

        self.demo = LaptopDemo(DemoConfig(host="127.0.0.1", port=self.demo_port))
        self.proxy = PhoneProxyApp(
            ProxyConfig(
                udp_session_idle_timeout_seconds=5,
                rules=[
                    ProxyRule(
                        id="test-rule",
                        name="test-rule",
                        listen_ip="127.0.0.1",
                        listen_port=self.proxy_port,
                        target_ip="127.0.0.1",
                        target_port=self.demo_port,
                    )
                ],
            )
        )

        await self.demo.start()
        await self.proxy.start()

    async def asyncTearDown(self) -> None:
        await self.proxy.stop()
        await self.demo.stop()

    async def test_tcp_proxy_round_trip(self) -> None:
        payload = await _tcp_request(self.proxy_port)
        self.assertEqual(payload["protocol"], "tcp")
        self.assertEqual(payload["service"], "laptop-demo")
        self.assertEqual(payload["listen_address"], f"127.0.0.1:{self.demo_port}")

    async def test_udp_proxy_round_trip(self) -> None:
        payload = await _udp_request(self.proxy_port)
        self.assertEqual(payload["protocol"], "udp")
        self.assertEqual(payload["service"], "laptop-demo")
        self.assertEqual(payload["listen_address"], f"127.0.0.1:{self.demo_port}")
