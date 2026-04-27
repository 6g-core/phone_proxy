#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLIENT_DIR="$ROOT_DIR/client"
SERVER_DIR="$ROOT_DIR/server"
SERVER_CFG="$(mktemp /tmp/rtc_server_loopback.XXXXXX.json)"
CLIENT_CFG="$(mktemp /tmp/rtc_client_loopback.XXXXXX.json)"
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]]; then
    kill "$SERVER_PID" >/dev/null 2>&1 || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f "$SERVER_CFG" "$CLIENT_CFG"
}
trap cleanup EXIT

cat > "$SERVER_CFG" <<'EOF'
{
  "signal_listen_host": "127.0.0.1",
  "signal_listen_port": 18080,
  "advertised_host": "127.0.0.1",
  "udp_port_start": 40000,
  "udp_port_end": 40003,
  "ice_bind_host": "127.0.0.1",
  "session_ttl_seconds": 60,
  "ice_gather_timeout_ms": 8000,
  "log_level": "INFO"
}
EOF

cat > "$CLIENT_CFG" <<'EOF'
{
  "offer_url": "http://127.0.0.1:18080/api/v1/webrtc/offer",
  "health_url": "http://127.0.0.1:18080/health",
  "session_id": "loopback-smoke",
  "ice_gather_timeout_ms": 8000,
  "connection_wait_timeout_ms": 15000,
  "media_kind": "video",
  "log_level": "INFO"
}
EOF

bash "$SERVER_DIR/scripts/run_server.sh" --config "$SERVER_CFG" >/tmp/rtc_server_loopback.log 2>&1 &
SERVER_PID="$!"

for _attempt in $(seq 1 30); do
  if bash "$CLIENT_DIR/scripts/run_client.sh" health --config "$CLIENT_CFG" >/tmp/rtc_client_health.log 2>&1; then
    break
  fi
  sleep 1
done

echo "=== health ==="
cat /tmp/rtc_client_health.log
echo
echo "=== connect ==="
bash "$CLIENT_DIR/scripts/run_client.sh" connect --config "$CLIENT_CFG"
