# Word2Voice LAN Android App Design

## Goal

Build an independent Android app under `phone_voice/` that exposes a LAN HTTP endpoint at `POST /api/v1/word2voice`. When the app receives a JSON body like `{"text":"你好，请到门口取快递"}`, it converts the text to speech with Android `TextToSpeech` and plays it immediately on the phone.

This first version is intentionally narrow:

- LAN access only
- Single endpoint
- JSON input with one required `text` field
- Immediate local playback on the phone
- No public internet access
- No authentication in the MVP
- No audio-file playback in the MVP

## Scope

### In Scope

- Create a standalone Android app project inside `phone_voice/`
- Run a lightweight embedded HTTP server inside the app
- Keep the server alive with a foreground service
- Expose `POST /api/v1/word2voice` on a fixed port
- Parse and validate the incoming JSON body
- Use Android `TextToSpeech` to speak the text in Chinese
- Show the current LAN URL, service state, and last received text in the UI

### Out of Scope

- Public internet access or NAT traversal
- Push messaging, WebSocket, or cloud relay
- Audio upload or streaming playback
- Speech queue persistence
- Start on boot
- Advanced speaker selection, volume routing, or voice tuning

## Recommended Technical Approach

Use a foreground service that owns two runtime dependencies:

- An embedded HTTP server implemented with `NanoHTTPD`
- A speech wrapper implemented with Android `TextToSpeech`

This is the best fit for the MVP because it keeps the API local to the phone, survives the app leaving the foreground better than an activity-only server, and keeps the implementation surface small.

## High-Level Architecture

The app is organized into five focused units:

1. `MainActivity`
Shows service status, current LAN URL, last request text, and start/stop controls.

2. `VoiceHttpForegroundService`
Runs as a foreground service, starts the HTTP server, owns the TTS lifecycle, and updates shared UI state.

3. `WordToVoiceServer`
Handles HTTP routing and request parsing. It only supports `POST /api/v1/word2voice`.

4. `TtsManager`
Initializes `TextToSpeech`, configures the language, exposes `speak(text)`, and shuts down cleanly.

5. `VoiceServiceStateStore`
Holds process-local observable state for the UI, including whether the service is running, the current IP/port, readiness, last text, and last error.

## Project Layout

The new standalone project lives in:

- `phone_voice/`

Suggested internal module layout:

- `phone_voice/app/src/main/java/com/example/phonevoice/MainActivity.kt`
- `phone_voice/app/src/main/java/com/example/phonevoice/service/VoiceHttpForegroundService.kt`
- `phone_voice/app/src/main/java/com/example/phonevoice/http/WordToVoiceServer.kt`
- `phone_voice/app/src/main/java/com/example/phonevoice/tts/TtsManager.kt`
- `phone_voice/app/src/main/java/com/example/phonevoice/state/VoiceServiceStateStore.kt`
- `phone_voice/app/src/main/java/com/example/phonevoice/net/LocalIpResolver.kt`

The default package and application id for the MVP will be `com.example.phonevoice`.

## Runtime Flow

### App Start

1. User opens the app.
2. `MainActivity` shows the current service status.
3. User taps Start.
4. The app starts `VoiceHttpForegroundService`.
5. The service creates a notification channel and enters foreground mode.
6. The service initializes `TtsManager`.
7. The service starts `WordToVoiceServer` on port `8080`.
8. The UI updates to show a LAN URL such as `http://192.168.1.23:8080/api/v1/word2voice`.

### Request Handling

1. A LAN client sends `POST /api/v1/word2voice`.
2. `WordToVoiceServer` validates method, path, content type, and request body.
3. The server parses the JSON and extracts `text`.
4. The server validates that `text` is present, non-blank, and within a defined size limit.
5. The service calls `TtsManager.speak(text)`.
6. `TtsManager` uses `TextToSpeech.QUEUE_FLUSH` so the newest request interrupts any current playback.
7. The server returns a JSON success response if the request is accepted.

## API Contract

### Request

- Method: `POST`
- Path: `/api/v1/word2voice`
- Content-Type: `application/json`

Example body:

```json
{"text":"你好，请到门口取快递"}
```

### Success Response

- Status: `200 OK`

```json
{"success":true,"message":"accepted","text":"你好，请到门口取快递"}
```

### Error Responses

- `400 Bad Request`
  - Invalid JSON
  - Missing `text`
  - Blank `text`
  - Text exceeds max length
- `404 Not Found`
  - Unsupported path
- `405 Method Not Allowed`
  - Path exists but request is not `POST`
- `503 Service Unavailable`
  - TTS engine not ready
  - Server service is up but speech is unavailable
