from __future__ import annotations

import argparse
import json

from .config import ServerConfig


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Phone Proxy RTC server scaffold")
    parser.add_argument(
        "--config",
        default="config.example.json",
        help="Path to the server JSON config file.",
    )
    parser.add_argument(
        "--print-config",
        action="store_true",
        help="Print the loaded config and exit.",
    )
    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    config = ServerConfig.load(args.config)

    if args.print_config:
        print(json.dumps(config.to_dict(), indent=2, ensure_ascii=False))
        return

    from .service import run_server

    run_server(config)


if __name__ == "__main__":
    main()
