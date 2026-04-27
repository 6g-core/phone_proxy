from __future__ import annotations

from dataclasses import dataclass


def _parse_candidate_line(line: str) -> list[str] | None:
    if not line.startswith("a=candidate:"):
        return None
    parts = line.split()
    if len(parts) < 8:
        return None
    return parts


def _rewrite_candidate_line(line: str, host: str, port: int) -> str:
    parts = _parse_candidate_line(line)
    if parts is None:
        return line
    parts[4] = host
    parts[5] = str(port)
    return " ".join(parts)


@dataclass(frozen=True)
class AdvertisedCandidateRange:
    host: str
    port_start: int
    port_end: int

    def label(self) -> str:
        if self.port_start == self.port_end:
            return f"{self.host}:{self.port_start}"
        return f"{self.host}:{self.port_start}-{self.port_end}"


def rewrite_answer_sdp_for_phone_proxy(sdp: str, advertised: AdvertisedCandidateRange) -> str:
    return rewrite_answer_sdp_for_allocated_port(sdp, host=advertised.host, port=advertised.port_start)


def rewrite_answer_sdp_for_allocated_port(sdp: str, host: str, port: int) -> str:
    """
    Rewrite the first media section to expose a fixed proxy IP:port.

    This keeps the SDP shape compatible with aiortc's generated answer, but
    swaps the public-facing IP / port to the configured phone proxy endpoint.
    """
    if not sdp.strip():
        return sdp

    lines = sdp.splitlines()
    media_start = next((index for index, line in enumerate(lines) if line.startswith("m=video ")), -1)
    if media_start < 0:
        media_start = next((index for index, line in enumerate(lines) if line.startswith("m=")), -1)
    if media_start < 0:
        return sdp

    media_end = len(lines)
    for index in range(media_start + 1, len(lines)):
        if lines[index].startswith("m="):
            media_end = index
            break

    media_lines = lines[media_start:media_end]
    rewritten_media_lines: list[str] = []
    rewritten_candidates: list[str] = []
    saw_end_of_candidates = False

    for line in media_lines:
        candidate_parts = _parse_candidate_line(line)
        if candidate_parts is not None:
            transport = candidate_parts[2].lower() if len(candidate_parts) >= 3 else ""
            if transport == "udp":
                rewritten_candidates.append(_rewrite_candidate_line(line, host=host, port=port))
            continue

        if line.startswith("m="):
            parts = line.split()
            if len(parts) >= 2:
                parts[1] = str(port)
                line = " ".join(parts)
        elif line.startswith("c=IN IP4 "):
            line = f"c=IN IP4 {host}"
        elif line.startswith("c=IN IP6 "):
            line = f"c=IN IP6 {host}"
        elif line == "a=end-of-candidates":
            saw_end_of_candidates = True
            rewritten_media_lines.extend(rewritten_candidates)
            rewritten_media_lines.append("a=end-of-candidates")
            continue

        rewritten_media_lines.append(line)

    if not saw_end_of_candidates:
        rewritten_media_lines.extend(rewritten_candidates)
        rewritten_media_lines.append("a=end-of-candidates")

    rewritten_lines = lines[:media_start] + rewritten_media_lines + lines[media_end:]
    return "\r\n".join(rewritten_lines) + "\r\n"
