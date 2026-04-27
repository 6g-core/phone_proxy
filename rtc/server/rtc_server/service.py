from __future__ import annotations

import logging
import uuid
import asyncio
from dataclasses import dataclass
from typing import Any

from aiohttp import web

from .config import ServerConfig
from .fixed_ice import create_fixed_port_peer_connection
from .media import PlaceholderVideoTrack
from .sdp import AdvertisedCandidateRange, rewrite_answer_sdp_for_phone_proxy


@dataclass
class SessionRecord:
    session_id: str
    requested_type: str
    allocated_udp_port: int
    peer_connection: Any | None = None
    timeout_handle: asyncio.TimerHandle | None = None
    internal_candidate_ports: tuple[int, ...] = ()


class UdpPortAllocator:
    def __init__(self, port_start: int, port_end: int) -> None:
        self._available = set(range(port_start, port_end + 1))

    def allocate(self) -> int:
        if not self._available:
            raise RuntimeError("No UDP ports available in the configured advertised range.")
        port = min(self._available)
        self._available.remove(port)
        return port

    def release(self, port: int | None) -> None:
        if port is None:
            return
        self._available.add(port)


class RtcServerService:
    def __init__(self, config: ServerConfig) -> None:
        self.config = config
        self.logger = logging.getLogger("phone-proxy-rtc-server")
        self.sessions: dict[str, SessionRecord] = {}
        self.port_allocator = UdpPortAllocator(config.udp_port_start, config.udp_port_end)

    def app(self) -> web.Application:
        app = web.Application()
        app.add_routes(
            [
                web.get("/health", self.handle_health),
                web.post("/api/v1/webrtc/offer", self.handle_offer),
                web.delete("/webrtc/session/{sessionId}", self.handle_delete_session),
            ]
        )
        app.on_shutdown.append(self.on_shutdown)
        return app

    async def handle_health(self, _request: web.Request) -> web.Response:
        return web.json_response(
            {
                "ok": True,
                "mode": "phone-proxy-webrtc-server",
                "nonTrickle": True,
                "stunEnabled": False,
                "turnEnabled": False,
                "advertisedHost": self.config.advertised_host,
                "advertisedUdpRange": self.config.advertised_udp_range_label(),
                "iceBindHost": self.config.ice_bind_host,
                "signalListen": f"{self.config.signal_listen_host}:{self.config.signal_listen_port}",
                "sessionTtlSeconds": self.config.session_ttl_seconds,
                "iceGatherTimeoutMs": self.config.ice_gather_timeout_ms,
                "activeSessions": len(self.sessions),
                "scaffoldOnly": False,
            }
        )

    async def handle_offer(self, request: web.Request) -> web.Response:
        payload = await request.json()
        offer_type = str(payload.get("type") or "").strip()
        offer_sdp = str(payload.get("sdp") or "").strip()
        if not offer_type or not offer_sdp:
            return web.json_response(
                {
                    "error": "bad_request",
                    "message": "Request body must include type and sdp.",
                },
                status=400,
            )
        validation_error = validate_offer_sdp(offer_type=offer_type, offer_sdp=offer_sdp)
        if validation_error is not None:
            return web.json_response(
                {
                    "error": "invalid_offer",
                    "message": validation_error,
                },
                status=400,
            )

        session_id = str(payload.get("sessionId") or uuid.uuid4())
        try:
            answer_payload = await self.negotiate_offer(
                session_id=session_id,
                offer_type=offer_type,
                offer_sdp=offer_sdp,
            )
            return web.json_response(answer_payload)
        except RuntimeError as exc:
            self.logger.warning("Offer rejected for session=%s: %s", session_id, exc)
            return web.json_response(
                {
                    "error": "capacity_exhausted",
                    "message": str(exc),
                    "sessionId": session_id,
                },
                status=503,
            )
        except ValueError as exc:
            self.logger.warning("Invalid offer for session=%s: %s", session_id, exc)
            await self.close_session(session_id, reason="invalid_offer")
            return web.json_response(
                {
                    "error": "invalid_offer",
                    "message": str(exc),
                    "sessionId": session_id,
                },
                status=400,
            )
        except Exception as exc:
            self.logger.exception("Offer handling failed for session=%s", session_id)
            await self.close_session(session_id, reason="offer_failed")
            return web.json_response(
                {
                    "error": "offer_failed",
                    "message": str(exc),
                    "sessionId": session_id,
                },
                status=500,
            )

    async def negotiate_offer(self, *, session_id: str, offer_type: str, offer_sdp: str) -> dict[str, object]:
        from aiortc import RTCSessionDescription

        await self.close_session(session_id, reason="replace_existing")

        allocated_port = self.port_allocator.allocate()
        session = SessionRecord(
            session_id=session_id,
            requested_type=offer_type,
            allocated_udp_port=allocated_port,
        )
        self.sessions[session_id] = session

        pc = create_fixed_port_peer_connection(
            fixed_udp_port=allocated_port,
            ice_bind_host=self.config.ice_bind_host or None,
            ice_servers=[],
        )
        session.peer_connection = pc
        pc.addTrack(PlaceholderVideoTrack())

        @pc.on("connectionstatechange")
        async def on_connectionstatechange() -> None:
            if pc.connectionState in {"failed", "closed", "disconnected"}:
                await self.close_session(session_id, reason=f"pc_{pc.connectionState}")

        offer = RTCSessionDescription(sdp=offer_sdp, type=offer_type)
        await pc.setRemoteDescription(offer)
        answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        ice_result = await self.wait_for_ice_gathering_complete(pc)
        internal_candidate_ports = self._collect_internal_candidate_ports(pc)
        session.internal_candidate_ports = internal_candidate_ports

        rewritten_answer_sdp = rewrite_answer_sdp_for_phone_proxy(
            str(pc.localDescription.sdp or ""),
            AdvertisedCandidateRange(
                host=self.config.advertised_host,
                port_start=allocated_port,
                port_end=allocated_port,
            ),
        )
        self._refresh_session_ttl(session_id)
        self.logger.info(
            "Prepared answer session=%s advertised=%s:%s internal_ports=%s",
            session_id,
            self.config.advertised_host,
            allocated_port,
            list(internal_candidate_ports),
        )
        return {
            "sessionId": session_id,
            "type": "answer",
            "sdp": rewritten_answer_sdp,
            "iceGatheringState": pc.iceGatheringState,
            "iceGatheringMode": "non-trickle",
            "iceGatheringResult": ice_result,
            "advertisedHost": self.config.advertised_host,
            "advertisedUdpRange": str(allocated_port),
            "allocatedUdpPort": allocated_port,
            "internalCandidatePorts": list(internal_candidate_ports),
        }

    async def wait_for_ice_gathering_complete(self, pc: Any) -> str:
        if pc.iceGatheringState == "complete":
            return "complete"

        done = asyncio.Event()

        @pc.on("icegatheringstatechange")
        async def on_ice_gathering_state_change() -> None:
            if pc.iceGatheringState == "complete":
                done.set()

        try:
            await asyncio.wait_for(
                done.wait(),
                timeout=max(self.config.ice_gather_timeout_ms / 1000.0, 0.1),
            )
            return "complete"
        except asyncio.TimeoutError:
            self.logger.warning("ICE gathering timed out; using current localDescription")
            return "timeout"

    def _refresh_session_ttl(self, session_id: str) -> None:
        session = self.sessions.get(session_id)
        if session is None:
            return

        if session.timeout_handle is not None:
            session.timeout_handle.cancel()
            session.timeout_handle = None

        if self.config.session_ttl_seconds <= 0:
            return

        loop = asyncio.get_running_loop()
        session.timeout_handle = loop.call_later(
            self.config.session_ttl_seconds,
            lambda: asyncio.create_task(self.close_session(session_id, reason="session_ttl_expired")),
        )

    def _collect_internal_candidate_ports(self, pc: Any) -> tuple[int, ...]:
        transports = getattr(pc, "_RTCPeerConnection__iceTransports", set())
        ports = {
            candidate.port
            for transport in transports
            for candidate in transport.iceGatherer.getLocalCandidates()
        }
        return tuple(sorted(ports))

    async def close_session(self, session_id: str, *, reason: str) -> bool:
        session = self.sessions.pop(session_id, None)
        if session is None:
            return False

        if session.timeout_handle is not None:
            session.timeout_handle.cancel()
            session.timeout_handle = None

        pc = session.peer_connection
        session.peer_connection = None
        self.port_allocator.release(session.allocated_udp_port)
        if pc is not None and getattr(pc, "connectionState", None) != "closed":
            await pc.close()
        self.logger.info("Closed session=%s reason=%s", session_id, reason)
        return True

    async def handle_delete_session(self, request: web.Request) -> web.Response:
        session_id = request.match_info["sessionId"]
        removed = await self.close_session(session_id, reason="http_delete")
        return web.json_response({"closed": removed, "sessionId": session_id})

    async def on_shutdown(self, _app: web.Application) -> None:
        session_ids = list(self.sessions)
        for session_id in session_ids:
            await self.close_session(session_id, reason="shutdown")

def run_server(config: ServerConfig) -> None:
    logging.basicConfig(
        level=getattr(logging, config.log_level, logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    service = RtcServerService(config)
    web.run_app(
        service.app(),
        host=config.signal_listen_host,
        port=config.signal_listen_port,
    )


def validate_offer_sdp(*, offer_type: str, offer_sdp: str) -> str | None:
    if offer_type != "offer":
        return f"Expected SDP type 'offer', got '{offer_type}'."

    lines = [line.strip() for line in offer_sdp.splitlines() if line.strip()]
    if not any(line.startswith("m=") for line in lines):
        return "Offer SDP must contain at least one media section such as m=video."

    return null_direction_hint(lines)


def null_direction_hint(lines: list[str]) -> str | None:
    has_media = any(line.startswith("m=") for line in lines)
    has_direction = any(
        line in {"a=sendrecv", "a=recvonly", "a=sendonly", "a=inactive"}
        for line in lines
    )
    if has_media and not has_direction:
        return (
            "Offer SDP is missing a media direction attribute. "
            "Generate the offer with the rtc client instead of hand-writing a minimal SDP."
        )
    return None
