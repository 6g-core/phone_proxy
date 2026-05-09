from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
import logging
import time
from typing import TYPE_CHECKING
from typing import Awaitable, Callable, cast

from .frame import FrameFlag, ProxyFrame, ProxyProtocol
from .transport import UsbFrameTransport

if TYPE_CHECKING:
    from .config import GlassProxyConfig


LOGGER = logging.getLogger("glass_proxy.gateway")
TCP_BUFFER_SIZE = 64 * 1024


@dataclass(slots=True)
class _SessionMeta:
    tunnel_epoch: int
    connection_id: int
    protocol: ProxyProtocol
    listen_port: int
    remote_host: str
    remote_port: int

    def frame(self, flag: FrameFlag, payload: bytes = b"") -> ProxyFrame:
        return ProxyFrame(
            tunnel_epoch=self.tunnel_epoch,
            connection_id=self.connection_id,
            protocol=self.protocol,
            flag=flag,
            listen_port=self.listen_port,
            remote_host=self.remote_host,
            remote_port=self.remote_port,
            payload=payload,
        )


@dataclass(slots=True)
class _TcpSession:
    meta: _SessionMeta
    reader: asyncio.StreamReader
    writer: asyncio.StreamWriter
    pump_task: asyncio.Task[None]


@dataclass(slots=True)
class _UdpSession:
    meta: _SessionMeta
    transport: asyncio.DatagramTransport
    protocol: "_UpstreamUdpProtocol"
    last_activity: float = field(default_factory=time.monotonic)

    def touch(self) -> None:
        self.last_activity = time.monotonic()


class _UpstreamUdpProtocol(asyncio.DatagramProtocol):
    def __init__(self, gateway: "GlassProxyGateway", meta: _SessionMeta) -> None:
        self.gateway = gateway
        self.meta = meta

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        self.gateway._touch_udp_session(self.meta.connection_id)
        asyncio.create_task(
            self.gateway._send_frame(self.meta.frame(FrameFlag.DATA, data))
        )

    def error_received(self, exc: Exception) -> None:
        asyncio.create_task(
            self.gateway._send_error_and_close(
                self.meta,
                f"udp upstream error: {exc}",
            )
        )

    def connection_lost(self, exc: Exception | None) -> None:
        if exc is None:
            asyncio.create_task(self.gateway._close_udp_session(self.meta.connection_id))
            return
        asyncio.create_task(
            self.gateway._send_error_and_close(
                self.meta,
                f"udp upstream closed: {exc}",
            )
        )


