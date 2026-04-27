from __future__ import annotations

from aiortc import VideoStreamTrack
from av import VideoFrame


class PlaceholderVideoTrack(VideoStreamTrack):
    """
    A tiny synthetic black video source used only for the first integration step.
    """

    kind = "video"

    def __init__(self, width: int = 640, height: int = 360) -> None:
        super().__init__()
        self.width = width
        self.height = height

    async def recv(self) -> VideoFrame:
        pts, time_base = await self.next_timestamp()
        frame = VideoFrame(width=self.width, height=self.height, format="yuv420p")
        for plane in frame.planes:
            plane.update(bytes(plane.buffer_size))
        frame.pts = pts
        frame.time_base = time_base
        return frame
