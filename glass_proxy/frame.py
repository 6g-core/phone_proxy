from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum
import struct


class ProxyProtocol(IntEnum):
    TCP = 1
    UDP = 2


class FrameFlag(IntEnum):
    OPEN = 1
    DATA = 2
    CLOSE = 3
    ERROR = 4


@dataclass(slots=True)
class ProxyFrame:
    tunnel_epoch: int
    connection_id: int
    protocol: ProxyProtocol
    flag: FrameFlag
    listen_port: int
    remote_host: str
    remote_port: int
    payload: bytes = b""

    def __post_init__(self) -> None:
        if self.tunnel_epoch < 0:
            raise ValueError("tunnel_epoch must be non-negative")
        if self.connection_id < 0:
            raise ValueError("connection_id must be non-negative")
        if not 1 <= self.listen_port <= 65535:
            raise ValueError("listen_port must be between 1 and 65535")
        if not 1 <= self.remote_port <= 65535:
            raise ValueError("remote_port must be between 1 and 65535")
        if not self.remote_host:
            raise ValueError("remote_host must not be empty")


class FrameCodec:
    MAGIC = b"GPRX"
    VERSION = 1
    HEADER = struct.Struct("!4sBBBBQQHHHI")

    @classmethod
    def encode(cls, frame: ProxyFrame) -> bytes:
        host_bytes = frame.remote_host.encode("utf-8")
        payload = bytes(frame.payload)
        header = cls.HEADER.pack(
            cls.MAGIC,
            cls.VERSION,
            int(frame.protocol),
            int(frame.flag),
            0,
            frame.tunnel_epoch,
            frame.connection_id,
            frame.listen_port,
            frame.remote_port,
            len(host_bytes),
            len(payload),
        )
        return header + host_bytes + payload

    @classmethod
    def decode_from_parts(
        cls,
        header_bytes: bytes,
        host_bytes: bytes,
        payload: bytes,
    ) -> ProxyFrame:
        (
            magic,
            version,
            protocol,
            flag,
            _reserved,
            tunnel_epoch,
            connection_id,
            listen_port,
            remote_port,
            host_len,
            payload_len,
        ) = cls.HEADER.unpack(header_bytes)
        if magic != cls.MAGIC:
            raise ValueError(f"invalid frame magic: {magic!r}")
        if version != cls.VERSION:
            raise ValueError(f"unsupported frame version: {version}")
        if host_len != len(host_bytes):
            raise ValueError("remote host length mismatch")
        if payload_len != len(payload):
            raise ValueError("payload length mismatch")
        return ProxyFrame(
            tunnel_epoch=tunnel_epoch,
            connection_id=connection_id,
            protocol=ProxyProtocol(protocol),
            flag=FrameFlag(flag),
            listen_port=listen_port,
            remote_host=host_bytes.decode("utf-8"),
            remote_port=remote_port,
            payload=payload,
        )

    @classmethod
    def decode(cls, raw: bytes) -> ProxyFrame:
        if len(raw) < cls.HEADER.size:
            raise ValueError("raw frame shorter than fixed header")
        header = raw[: cls.HEADER.size]
        unpacked = cls.HEADER.unpack(header)
        host_len = unpacked[-2]
        payload_len = unpacked[-1]
        offset = cls.HEADER.size
        host_bytes = raw[offset : offset + host_len]
        offset += host_len
        payload = raw[offset : offset + payload_len]
        if offset + payload_len != len(raw):
            raise ValueError("raw frame has trailing or truncated bytes")
        return cls.decode_from_parts(header, host_bytes, payload)
