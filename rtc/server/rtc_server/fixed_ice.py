from __future__ import annotations

import asyncio
import ipaddress
import socket
from typing import Any, Optional

from aioice import Candidate
from aioice.ice import (
    Connection,
    StunProtocol,
    TransportPolicy,
    candidate_foundation,
    candidate_priority,
    get_host_addresses,
    relayed_candidate,
    server_reflexive_candidate,
    turn,
)
from aiortc import RTCDtlsTransport, RTCIceGatherer, RTCIceServer, RTCIceTransport, RTCPeerConnection
from aiortc.rtcicetransport import connection_kwargs


class FixedPortConnection(Connection):
    def __init__(
        self,
        *,
        fixed_port: int,
        bind_host: str | None = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(**kwargs)
        self._fixed_port = fixed_port
        self._bind_host = (bind_host or "").strip()

    async def gather_candidates(self) -> None:
        if not self._local_candidates_start:
            self._local_candidates_start = True
            if self._bind_host and self._bind_host != "0.0.0.0":
                addresses = [self._bind_host]
            else:
                addresses = get_host_addresses(use_ipv4=self._use_ipv4, use_ipv6=self._use_ipv6)
            coros = [
                self.get_component_candidates(component=component, addresses=addresses)
                for component in self._components
            ]
            for candidates in await asyncio.gather(*coros):
                self._local_candidates += candidates
            self._local_candidates_end = True

    async def get_component_candidates(
        self,
        component: int,
        addresses: list[str],
        timeout: int = 5,
    ) -> list[Candidate]:
        candidates: list[Candidate] = []
        loop = asyncio.get_event_loop()

        host_protocols: list[StunProtocol] = []
        for address in addresses:
            try:
                transport, protocol = await loop.create_datagram_endpoint(
                    lambda: StunProtocol(self),
                    local_addr=(address, self._fixed_port),
                )
                sock = transport.get_extra_info("socket")
                if sock is not None:
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, turn.UDP_SOCKET_BUFFER_SIZE)
            except OSError as exc:
                self._Connection__log_info("Could not bind to %s:%s - %s", address, self._fixed_port, exc)
                continue

            host_protocols.append(protocol)
            candidate_address = protocol.transport.get_extra_info("sockname")
            protocol.local_candidate = Candidate(
                foundation=candidate_foundation("host", "udp", candidate_address[0]),
                component=component,
                transport="udp",
                priority=candidate_priority(component, "host"),
                host=candidate_address[0],
                port=candidate_address[1],
                type="host",
            )
            if self._transport_policy == TransportPolicy.ALL:
                candidates.append(protocol.local_candidate)
        self._protocols += host_protocols

        tasks: list[asyncio.Task[tuple[Candidate, Optional[StunProtocol]]]] = []
        if self.stun_server:
            for protocol in host_protocols:
                if ipaddress.ip_address(protocol.local_candidate.host).version == 4:
                    tasks.append(
                        asyncio.create_task(server_reflexive_candidate(protocol, self.stun_server))
                    )

        if self.turn_server:
            tasks.append(
                asyncio.create_task(
                    relayed_candidate(
                        component=component,
                        protocol_factory=lambda: StunProtocol(self),
                        turn_server=self.turn_server,
                        turn_username=self.turn_username,
                        turn_password=self.turn_password,
                        turn_ssl=self.turn_ssl,
                        turn_transport=self.turn_transport,
                    )
                )
            )

        if tasks:
            done, _pending = await asyncio.wait(tasks, timeout=timeout)
            for task in done:
                if task.exception() is None:
                    candidate, protocol = task.result()
                    candidates.append(candidate)
                    if protocol is not None:
                        self._protocols.append(protocol)

        return candidates


class FixedPortIceGatherer(RTCIceGatherer):
    def __init__(
        self,
        *,
        fixed_port: int,
        bind_host: str | None,
        iceServers: Optional[list[RTCIceServer]] = None,
    ) -> None:
        effective_servers = iceServers if iceServers is not None else self.getDefaultIceServers()
        super().__init__(iceServers=iceServers)
        self._connection = FixedPortConnection(
            ice_controlling=False,
            fixed_port=fixed_port,
            bind_host=bind_host,
            **connection_kwargs(effective_servers),
        )
        self._remote_candidates_end = False


class FixedPortPeerConnection(RTCPeerConnection):
    def __init__(
        self,
        *args: Any,
        fixed_udp_port: int,
        ice_bind_host: str | None,
        **kwargs: Any,
    ) -> None:
        self._fixed_udp_port = fixed_udp_port
        self._ice_bind_host = ice_bind_host
        super().__init__(*args, **kwargs)

    def _RTCPeerConnection__createDtlsTransport(self) -> RTCDtlsTransport:
        iceGatherer = FixedPortIceGatherer(
            fixed_port=self._fixed_udp_port,
            bind_host=self._ice_bind_host,
            iceServers=self._RTCPeerConnection__configuration.iceServers,
        )
        iceGatherer.on("statechange", self._RTCPeerConnection__updateIceGatheringState)
        iceTransport = RTCIceTransport(iceGatherer)
        iceTransport.on("statechange", self._RTCPeerConnection__updateIceConnectionState)
        iceTransport.on("statechange", self._RTCPeerConnection__updateConnectionState)
        self._RTCPeerConnection__iceTransports.add(iceTransport)

        dtlsTransport = RTCDtlsTransport(
            iceTransport,
            self._RTCPeerConnection__certificates,
        )
        dtlsTransport.on("statechange", self._RTCPeerConnection__updateConnectionState)
        self._RTCPeerConnection__dtlsTransports.add(dtlsTransport)

        self._RTCPeerConnection__updateIceGatheringState()
        self._RTCPeerConnection__updateIceConnectionState()
        self._RTCPeerConnection__updateConnectionState()
        return dtlsTransport


def create_fixed_port_peer_connection(
    *,
    fixed_udp_port: int,
    ice_bind_host: str | None,
    ice_servers: list[RTCIceServer] | None,
) -> FixedPortPeerConnection:
    from aiortc import RTCConfiguration

    return FixedPortPeerConnection(
        configuration=RTCConfiguration(iceServers=ice_servers or []),
        fixed_udp_port=fixed_udp_port,
        ice_bind_host=ice_bind_host,
    )
