#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT_DIR/android"
TOOLCHAIN_DIR="$ANDROID_DIR/.local"
ADB_BIN="$TOOLCHAIN_DIR/android-sdk/platform-tools/adb"
APK_PATH="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -x "$ADB_BIN" ]]; then
  echo "adb not found; run scripts/install_android_toolchain.sh first" >&2
  exit 1
fi

if [[ ! -f "$APK_PATH" ]]; then
  echo "APK not found at $APK_PATH; run scripts/build_android_app.sh first" >&2
  exit 1
fi

"$ADB_BIN" start-server >/dev/null
"$ADB_BIN" devices
"$ADB_BIN" install -r -g "$APK_PATH"
