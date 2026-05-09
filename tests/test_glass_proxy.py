from __future__ import annotations

import asyncio
import json
import tempfile
import unittest

from glass_proxy.config import DEFAULT_CONFIG_PATH, GlassProxyConfig, load_config
from glass_proxy.frame import FrameCodec, FrameFlag, ProxyFrame, ProxyProtocol
from glass_proxy.gateway import GlassProxyGateway
from glass_proxy.runtime import GlassProxyApp
from glass_proxy.transport import StreamUsbFrameTransport, UsbFrameTransport


class _MemoryUsbTransport(UsbFrameTransport):
    def __init__(self) -> None:
        self.inbound: asyncio.Queue[ProxyFrame | None] = asyncio.Queue()
        self.outbound: asyncio.Queue[ProxyFrame] = asyncio.Queue()
        self.closed = False

    async def read_frame(self) -> ProxyFrame | None:
        return await self.inbound.get()

    async def send_frame(self, frame: ProxyFrame) -> None:
        await self.outbound.put(frame)

    async def close(self) -> None:
        self.closed = True
        await self.inbound.put(None)

    async def push_inbound(self, frame: ProxyFrame) -> None:
        await self.inbound.put(frame)

    async def next_outbound(self, timeout: float = 3.0) -> ProxyFrame:
        return await asyncio.wait_for(self.outbound.get(), timeout=timeout)


class _FakeByteStream:
    def __init__(self) -> None:
        self._read_buffer = bytearray()
        self._read_event = asyncio.Event()
        self.written = bytearray()
        self.closed = False

    async def readexactly(self, n: int) -> bytes:
        while len(self._read_buffer) < n:
            if self.closed:
                partial = bytes(self._read_buffer)
                self._read_buffer.clear()
                raise asyncio.IncompleteReadError(partial=partial, expected=n)
            self._read_event.clear()
            await self._read_event.wait()
        data = bytes(self._read_buffer[:n])
        del self._read_buffer[:n]
        return data

    def write(self, data: bytes) -> None:
        self.written.extend(data)

    async def drain(self) -> None:
        return None

    def close(self) -> None:
        self.closed = True
        self._read_event.set()

    async def wait_closed(self) -> None:
        return None

    def feed_read_data(self, data: bytes) -> None:
        self._read_buffer.extend(data)
        self._read_event.set()


class _FakeStreamReader:
    def __init__(self) -> None:
        self._chunks: asyncio.Queue[bytes] = asyncio.Queue()

    async def read(self, n: int) -> bytes:
        return await self._chunks.get()

    async def feed_data(self, data: bytes) -> None:
        await self._chunks.put(data)

    async def feed_eof(self) -> None:
        await self._chunks.put(b"")


class _FakeStreamWriter:
    def __init__(self, reader: _FakeStreamReader) -> None:
        self.received = bytearray()
        self.closed = False
        self._reader = reader

    def write(self, data: bytes) -> None:
        self.received.extend(data)
        asyncio.create_task(self._reader.feed_data(data.upper()))

    async def drain(self) -> None:
        return None

    def close(self) -> None:
        self.closed = True
        asyncio.create_task(self._reader.feed_eof())

    async def wait_closed(self) -> None:
        return None


class _FakeDatagramTransport(asyncio.DatagramTransport):
    def __init__(self, protocol: asyncio.DatagramProtocol) -> None:
        super().__init__()
        self.protocol = protocol
        self.closed = False

    def sendto(self, data: bytes, addr=None) -> None:
        self.protocol.datagram_received(data.upper(), ("127.0.0.1", 28002))

    def close(self) -> None:
        self.closed = True
        self.protocol.connection_lost(None)


