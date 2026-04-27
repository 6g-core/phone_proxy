from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass(frozen=True)
class ClientConfig:
    offer_url: str
    health_url: str
    session_id: str = ""
    ice_gather_timeout_ms: int = 8000
    connection_wait_timeout_ms: int = 15000
    media_kind: str = "video"
    log_level: str = "INFO"

    @classmethod
    def load(cls, path: str | Path) -> "ClientConfig":
        config_path = Path(path)
        payload = json.loads(config_path.read_text(encoding="utf-8"))
        return cls(
            offer_url=str(payload["offer_url"]).strip(),
            health_url=str(payload.get("health_url") or "").strip(),
            session_id=str(payload.get("session_id") or "").strip(),
            ice_gather_timeout_ms=int(payload.get("ice_gather_timeout_ms", 8000)),
            connection_wait_timeout_ms=int(payload.get("connection_wait_timeout_ms", 15000)),
            media_kind=str(payload.get("media_kind") or "video").strip(),
            log_level=str(payload.get("log_level") or "INFO").strip().upper(),
        )

    def to_dict(self) -> dict[str, object]:
        return asdict(self)
