from __future__ import annotations

import abc
import asyncio
from typing import Protocol

from .frame import FrameCodec, ProxyFrame


class UsbFrameTransport(abc.ABC):
    @abc.abstractmethod
    async def read_frame(self) -> ProxyFrame | None:
        raise NotImplementedError

    @abc.abstractmethod
    async def send_frame(self, frame: ProxyFrame) -> None:
        raise NotImplementedError

    @abc.abstractmethod
    async def close(self) -> None:
        raise NotImplementedError


class _ReadableWriter(Protocol):
    async def readexactly(self, n: int) -> bytes:
        ...

    def write(self, data: bytes) -> None:
        ...

    async def drain(self) -> None:
        ...

    def close(self) -> None:
        ...

    async def wait_closed(self) -> None:
        ...


class StreamUsbFrameTransport(UsbFrameTransport):
    def __init__(self, stream: _ReadableWriter) -> None:
        self._stream = stream
        self._write_lock = asyncio.Lock()
        self._closed = False

    async def read_frame(self) -> ProxyFrame | None:
        if self._closed:
            return None
        try:
            header = await self._stream.readexactly(FrameCodec.HEADER.size)
        except asyncio.IncompleteReadError:
            return None

        unpacked = FrameCodec.HEADER.unpack(header)
        host_len = unpacked[-2]
        payload_len = unpacked[-1]
        host_bytes = await self._stream.readexactly(host_len)
        payload = await self._stream.readexactly(payload_len)
        return FrameCodec.decode_from_parts(header, host_bytes, payload)

    async def send_frame(self, frame: ProxyFrame) -> None:
        if self._closed:
            raise RuntimeError("USB transport is already closed")
        encoded = FrameCodec.encode(frame)
        async with self._write_lock:
            self._stream.write(encoded)
            await self._stream.drain()

    async def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._stream.close()
        await self._stream.wait_closed()
