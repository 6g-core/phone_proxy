#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ ! -x "$ROOT_DIR/.venv/bin/python" ]]; then
  python3 -m virtualenv "$ROOT_DIR/.venv"
fi

"$ROOT_DIR/.venv/bin/pip" install -r "$ROOT_DIR/requirements-runtime.txt"