class GlassProxyCodecAndTransportTest(unittest.IsolatedAsyncioTestCase):
    async def test_frame_codec_round_trip(self) -> None:
        frame = ProxyFrame(
            tunnel_epoch=9,
            connection_id=101,
            protocol=ProxyProtocol.TCP,
            flag=FrameFlag.DATA,
            listen_port=18001,
            remote_host="101.245.78.174",
            remote_port=28001,
            payload=b"hello",
        )

        raw = FrameCodec.encode(frame)
        decoded = FrameCodec.decode(raw)

        self.assertEqual(frame, decoded)

    async def test_stream_usb_frame_transport_reads_and_writes_frames(self) -> None:
        stream = _FakeByteStream()
        transport = StreamUsbFrameTransport(stream)
        frame = ProxyFrame(
            tunnel_epoch=7,
            connection_id=201,
            protocol=ProxyProtocol.UDP,
            flag=FrameFlag.OPEN,
            listen_port=19004,
            remote_host="127.0.0.1",
            remote_port=28002,
            payload=b"",
        )

        await transport.send_frame(frame)
        self.assertEqual(FrameCodec.decode(bytes(stream.written)), frame)

        inbound = ProxyFrame(
            tunnel_epoch=7,
            connection_id=202,
            protocol=ProxyProtocol.TCP,
            flag=FrameFlag.DATA,
            listen_port=18002,
            remote_host="127.0.0.1",
            remote_port=28003,
            payload=b"pong",
        )
        stream.feed_read_data(FrameCodec.encode(inbound))
        decoded = await transport.read_frame()
        self.assertEqual(inbound, decoded)

        await transport.close()


class GlassProxyGatewayTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.transport = _MemoryUsbTransport()
        self.last_tcp_reader: _FakeStreamReader | None = None
        self.last_tcp_writer: _FakeStreamWriter | None = None

        async def fake_open_connection(host: str, port: int) -> tuple[_FakeStreamReader, _FakeStreamWriter]:
            reader = _FakeStreamReader()
            writer = _FakeStreamWriter(reader)
            self.last_tcp_reader = reader
            self.last_tcp_writer = writer
            return reader, writer

        async def fake_create_datagram_endpoint(protocol_factory, remote_addr):
            protocol = protocol_factory()
            transport = _FakeDatagramTransport(protocol)
            protocol.connection_made(transport)
            return transport, protocol

        self.gateway = GlassProxyGateway(
            self.transport,
            open_connection=fake_open_connection,
            create_datagram_endpoint=fake_create_datagram_endpoint,
        )
        await self.gateway.start()

    async def asyncTearDown(self) -> None:
        await self.gateway.stop()

    async def test_tcp_frames_create_forwarder_and_return_response(self) -> None:
        await self.transport.push_inbound(
            ProxyFrame(
                tunnel_epoch=1,
                connection_id=3001,
                protocol=ProxyProtocol.TCP,
                flag=FrameFlag.OPEN,
                listen_port=18001,
                remote_host="127.0.0.1",
                remote_port=28001,
                payload=b"",
            )
        )
        await self.transport.push_inbound(
            ProxyFrame(
                tunnel_epoch=1,
                connection_id=3001,
                protocol=ProxyProtocol.TCP,
                flag=FrameFlag.DATA,
                listen_port=18001,
                remote_host="127.0.0.1",
                remote_port=28001,
                payload=b"ping",
            )
        )

        response = await self.transport.next_outbound()
        self.assertEqual(FrameFlag.DATA, response.flag)
        self.assertEqual(b"PING", response.payload)
        self.assertEqual(b"ping", bytes(self.last_tcp_writer.received))

        await self.transport.push_inbound(
            ProxyFrame(
                tunnel_epoch=1,
                connection_id=3001,
                protocol=ProxyProtocol.TCP,
                flag=FrameFlag.CLOSE,
                listen_port=18001,
                remote_host="127.0.0.1",
                remote_port=28001,
                payload=b"",
            )
        )

    async def test_udp_frames_create_forwarder_and_return_response(self) -> None:
        await self.transport.push_inbound(
            ProxyFrame(
                tunnel_epoch=2,
                connection_id=4001,
                protocol=ProxyProtocol.UDP,
                flag=FrameFlag.OPEN,
                listen_port=19004,
                remote_host="127.0.0.1",
                remote_port=28002,
                payload=b"",
            )
        )
        await self.transport.push_inbound(
            ProxyFrame(
                tunnel_epoch=2,
                connection_id=4001,
                protocol=ProxyProtocol.UDP,
                flag=FrameFlag.DATA,
                listen_port=19004,
                remote_host="127.0.0.1",
                remote_port=28002,
                payload=b"hello",
            )
        )

        response = await self.transport.next_outbound()
        self.assertEqual(FrameFlag.DATA, response.flag)
        self.assertEqual(b"HELLO", response.payload)

        await self.transport.push_inbound(
            ProxyFrame(
                tunnel_epoch=2,
                connection_id=4001,
                protocol=ProxyProtocol.UDP,
                flag=FrameFlag.CLOSE,
                listen_port=19004,
                remote_host="127.0.0.1",
                remote_port=28002,
                payload=b"",
            )
        )

    async def test_udp_idle_timeout_sends_close(self) -> None:
        transport = _MemoryUsbTransport()

        async def fake_create_datagram_endpoint(protocol_factory, remote_addr):
            protocol = protocol_factory()
            fake_transport = _FakeDatagramTransport(protocol)
            protocol.connection_made(fake_transport)
            return fake_transport, protocol

        gateway = GlassProxyGateway(
            transport,
            create_datagram_endpoint=fake_create_datagram_endpoint,
            udp_idle_timeout_seconds=0.05,
            udp_reap_interval_seconds=0.01,
        )
        await gateway.start()
        try:
            await transport.push_inbound(
                ProxyFrame(
                    tunnel_epoch=3,
                    connection_id=5001,
                    protocol=ProxyProtocol.UDP,
                    flag=FrameFlag.OPEN,
                    listen_port=19005,
                    remote_host="127.0.0.1",
                    remote_port=28003,
                    payload=b"",
                )
            )

            close_frame = await transport.next_outbound(timeout=1.0)
            self.assertEqual(FrameFlag.CLOSE, close_frame.flag)
            self.assertEqual(5001, close_frame.connection_id)
        finally:
            await gateway.stop()

    async def test_tcp_connect_timeout_sends_error_and_close(self) -> None:
        transport = _MemoryUsbTransport()
        blocked = asyncio.Event()

        async def fake_open_connection(host: str, port: int):
            await blocked.wait()
            raise AssertionError("unreachable")

        gateway = GlassProxyGateway(
            transport,
            open_connection=fake_open_connection,
            tcp_connect_timeout_seconds=0.05,
        )
        await gateway.start()
        try:
            await transport.push_inbound(
                ProxyFrame(
                    tunnel_epoch=4,
                    connection_id=6001,
                    protocol=ProxyProtocol.TCP,
                    flag=FrameFlag.OPEN,
                    listen_port=18006,
                    remote_host="127.0.0.1",
                    remote_port=28006,
                    payload=b"",
                )
            )

            error_frame = await transport.next_outbound(timeout=1.0)
            close_frame = await transport.next_outbound(timeout=1.0)

            self.assertEqual(FrameFlag.ERROR, error_frame.flag)
            self.assertIn(b"connect failed", error_frame.payload)
            self.assertEqual(FrameFlag.CLOSE, close_frame.flag)
            self.assertEqual(6001, close_frame.connection_id)
        finally:
            await gateway.stop()


class GlassProxyConfigTest(unittest.TestCase):
    def test_load_config_reads_timeout_values(self) -> None:
        with tempfile.NamedTemporaryFile("w+", suffix=".json", delete=False) as fp:
            json.dump(
                {
                    "tcp_connect_timeout_seconds": 3.5,
                    "udp_idle_timeout_seconds": 45.0,
                    "udp_reap_interval_seconds": 5.0,
                },
                fp,
            )
            path = fp.name

        config = load_config(path)

        self.assertEqual(
            GlassProxyConfig(
                tcp_connect_timeout_seconds=3.5,
                udp_idle_timeout_seconds=45.0,
                udp_reap_interval_seconds=5.0,
            ),
            config,
        )

    def test_app_loads_default_config_on_startup(self) -> None:
        app = GlassProxyApp(_MemoryUsbTransport())

        self.assertEqual(DEFAULT_CONFIG_PATH, app.config_path)
        self.assertEqual(5.0, app.config.tcp_connect_timeout_seconds)
        self.assertEqual(60.0, app.config.udp_idle_timeout_seconds)
        self.assertEqual(15.0, app.config.udp_reap_interval_seconds)
