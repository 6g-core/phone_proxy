from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import json
from pathlib import Path


@dataclass(slots=True)
class ProxyRule:
    id: str
    name: str
    listen_ip: str
    listen_port: int
    target_ip: str
    target_port: int
    enabled: bool = True

    def validate(self) -> None:
        if not self.id.strip():
            raise ValueError("rule id must not be empty")
        if not self.name.strip():
            raise ValueError(f"rule {self.id} must have a non-empty name")
        _validate_ip(self.listen_ip, field_name=f"rule {self.id} listen_ip")
        _validate_ip(self.target_ip, field_name=f"rule {self.id} target_ip")
        _validate_port(self.listen_port, field_name=f"rule {self.id} listen_port")
        _validate_port(self.target_port, field_name=f"rule {self.id} target_port")


@dataclass(slots=True)
class ProxyConfig:
    rules: list[ProxyRule]
    udp_session_idle_timeout_seconds: int = 60

    def validate(self) -> None:
        if self.udp_session_idle_timeout_seconds <= 0:
            raise ValueError("udp_session_idle_timeout_seconds must be > 0")

        seen_bindings: set[tuple[str, int]] = set()
        rule_ids: set[str] = set()

        for rule in self.rules:
            rule.validate()
            if rule.id in rule_ids:
                raise ValueError(f"duplicate rule id: {rule.id}")
            rule_ids.add(rule.id)

            if not rule.enabled:
                continue

            binding = (rule.listen_ip, rule.listen_port)
            if binding in seen_bindings:
                raise ValueError(
                    "duplicate enabled listen binding: "
                    f"{rule.listen_ip}:{rule.listen_port}"
                )
            seen_bindings.add(binding)


def _validate_ip(value: str, *, field_name: str) -> None:
    try:
        ipaddress.ip_address(value)
    except ValueError as exc:
        raise ValueError(f"{field_name} is not a valid IPv4/IPv6 address: {value}") from exc


def _validate_port(value: int, *, field_name: str) -> None:
    if not 1 <= value <= 65535:
        raise ValueError(f"{field_name} must be between 1 and 65535, got {value}")


def load_config(path: str | Path) -> ProxyConfig:
    raw = json.loads(Path(path).read_text(encoding="utf-8"))
    rules = [
        ProxyRule(
            id=item["id"],
            name=item["name"],
            listen_ip=item["listen_ip"],
            listen_port=item["listen_port"],
            target_ip=item["target_ip"],
            target_port=item["target_port"],
            enabled=item.get("enabled", True),
        )
        for item in raw.get("rules", [])
    ]
    config = ProxyConfig(
        rules=rules,
        udp_session_idle_timeout_seconds=raw.get("udp_session_idle_timeout_seconds", 60),
    )
    config.validate()
    return config
