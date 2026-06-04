# Phone Voice

`phone_voice/` is a standalone Android app that exposes local HTTP endpoints on the phone's LAN IP. `POST /api/v1/word2voice` speaks the incoming JSON `text` value with Android `TextToSpeech`; `POST /api/v1/word2voice_forward` speaks `text` and forwards the incoming `arm_body` object to the configured forward target.

This first implementation focuses on the text-to-speech contract. The alternative "upload audio bytes and play them directly" flow is possible too, but it is not part of this version's HTTP API yet.

## Speech Endpoint

- Method: `POST`
- Path: `/api/v1/word2voice`
- Port: `8080`
- Content-Type: `application/json`

Example payload:

```json
{
  "text": "你好，请到门口取快递"
}
```

Successful response:

```json
{
  "success": true,
  "message": "accepted",
  "text": "你好，请到门口取快递",
  "speech": {
    "success": true,
    "message": "accepted"
  },
  "forward": {
    "success": true,
    "message": "forwardUrl is blank",
    "skipped": true
  }
}
```

## Build

The app reuses the repository-local Android toolchain in [`android/.local`](../android/.local).

```bash
cd /home/aicor/compute_netwok/phone_proxy/phone_voice
./scripts/test_unit.sh
./scripts/build_android_app.sh
```

## Install

```bash
cd /home/aicor/compute_netwok/phone_proxy/phone_voice
./scripts/install_android_app.sh
```

## Use On Device

1. Open the app.
2. Optionally enter a `Speech URL`, for example `http://10.45.0.10:8080/api/v1/word2voice`. Leave it blank to use the automatically detected LAN URL.
3. Optionally enter a `Speech + Forward URL`, for example `http://10.45.0.10:8080/api/v1/word2voice_forward`. Leave it blank to use the default path on the same host and port.
4. Optionally enter a `Forward Target URL`. Leave it blank to disable forwarding.
5. Optionally enter `Forward Timeout (ms)`. The default is `3000`.
6. Tap `Start Service`.
7. Confirm the app shows URLs such as `http://192.168.1.23:8080/api/v1/word2voice` and `http://192.168.1.23:8080/api/v1/word2voice_forward`.
8. Send a speech-only request from another device on the same LAN:

```bash
curl -X POST http://<phone-ip>:8080/api/v1/word2voice \
  -H "Content-Type: application/json" \
  -d '{"text":"你好，请到门口取快递"}'
```

The phone should speak the text immediately.

## Speech + Forward Endpoint

- Method: `POST`
- Default path: `/api/v1/word2voice_forward`
- Port: `8080`
- Content-Type: `application/json`

Example payload:

```json
{
  "text": "今天天气真好啊",
  "arm_body": {
    "target": "yellow bottle"
  }
}
```

Send a speech + forward request when the downstream service should be triggered:

```bash
curl -X POST http://<phone-ip>:8080/api/v1/word2voice_forward \
  -H "Content-Type: application/json" \
  -d '{"text":"今天天气真好啊","arm_body":{"target":"yellow bottle"}}'
```

If `Forward Target URL` is set, this endpoint also sends `arm_body` to the target:

```bash
curl -X POST http://10.45.0.2:18891/api/v1/exec \
  -H "Content-Type:application" \
  -d '{"target":"yellow bottle"}'
```

The `/api/v1/word2voice_forward` response contains separate `speech` and `forward` results. If speech fails, or if forwarding is configured and fails, the endpoint returns a failure response. The original `/api/v1/word2voice` endpoint is speech-only and never forwards.

Example failure response when forwarding is configured but the downstream request fails:

```json
{
  "success": false,
  "message": "forward failed",
  "text": "你好，请到门口取快递",
  "speech": {
    "success": true,
    "message": "accepted"
  },
  "forward": {
    "success": false,
    "message": "HTTP 500 from forwardUrl",
    "statusCode": 500,
    "skipped": false
  }
}
```

## Notes

- `GET /api/v1/word2voice` and `GET /api/v1/word2voice_forward` return `405`.
- Blank text returns `400`.
- If Android `TextToSpeech` is not ready yet, the endpoint returns `503`.
- If `Speech URL` is set, its host, port, and path are used for the local HTTP server. If it is blank or invalid, the app uses the automatic LAN URL.
- If `Speech + Forward URL` is set, its path is used for the speech + forward endpoint. If `Speech URL` is blank, its host and port are also used for the local HTTP server.
- Blank `Forward Target URL` skips forwarding and does not count as a failure.
- Blank or invalid `Forward Timeout (ms)` falls back to `3000`.
