from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass, field
import itertools
import logging
import time
from typing import cast

from phone_proxy_core.config import ProxyConfig, ProxyRule, load_config


LOGGER = logging.getLogger("phone_proxy")
TCP_CONN_IDS = itertools.count(1)
UDP_SESSION_IDS = itertools.count(1)


@dataclass(slots=True)
class UdpSession:
    id: int
    client_addr: tuple[str, int]
    transport: asyncio.DatagramTransport
    created_at: float = field(default_factory=time.monotonic)
    last_activity: float = field(default_factory=time.monotonic)
    packets_up: int = 0
    packets_down: int = 0
    bytes_up: int = 0
    bytes_down: int = 0

    def touch(self) -> None:
        self.last_activity = time.monotonic()

    def close(self) -> None:
        self.transport.close()


class UpstreamUdpProtocol(asyncio.DatagramProtocol):
    def __init__(self, runtime: "UdpRuleRuntime", client_addr: tuple[str, int]) -> None:
        self.runtime = runtime
        self.client_addr = client_addr
        self.session: UdpSession | None = None

    def attach_session(self, session: UdpSession) -> None:
        self.session = session

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        if self.session is None or self.runtime.listener_transport is None:
            return
        self.session.touch()
        self.session.packets_down += 1
        self.session.bytes_down += len(data)
        self.runtime.listener_transport.sendto(data, self.client_addr)

    def error_received(self, exc: Exception) -> None:
        self.runtime.logger.warning(
            "[rule=%s udp sess=%s] upstream error: %s",
            self.runtime.rule.name,
            getattr(self.session, "id", "pending"),
            exc,
        )