- `500 Internal Server Error`
  - Unexpected runtime failure

Error response shape:

```json
{"success":false,"message":"human-readable error"}
```

## Validation Rules

The MVP enforces the following rules:

- `text` is required
- `text.trim()` must not be empty
- Maximum input length is `1000` characters
- Only one request is processed at a time for playback policy purposes
- A new request interrupts the currently playing speech

`1000` characters is large enough for announcements but small enough to avoid turning the endpoint into a long-form speech job in the first release.

## Foreground Service Design

The service is required because Android vendors, including VIVO/OriginOS, are aggressive about background process limits. The MVP will:

- Start in foreground mode with a persistent notification
- Show a clear notification title such as `Word2Voice service running`
- Keep the embedded HTTP server and TTS runtime inside the service
- Stop the HTTP server and shut down TTS when the user stops the service

This does not guarantee the OS will never reclaim the process, but it is the correct baseline for a LAN listener app.

## UI Design

The first screen stays intentionally simple:

- Service status: stopped, starting, running, error
- Current LAN URL
- TTS readiness state
- Last received text
- Start button
- Stop button

The UI does not need a settings page in the MVP. Port `8080` is fixed and not user-editable yet.

## Local IP Resolution

The app needs to display a reachable LAN URL. The MVP will:

- Scan active network interfaces
- Prefer a non-loopback site-local IPv4 address
- Fall back to a generic host display if no LAN IPv4 is found

The service listens on `0.0.0.0:8080`, but the UI shows a concrete IP when one is available.

## TTS Behavior

`TtsManager` is responsible for:

- Initializing Android `TextToSpeech`
- Setting preferred language to `Locale.CHINESE` or `Locale.SIMPLIFIED_CHINESE`
- Exposing readiness state
- Speaking text with `QUEUE_FLUSH`
- Releasing the engine on shutdown

If the TTS engine fails to initialize or does not support the requested language, the app remains running but returns `503` for speech requests and shows the failure in the UI.

## Dependency Choices

The MVP keeps dependencies small:

- UI: Jetpack Compose
- Embedded HTTP server: `NanoHTTPD`
- JSON parsing: `org.json.JSONObject`

`org.json` is enough for a single-field payload and avoids pulling in a larger serialization stack for the first version.

## Permissions and Manifest Requirements

The app will need:

- `android.permission.INTERNET`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.POST_NOTIFICATIONS` on Android 13+ for a visible foreground notification flow

The manifest also needs a foreground service declaration for `VoiceHttpForegroundService`.

## Failure Handling

The app handles failure in a user-visible way:

- If the service cannot bind the port, it transitions to error state and shows the reason
- If TTS initialization fails, the service still runs but the endpoint returns `503`
- If malformed input arrives, the app rejects it without crashing
- If playback fails unexpectedly, the app logs the event and returns a request-level error when possible

No request should crash the app process.

## Observability

The MVP will include lightweight observability only:

- Android logcat entries for service start/stop, server start, request receipt, validation errors, and TTS failures
- UI display of the last accepted text and current error state

No analytics or remote reporting is included.

## Testing Strategy

### Unit-Level Testing

- Validate JSON parsing and error mapping in the HTTP handler
- Validate request rules for missing or blank `text`
- Validate response status codes for wrong method and wrong path

### Manual Device Testing

On a real Android phone in the same LAN:

1. Install and open the app
2. Start the service
3. Confirm the UI shows a LAN URL
4. From another device, run:

```bash
curl -X POST http://<phone-ip>:8080/api/v1/word2voice \
  -H "Content-Type: application/json" \
  -d '{"text":"你好，请到门口取快递"}'
```

5. Confirm the phone speaks the text immediately
6. Confirm invalid requests return the correct error code

## Security Notes

The MVP intentionally does not include authentication because the user asked for the simplest LAN-only version. This means any device on the same LAN can trigger playback. A token-based header check should be the first hardening step after the basic flow is proven.

## Follow-On Extensions

Once the MVP is stable, the next useful extensions are:

- Add bearer token authentication
- Add a second endpoint for direct audio playback
- Add a simple request queue mode instead of always interrupting
- Add configurable port and speech settings
- Add boot-time restart support

## Final Decisions

The approved MVP design is:

- Standalone Android app in `phone_voice/`
- LAN-only access
- Foreground service host
- Embedded `NanoHTTPD` server
- Single endpoint: `POST /api/v1/word2voice`
- JSON request body with required `text`
- `TextToSpeech` playback with interruption-on-new-request behavior
- Fixed port `8080`
- Simple Compose UI for service control and status