class GlassProxyGateway:
    def __init__(
        self,
        transport: UsbFrameTransport,
        *,
        open_connection: Callable[[str, int], Awaitable[tuple[asyncio.StreamReader, asyncio.StreamWriter]]] = asyncio.open_connection,
        create_datagram_endpoint: Callable[
            [Callable[[], asyncio.DatagramProtocol], tuple[str, int]],
            Awaitable[tuple[asyncio.DatagramTransport, asyncio.DatagramProtocol]],
        ] | None = None,
        tcp_connect_timeout_seconds: float = 5.0,
        udp_idle_timeout_seconds: float = 60.0,
        udp_reap_interval_seconds: float | None = None,
    ) -> None:
        self._transport = transport
        self._open_connection = open_connection
        self._create_datagram_endpoint = create_datagram_endpoint or self._default_create_datagram_endpoint
        self._tcp_connect_timeout_seconds = tcp_connect_timeout_seconds
        self._udp_idle_timeout_seconds = udp_idle_timeout_seconds
        self._udp_reap_interval_seconds = (
            udp_reap_interval_seconds
            if udp_reap_interval_seconds is not None
            else max(1.0, udp_idle_timeout_seconds / 2.0)
        )
        self._reader_task: asyncio.Task[None] | None = None
        self._udp_reaper_task: asyncio.Task[None] | None = None
        self._tcp_sessions: dict[int, _TcpSession] = {}
        self._udp_sessions: dict[int, _UdpSession] = {}
        self._running = False

    async def start(self) -> None:
        if self._running:
            return
        self._running = True
        self._reader_task = asyncio.create_task(self._read_loop(), name="glass-proxy-reader")
        self._udp_reaper_task = asyncio.create_task(self._udp_reap_loop(), name="glass-proxy-udp-reaper")

    async def stop(self) -> None:
        if not self._running:
            return
        self._running = False
        if self._reader_task is not None:
            self._reader_task.cancel()
            await asyncio.gather(self._reader_task, return_exceptions=True)
            self._reader_task = None
        if self._udp_reaper_task is not None:
            self._udp_reaper_task.cancel()
            await asyncio.gather(self._udp_reaper_task, return_exceptions=True)
            self._udp_reaper_task = None

        for connection_id in list(self._tcp_sessions):
            await self._close_tcp_session(connection_id)
        for connection_id in list(self._udp_sessions):
            await self._close_udp_session(connection_id)

        await self._transport.close()

    async def _read_loop(self) -> None:
        try:
            while self._running:
                frame = await self._transport.read_frame()
                if frame is None:
                    break
                await self._handle_frame(frame)
        except asyncio.CancelledError:
            raise

    async def _handle_frame(self, frame: ProxyFrame) -> None:
        if frame.flag is FrameFlag.OPEN:
            await self._handle_open(frame)
            return
        if frame.flag is FrameFlag.DATA:
            await self._handle_data(frame)
            return
        if frame.flag is FrameFlag.CLOSE:
            await self._handle_close(frame)
            return
        if frame.flag is FrameFlag.ERROR:
            LOGGER.warning(
                "peer sent error conn=%s protocol=%s payload=%r",
                frame.connection_id,
                frame.protocol.name,
                frame.payload,
            )

    async def _handle_open(self, frame: ProxyFrame) -> None:
        meta = _SessionMeta(
            tunnel_epoch=frame.tunnel_epoch,
            connection_id=frame.connection_id,
            protocol=frame.protocol,
            listen_port=frame.listen_port,
            remote_host=frame.remote_host,
            remote_port=frame.remote_port,
        )
        if frame.protocol is ProxyProtocol.TCP:
            await self._open_tcp_session(meta)
        else:
            await self._open_udp_session(meta)

    async def _handle_data(self, frame: ProxyFrame) -> None:
        if frame.protocol is ProxyProtocol.TCP:
            await self._forward_tcp_payload(frame.connection_id, frame.payload)
        else:
            await self._forward_udp_payload(frame.connection_id, frame.payload)

    async def _handle_close(self, frame: ProxyFrame) -> None:
        if frame.protocol is ProxyProtocol.TCP:
            await self._close_tcp_session(frame.connection_id)
        else:
            await self._close_udp_session(frame.connection_id)

    async def _open_tcp_session(self, meta: _SessionMeta) -> None:
        if meta.connection_id in self._tcp_sessions or meta.connection_id in self._udp_sessions:
            await self._send_error(meta, "connection id already exists")
            return
        try:
            reader, writer = await asyncio.wait_for(
                self._open_connection(meta.remote_host, meta.remote_port),
                timeout=self._tcp_connect_timeout_seconds,
            )
        except Exception as exc:
            await self._send_error(meta, f"tcp connect failed: {exc}")
            await self._send_frame(meta.frame(FrameFlag.CLOSE))
            return

        pump_task = asyncio.create_task(
            self._pump_tcp_downlink(meta, reader, writer),
            name=f"glass-tcp-{meta.connection_id}",
        )
        self._tcp_sessions[meta.connection_id] = _TcpSession(
            meta=meta,
            reader=reader,
            writer=writer,
            pump_task=pump_task,
        )

    async def _pump_tcp_downlink(
        self,
        meta: _SessionMeta,
        reader: asyncio.StreamReader,
        writer: asyncio.StreamWriter,
    ) -> None:
        try:
            while True:
                data = await reader.read(TCP_BUFFER_SIZE)
                if not data:
                    break
                await self._send_frame(meta.frame(FrameFlag.DATA, data))
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            await self._send_error(meta, f"tcp read failed: {exc}")
        finally:
            session = self._tcp_sessions.pop(meta.connection_id, None)
            if session is not None:
                writer.close()
                await writer.wait_closed()
                await self._send_frame(meta.frame(FrameFlag.CLOSE))

    async def _forward_tcp_payload(self, connection_id: int, payload: bytes) -> None:
        session = self._tcp_sessions.get(connection_id)
        if session is None:
            await self._send_error(
                _SessionMeta(0, connection_id, ProxyProtocol.TCP, 1, "unknown", 1),
                "unknown tcp connection id",
            )
            return
        try:
            session.writer.write(payload)
            await session.writer.drain()
        except Exception as exc:
            await self._send_error(session.meta, f"tcp write failed: {exc}")
            await self._close_tcp_session(connection_id)

    async def _close_tcp_session(self, connection_id: int) -> None:
        session = self._tcp_sessions.pop(connection_id, None)
        if session is None:
            return
        if asyncio.current_task() is not session.pump_task:
            session.pump_task.cancel()
            await asyncio.gather(session.pump_task, return_exceptions=True)
        session.writer.close()
        await session.writer.wait_closed()

    async def _open_udp_session(self, meta: _SessionMeta) -> None:
        if meta.connection_id in self._udp_sessions or meta.connection_id in self._tcp_sessions:
            await self._send_error(meta, "connection id already exists")
            return
        protocol = _UpstreamUdpProtocol(self, meta)
        try:
            transport, _ = await self._create_datagram_endpoint(
                lambda: protocol,
                (meta.remote_host, meta.remote_port),
            )
        except Exception as exc:
            await self._send_error(meta, f"udp connect failed: {exc}")
            await self._send_frame(meta.frame(FrameFlag.CLOSE))
            return
        self._udp_sessions[meta.connection_id] = _UdpSession(
            meta=meta,
            transport=cast(asyncio.DatagramTransport, transport),
            protocol=protocol,
        )

    async def _forward_udp_payload(self, connection_id: int, payload: bytes) -> None:
        session = self._udp_sessions.get(connection_id)
        if session is None:
            await self._send_error(
                _SessionMeta(0, connection_id, ProxyProtocol.UDP, 1, "unknown", 1),
                "unknown udp connection id",
            )
            return
        session.touch()
        session.transport.sendto(payload)

    async def _close_udp_session(self, connection_id: int) -> None:
        session = self._udp_sessions.pop(connection_id, None)
        if session is None:
            return
        session.transport.close()

    async def _send_error(self, meta: _SessionMeta, message: str) -> None:
        await self._send_frame(meta.frame(FrameFlag.ERROR, message.encode("utf-8")))

    async def _send_error_and_close(self, meta: _SessionMeta, message: str) -> None:
        await self._send_error(meta, message)
        if meta.protocol is ProxyProtocol.TCP:
            await self._close_tcp_session(meta.connection_id)
        else:
            await self._close_udp_session(meta.connection_id)
        await self._send_frame(meta.frame(FrameFlag.CLOSE))

    async def _send_frame(self, frame: ProxyFrame) -> None:
        await self._transport.send_frame(frame)

    @classmethod
    def from_config(
        cls,
        transport: UsbFrameTransport,
        config: "GlassProxyConfig",
        *,
        open_connection: Callable[[str, int], Awaitable[tuple[asyncio.StreamReader, asyncio.StreamWriter]]] = asyncio.open_connection,
        create_datagram_endpoint: Callable[
            [Callable[[], asyncio.DatagramProtocol], tuple[str, int]],
            Awaitable[tuple[asyncio.DatagramTransport, asyncio.DatagramProtocol]],
        ] | None = None,
    ) -> "GlassProxyGateway":
        return cls(
            transport,
            open_connection=open_connection,
            create_datagram_endpoint=create_datagram_endpoint,
            tcp_connect_timeout_seconds=config.tcp_connect_timeout_seconds,
            udp_idle_timeout_seconds=config.udp_idle_timeout_seconds,
            udp_reap_interval_seconds=config.udp_reap_interval_seconds,
        )

    def _touch_udp_session(self, connection_id: int) -> None:
        session = self._udp_sessions.get(connection_id)
        if session is not None:
            session.touch()

    async def _udp_reap_loop(self) -> None:
        try:
            while self._running:
                await asyncio.sleep(self._udp_reap_interval_seconds)
                now = time.monotonic()
                expired = [
                    connection_id
                    for connection_id, session in list(self._udp_sessions.items())
                    if now - session.last_activity > self._udp_idle_timeout_seconds
                ]
                for connection_id in expired:
                    session = self._udp_sessions.get(connection_id)
                    if session is None:
                        continue
                    await self._close_udp_session(connection_id)
                    await self._send_frame(session.meta.frame(FrameFlag.CLOSE))
        except asyncio.CancelledError:
            raise

    async def _default_create_datagram_endpoint(
        self,
        protocol_factory: Callable[[], asyncio.DatagramProtocol],
        remote_addr: tuple[str, int],
    ) -> tuple[asyncio.DatagramTransport, asyncio.DatagramProtocol]:
        transport, protocol = await asyncio.get_running_loop().create_datagram_endpoint(
            protocol_factory,
            remote_addr=remote_addr,
        )
        return cast(asyncio.DatagramTransport, transport), protocol
