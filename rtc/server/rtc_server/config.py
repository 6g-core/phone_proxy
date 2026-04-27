from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class ServerConfig:
    signal_listen_host: str
    signal_listen_port: int
    advertised_host: str
    udp_port_start: int
    udp_port_end: int
    ice_bind_host: str = ""
    session_ttl_seconds: int = 300
    ice_gather_timeout_ms: int = 8000
    log_level: str = "INFO"

    @classmethod
    def load(cls, path: str | Path) -> "ServerConfig":
        config_path = Path(path)
        payload = json.loads(config_path.read_text(encoding="utf-8"))
        return cls(
            signal_listen_host=str(payload.get("signal_listen_host") or "0.0.0.0").strip(),
            signal_listen_port=int(payload.get("signal_listen_port", 18080)),
            advertised_host=str(payload["advertised_host"]).strip(),
            udp_port_start=int(payload["udp_port_start"]),
            udp_port_end=int(payload["udp_port_end"]),
            ice_bind_host=str(payload.get("ice_bind_host") or "").strip(),
            session_ttl_seconds=int(payload.get("session_ttl_seconds", 300)),
            ice_gather_timeout_ms=int(payload.get("ice_gather_timeout_ms", 8000)),
            log_level=str(payload.get("log_level") or "INFO").strip().upper(),
        )

    def advertised_udp_range_label(self) -> str:
        if self.udp_port_start == self.udp_port_end:
            return str(self.udp_port_start)
        return f"{self.udp_port_start}-{self.udp_port_end}"

    def to_dict(self) -> dict[str, object]:
        return asdict(self)
