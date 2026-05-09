from __future__ import annotations

from dataclasses import dataclass
import json
from pathlib import Path

DEFAULT_CONFIG_PATH = Path(__file__).with_name("default_config.json")


@dataclass(slots=True)
class GlassProxyConfig:
    tcp_connect_timeout_seconds: float = 5.0
    udp_idle_timeout_seconds: float = 60.0
    udp_reap_interval_seconds: float | None = None

    def validate(self) -> None:
        if self.tcp_connect_timeout_seconds <= 0:
            raise ValueError("tcp_connect_timeout_seconds must be > 0")
        if self.udp_idle_timeout_seconds <= 0:
            raise ValueError("udp_idle_timeout_seconds must be > 0")
        if (
            self.udp_reap_interval_seconds is not None
            and self.udp_reap_interval_seconds <= 0
        ):
            raise ValueError("udp_reap_interval_seconds must be > 0 when provided")


def load_config(path: str | Path) -> GlassProxyConfig:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    config = GlassProxyConfig(
        tcp_connect_timeout_seconds=raw.get("tcp_connect_timeout_seconds", 5.0),
        udp_idle_timeout_seconds=raw.get("udp_idle_timeout_seconds", 60.0),
        udp_reap_interval_seconds=raw.get("udp_reap_interval_seconds"),
    )
    config.validate()
    return config