class UdpRuleRuntime(asyncio.DatagramProtocol):
    def __init__(
        self,
        rule: ProxyRule,
        idle_timeout_seconds: int,
        logger: logging.Logger,
    ) -> None:
        self.rule = rule
        self.idle_timeout_seconds = idle_timeout_seconds
        self.logger = logger
        self.listener_transport: asyncio.DatagramTransport | None = None
        self.sessions: dict[tuple[str, int], UdpSession] = {}
        self._session_locks: dict[tuple[str, int], asyncio.Lock] = {}
        self._reaper_task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        loop = asyncio.get_running_loop()
        transport, _ = await loop.create_datagram_endpoint(
            lambda: self,
            local_addr=(self.rule.listen_ip, self.rule.listen_port),
        )
        self.listener_transport = cast(asyncio.DatagramTransport, transport)
        self._reaper_task = asyncio.create_task(self._reap_sessions())

    async def stop(self) -> None:
        if self._reaper_task is not None:
            self._reaper_task.cancel()
            try:
                await self._reaper_task
            except asyncio.CancelledError:
                pass
            self._reaper_task = None

        for client_addr in list(self.sessions):
            self._close_session(client_addr, reason="shutdown")

        if self.listener_transport is not None:
            self.listener_transport.close()
            self.listener_transport = None

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.listener_transport = cast(asyncio.DatagramTransport, transport)
        self.logger.info(
            "[rule=%s udp] listening on %s:%s",
            self.rule.name,
            self.rule.listen_ip,
            self.rule.listen_port,
        )

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        asyncio.create_task(self._handle_datagram(data, addr))

    async def _handle_datagram(self, data: bytes, client_addr: tuple[str, int]) -> None:
        session = await self._ensure_session(client_addr)
        session.touch()
        session.packets_up += 1
        session.bytes_up += len(data)
        session.transport.sendto(data)
        self.logger.debug(
            "[rule=%s udp sess=%s] forwarded %s bytes from %s:%s to %s:%s",
            self.rule.name,
            session.id,
            len(data),
            client_addr[0],
            client_addr[1],
            self.rule.target_ip,
            self.rule.target_port,
        )

    async def _ensure_session(self, client_addr: tuple[str, int]) -> UdpSession:
        session = self.sessions.get(client_addr)
        if session is not None:
            return session

        lock = self._session_locks.setdefault(client_addr, asyncio.Lock())
        async with lock:
            session = self.sessions.get(client_addr)
            if session is not None:
                return session

            loop = asyncio.get_running_loop()
            protocol = UpstreamUdpProtocol(self, client_addr)
            transport, _ = await loop.create_datagram_endpoint(
                lambda: protocol,
                remote_addr=(self.rule.target_ip, self.rule.target_port),
            )
            session = UdpSession(
                id=next(UDP_SESSION_IDS),
                client_addr=client_addr,
                transport=cast(asyncio.DatagramTransport, transport),
            )
            protocol.attach_session(session)
            self.sessions[client_addr] = session
            self.logger.info(
                "[rule=%s udp sess=%s] accepted %s:%s -> %s:%s",
                self.rule.name,
                session.id,
                client_addr[0],
                client_addr[1],
                self.rule.target_ip,
                self.rule.target_port,
            )
            return session

    async def _reap_sessions(self) -> None:
        try:
            while True:
                await asyncio.sleep(max(1, self.idle_timeout_seconds // 2))
                now = time.monotonic()
                for client_addr, session in list(self.sessions.items()):
                    if now - session.last_activity > self.idle_timeout_seconds:
                        self._close_session(client_addr, reason="idle_timeout")
        except asyncio.CancelledError:
            raise

    def _close_session(self, client_addr: tuple[str, int], *, reason: str) -> None:
        session = self.sessions.pop(client_addr, None)
        if session is None:
            return
        session.close()
        self._session_locks.pop(client_addr, None)
        self.logger.info(
            "[rule=%s udp sess=%s] closed reason=%s packets_up=%s packets_down=%s bytes_up=%s bytes_down=%s",
            self.rule.name,
            session.id,
            reason,
            session.packets_up,
            session.packets_down,
            session.bytes_up,
            session.bytes_down,
        )


class RuleRuntime:
    def __init__(self, rule: ProxyRule, idle_timeout_seconds: int) -> None:
        self.rule = rule
        self.idle_timeout_seconds = idle_timeout_seconds
        self.logger = logging.getLogger(f"phone_proxy.rule.{rule.id}")
        self._tcp_server: asyncio.AbstractServer | None = None
        self._udp_runtime = UdpRuleRuntime(rule, idle_timeout_seconds, self.logger)

    async def start(self) -> None:
        self._tcp_server = await asyncio.start_server(
            self._handle_tcp_connection,
            host=self.rule.listen_ip,
            port=self.rule.listen_port,
        )
        self.logger.info(
            "[rule=%s tcp] listening on %s:%s",
            self.rule.name,
            self.rule.listen_ip,
            self.rule.listen_port,
        )
        await self._udp_runtime.start()

    async def stop(self) -> None:
        if self._tcp_server is not None:
            self._tcp_server.close()
            await self._tcp_server.wait_closed()
            self._tcp_server = None
        await self._udp_runtime.stop()

    async def _handle_tcp_connection(
        self,
        client_reader: asyncio.StreamReader,
        client_writer: asyncio.StreamWriter,
    ) -> None:
        conn_id = next(TCP_CONN_IDS)
        client_peer = _format_peer(client_writer.get_extra_info("peername"))
        start_time = time.monotonic()
        self.logger.info(
            "[rule=%s tcp conn=%s] accepted %s -> %s:%s",
            self.rule.name,
            conn_id,
            client_peer,
            self.rule.listen_ip,
            self.rule.listen_port,
        )

        upstream_writer: asyncio.StreamWriter | None = None
        upstream_reader: asyncio.StreamReader | None = None
        uplink_bytes = 0
        downlink_bytes = 0

        try:
            upstream_reader, upstream_writer = await asyncio.open_connection(
                self.rule.target_ip,
                self.rule.target_port,
            )
            self.logger.info(
                "[rule=%s tcp conn=%s] dial target %s:%s ok",
                self.rule.name,
                conn_id,
                self.rule.target_ip,
                self.rule.target_port,
            )

            up_task = asyncio.create_task(
                _pipe_stream(client_reader, upstream_writer),
                name=f"uplink-{conn_id}",
            )
            down_task = asyncio.create_task(
                _pipe_stream(upstream_reader, client_writer),
                name=f"downlink-{conn_id}",
            )
            done, pending = await asyncio.wait(
                {up_task, down_task},
                return_when=asyncio.FIRST_COMPLETED,
            )
            for task in pending:
                task.cancel()
            for task in pending:
                try:
                    await task
                except asyncio.CancelledError:
                    pass
            for task in done:
                if task is up_task:
                    uplink_bytes = task.result()
                if task is down_task:
                    downlink_bytes = task.result()
        except Exception as exc:
            self.logger.warning(
                "[rule=%s tcp conn=%s] failed: %s",
                self.rule.name,
                conn_id,
                exc,
            )
        finally:
            if upstream_writer is not None:
                upstream_writer.close()
                await upstream_writer.wait_closed()
            client_writer.close()
            await client_writer.wait_closed()
            duration_ms = int((time.monotonic() - start_time) * 1000)
            self.logger.info(
                "[rule=%s tcp conn=%s] closed uplink_bytes=%s downlink_bytes=%s duration_ms=%s",
                self.rule.name,
                conn_id,
                uplink_bytes,
                downlink_bytes,
                duration_ms,
            )


class PhoneProxyApp:
    def __init__(self, config: ProxyConfig) -> None:
        self.config = config
        self.runtimes: list[RuleRuntime] = []

    async def start(self) -> None:
        self.config.validate()
        for rule in self.config.rules:
            if not rule.enabled:
                continue
            runtime = RuleRuntime(rule, self.config.udp_session_idle_timeout_seconds)
            await runtime.start()
            self.runtimes.append(runtime)

    async def stop(self) -> None:
        for runtime in reversed(self.runtimes):
            await runtime.stop()
        self.runtimes.clear()


async def _pipe_stream(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
) -> int:
    total = 0
    while True:
        chunk = await reader.read(65536)
        if not chunk:
            break
        writer.write(chunk)
        await writer.drain()
        total += len(chunk)
    return total


def _format_peer(peer: object) -> str:
    if isinstance(peer, tuple) and len(peer) >= 2:
        return f"{peer[0]}:{peer[1]}"
    return "unknown"


async def _run(config: ProxyConfig) -> None:
    app = PhoneProxyApp(config)
    await app.start()
    stop_event = asyncio.Event()
    try:
        await stop_event.wait()
    finally:
        await app.stop()


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the multi-rule phone proxy.")
    parser.add_argument("--config", required=True)
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    config = load_config(args.config)
    asyncio.run(_run(config))


if __name__ == "__main__":
    main()
