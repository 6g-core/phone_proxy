from __future__ import annotations

import argparse
import json
import logging

from .client import RtcClientApp
from .config import ClientConfig


def add_config_argument(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--config",
        default="config.example.json",
        help="Path to the client JSON config file.",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Phone Proxy RTC client scaffold")
    subparsers = parser.add_subparsers(dest="command", required=True)
    show_config_parser = subparsers.add_parser("show-config", help="Print the loaded client config.")
    add_config_argument(show_config_parser)

    health_parser = subparsers.add_parser("health", help="Call the configured server health endpoint.")
    add_config_argument(health_parser)

    connect_parser = subparsers.add_parser(
        "connect",
        help="Create a non-trickle WebRTC session and optionally record the remote video.",
    )
    add_config_argument(connect_parser)
    connect_parser.add_argument(
        "--record",
        help="Optional output path for recording the remote media, for example out.mp4.",
    )
    connect_parser.add_argument(
        "--record-seconds",
        type=float,
        default=10.0,
        help="How long to keep recording after the peer connection reaches connected state.",
    )
    return parser


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s [rtc-client] %(name)s %(message)s",
    )


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    config = ClientConfig.load(args.config)
    configure_logging(config.log_level)
    app = RtcClientApp(config)

    if args.command == "show-config":
        print(app.show_config())
        return

    if args.command == "health":
        result = app.health_check()
        print(json.dumps({"status": result.status, "payload": result.payload}, indent=2, ensure_ascii=False))
        return

    if args.command == "connect":
        if args.record:
            result = app.connect_and_record(args.record, record_seconds=args.record_seconds)
        else:
            result = app.connect()
        print(
            json.dumps(
                {
                    "sessionId": result.session_id,
                    "connectionState": result.connection_state,
                    "iceGatheringState": result.ice_gathering_state,
                    "remoteTracks": result.remote_tracks,
                    "answerSummary": result.answer_summary,
                    "localCandidates": result.local_candidates,
                    "selectedCandidatePairs": result.selected_candidate_pairs,
                    "recording": result.recording,
                },
                indent=2,
                ensure_ascii=False,
            )
        )
        return

    parser.error(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    main()
