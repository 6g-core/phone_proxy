from __future__ import annotations

import asyncio
import json
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .config import ClientConfig


@dataclass
class HealthCheckResult:
    status: int
    payload: dict[str, object]


@dataclass
class ConnectResult:
    session_id: str
    connection_state: str
    ice_gathering_state: str
    remote_tracks: list[str]
    answer_summary: dict[str, object]
    local_candidates: list[dict[str, object]]
    selected_candidate_pairs: list[dict[str, object]]
    recording: dict[str, object] | None = None


class RtcClientApp:
    def __init__(self, config: ClientConfig) -> None:
        self.config = config

    def show_config(self) -> str:
        return json.dumps(self.config.to_dict(), indent=2, ensure_ascii=False)

    def health_check(self) -> HealthCheckResult:
        if not self.config.health_url:
            raise ValueError("health_url is required for the health command.")

        request = urllib.request.Request(
            self.config.health_url,
            method="GET",
            headers={"Accept": "application/json"},
        )
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=5) as response:
            payload = json.loads(response.read().decode("utf-8"))
            return HealthCheckResult(status=response.status, payload=payload)

    def connect(self) -> ConnectResult:
        return asyncio.run(self._connect())

    def connect_and_record(self, record_path: str, *, record_seconds: float = 10.0) -> ConnectResult:
        return asyncio.run(self._connect(record_path=record_path, record_seconds=record_seconds))

    async def _connect(
        self,
        *,
        record_path: str | None = None,
        record_seconds: float = 10.0,
    ) -> ConnectResult:
        from aiohttp import ClientSession
        from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
        from aiortc.contrib.media import MediaRecorder

        pc = RTCPeerConnection(configuration=RTCConfiguration(iceServers=[]))
        remote_tracks: list[str] = []
        connected = asyncio.Event()
        failed = asyncio.Event()
        remote_track_ready = asyncio.Event()
        recorder: MediaRecorder | None = None
        recorder_started = False
        recording_summary: dict[str, object] | None = None

        if record_path:
            output_path = Path(record_path).expanduser().resolve()
            output_path.parent.mkdir(parents=True, exist_ok=True)
            recorder = MediaRecorder(str(output_path))

        @pc.on("track")
        def on_track(track: object) -> None:
            kind = getattr(track, "kind", "unknown")
            remote_tracks.append(str(kind))
            if recorder is not None:
                recorder.addTrack(track)
                remote_track_ready.set()

        @pc.on("connectionstatechange")
        async def on_connectionstatechange() -> None:
            if pc.connectionState == "connected":
                connected.set()
            elif pc.connectionState in {"failed", "closed", "disconnected"}:
                failed.set()

        pc.addTransceiver(self.config.media_kind, direction="recvonly")
        offer = await pc.createOffer()
        await pc.setLocalDescription(offer)
        await self._wait_for_ice_gathering_complete(pc)

        request_body = {
            "sessionId": self.config.session_id or "",
            "type": str(pc.localDescription.type),
            "sdp": str(pc.localDescription.sdp or ""),
        }

        async with ClientSession() as session:
            async with session.post(self.config.offer_url, json=request_body) as response:
                response.raise_for_status()
                answer_payload = await response.json()

        answer = RTCSessionDescription(
            sdp=str(answer_payload["sdp"]),
            type=str(answer_payload["type"]),
        )
        await pc.setRemoteDescription(answer)

        try:
            await asyncio.wait_for(
                asyncio.shield(self._wait_for_connected(connected, failed)),
                timeout=max(self.config.connection_wait_timeout_ms / 1000.0, 0.1),
            )
            if recorder is not None:
                await asyncio.wait_for(
                    remote_track_ready.wait(),
                    timeout=max(self.config.connection_wait_timeout_ms / 1000.0, 0.1),
                )
                await recorder.start()
                recorder_started = True
                await asyncio.sleep(max(record_seconds, 0.1))
                recording_summary = {
                    "path": str(Path(record_path).expanduser().resolve()),
                    "seconds": max(record_seconds, 0.1),
                    "tracks": list(remote_tracks),
                }
        finally:
            connection_state = pc.connectionState
            ice_gathering_state = pc.iceGatheringState
            local_candidates = self._collect_local_candidates(pc)
            selected_candidate_pairs = self._collect_selected_candidate_pairs(pc)
            if recorder is not None and recorder_started:
                await recorder.stop()
            await pc.close()

        return ConnectResult(
            session_id=str(answer_payload.get("sessionId") or self.config.session_id),
            connection_state=connection_state,
            ice_gathering_state=ice_gathering_state,
            remote_tracks=remote_tracks,
            answer_summary={
                "advertisedHost": answer_payload.get("advertisedHost"),
                "advertisedUdpRange": answer_payload.get("advertisedUdpRange"),
                "allocatedUdpPort": answer_payload.get("allocatedUdpPort"),
                "internalCandidatePorts": answer_payload.get("internalCandidatePorts"),
                "iceGatheringResult": answer_payload.get("iceGatheringResult"),
            },
            local_candidates=local_candidates,
            selected_candidate_pairs=selected_candidate_pairs,
            recording=recording_summary,
        )

    async def _wait_for_ice_gathering_complete(self, pc: object) -> None:
        if getattr(pc, "iceGatheringState", None) == "complete":
            return

        done = asyncio.Event()

        @pc.on("icegatheringstatechange")
        async def on_ice_gathering_state_change() -> None:
            if getattr(pc, "iceGatheringState", None) == "complete":
                done.set()

        await asyncio.wait_for(done.wait(), timeout=max(self.config.ice_gather_timeout_ms / 1000.0, 0.1))

    async def _wait_for_connected(self, connected: asyncio.Event, failed: asyncio.Event) -> None:
        connected_task = asyncio.create_task(connected.wait())
        failed_task = asyncio.create_task(failed.wait())
        done, pending = await asyncio.wait(
            {connected_task, failed_task},
            return_when=asyncio.FIRST_COMPLETED,
        )
        for task in pending:
            task.cancel()

        if failed_task in done and failed.is_set() and not connected.is_set():
            raise RuntimeError("Peer connection entered a failed/disconnected state before connecting.")

        if connected_task in done and connected.is_set():
            return

        raise RuntimeError("Peer connection did not reach a connected state.")

    def _collect_local_candidates(self, pc: object) -> list[dict[str, object]]:
        transports = getattr(pc, "_RTCPeerConnection__iceTransports", set())
        candidates: list[dict[str, object]] = []
        seen: set[tuple[object, ...]] = set()
        for transport in transports:
            for candidate in transport.iceGatherer.getLocalCandidates():
                item = self._serialize_rtc_candidate(candidate)
                key = (
                    item["component"],
                    item["foundation"],
                    item["protocol"],
                    item["type"],
                    item["ip"],
                    item["port"],
                )
                if key in seen:
                    continue
                seen.add(key)
                candidates.append(item)
        return sorted(candidates, key=lambda item: (int(item["component"]), str(item["ip"]), int(item["port"])))

    def _collect_selected_candidate_pairs(self, pc: object) -> list[dict[str, object]]:
        transports = getattr(pc, "_RTCPeerConnection__iceTransports", set())
        selected_pairs: list[dict[str, object]] = []
        for transport in transports:
            connection = getattr(transport, "_connection", None)
            nominated = getattr(connection, "_nominated", {}) if connection is not None else {}
            for component, pair in sorted(nominated.items()):
                selected_pairs.append(
                    {
                        "component": int(component),
                        "state": getattr(getattr(pair, "state", None), "name", str(getattr(pair, "state", ""))).lower(),
                        "local": self._serialize_aioice_candidate(pair.local_candidate),
                        "remote": self._serialize_aioice_candidate(pair.remote_candidate),
                    }
                )
        return selected_pairs

    def _serialize_rtc_candidate(self, candidate: object) -> dict[str, object]:
        return {
            "component": int(getattr(candidate, "component")),
            "foundation": str(getattr(candidate, "foundation")),
            "protocol": str(getattr(candidate, "protocol")),
            "type": str(getattr(candidate, "type")),
            "ip": str(getattr(candidate, "ip")),
            "port": int(getattr(candidate, "port")),
            "priority": int(getattr(candidate, "priority")),
            "relatedAddress": self._optional_str(getattr(candidate, "relatedAddress", None)),
            "relatedPort": self._optional_int(getattr(candidate, "relatedPort", None)),
            "tcpType": self._optional_str(getattr(candidate, "tcpType", None)),
        }

    def _serialize_aioice_candidate(self, candidate: object) -> dict[str, object]:
        return {
            "component": int(getattr(candidate, "component")),
            "foundation": str(getattr(candidate, "foundation")),
            "protocol": str(getattr(candidate, "transport")),
            "type": str(getattr(candidate, "type")),
            "ip": str(getattr(candidate, "host")),
            "port": int(getattr(candidate, "port")),
            "priority": int(getattr(candidate, "priority")),
            "relatedAddress": self._optional_str(getattr(candidate, "related_address", None)),
            "relatedPort": self._optional_int(getattr(candidate, "related_port", None)),
            "tcpType": self._optional_str(getattr(candidate, "tcptype", None)),
        }

    def _optional_str(self, value: object) -> str | None:
        if value in {None, ""}:
            return None
        return str(value)

    def _optional_int(self, value: object) -> int | None:
        if value in {None, ""}:
            return None
        return int(value)
