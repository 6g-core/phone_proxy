# Phone Voice

`phone_voice/` is a standalone Android app that exposes `POST /api/v1/word2voice` on the phone's LAN IP and speaks the incoming JSON `text` value with Android `TextToSpeech`.

This first implementation focuses on the text-to-speech contract. The alternative "upload audio bytes and play them directly" flow is possible too, but it is not part of this version's HTTP API yet.

## Endpoint

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
  "text": "你好，请到门口取快递"
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
2. Tap `Start Service`.
3. Confirm the app shows a LAN URL such as `http://192.168.1.23:8080/api/v1/word2voice`.
4. Send a request from another device on the same LAN:

```bash
curl -X POST http://<phone-ip>:8080/api/v1/word2voice \
  -H "Content-Type: application/json" \
  -d '{"text":"你好，请到门口取快递"}'
```

The phone should speak the text immediately.

## Notes

- `GET /api/v1/word2voice` returns `405`.
- Blank text returns `400`.
- If Android `TextToSpeech` is not ready yet, the endpoint returns `503`.
