# Loop Browser

Standalone Android WebView browser prototype. This app is developed independently and is not based on another browser project.

## Current features
- URL/search field, Go, Back, Reload and manual Capture.
- HTTP/HTTPS navigation only.
- JavaScript and DOM storage enabled; local file/content access disabled.
- Manual visible-WebView PNG screenshot saved in app-specific external storage under `captures/`.
- Loopback bridge on `127.0.0.1:8789` for Termux MCP control and state/screenshot retrieval.
- AI Action Loop step endpoint: one validated action per request, with action status exposed in browser state.

## Build
Use the Android APK Build GitHub Actions workflow in this repository. This subproject requires JDK 17 and Android SDK 35.

- Application ID: `com.pcontrol.loopbrowser`
- Minimum API: 26
- Version: 0.1.0

## Not implemented yet
- External AI model/provider integration (the app does not invent decisions or call a paid API).
- Full DOM/accessibility snapshot contract; current state endpoint provides URL/title/navigation telemetry and screenshot endpoint provides the viewport PNG.

## Safety
Web content is untrusted. Future automation must use allowlisted actions, authenticated loopback-only communication, a visible stop control, action/time limits, and confirmation for sensitive operations. Never bypass CAPTCHA or access controls.

## Touch interaction controls

The loopback bridge now supports WebView touch gestures without an external AI/API:
- `POST /v1/browser/tap` JSON: `{"x":500,"y":400}`
- `POST /v1/browser/swipe` JSON: `{"x1":500,"y1":900,"x2":500,"y2":300,"durationMs":350}`
- `POST /v1/browser/scroll` JSON: `{"direction":"down","amount":500}` (direction is page scroll direction; amount is 80..1200 pixels)

Coordinates are viewport-local pixels relative to the WebView content, not the Android status bar or browser toolbar. Take a fresh screenshot before choosing coordinates. Tap/swipe coordinates are bounds-checked; swipe duration is limited to 80..2000 ms. A queued response means the gesture was accepted, not that the target responded; capture a new screenshot/state afterward to verify the result. These endpoints dispatch touch events to the existing WebView; they do not bypass site protections.

Example:
```sh
curl -sS -X POST http://127.0.0.1:8789/v1/browser/tap -H 'Content-Type: application/json' -d '{"x":500,"y":400}'
curl -sS -X POST http://127.0.0.1:8789/v1/browser/swipe -H 'Content-Type: application/json' -d '{"x1":500,"y1":900,"x2":500,"y2":300,"durationMs":350}'
curl -sS -X POST http://127.0.0.1:8789/v1/browser/scroll -H 'Content-Type: application/json' -d '{"direction":"down","amount":500}'
```

## AI Action Loop prototype (Stage 2)

The app exposes `POST /v1/browser/agent/step` on the loopback bridge. It accepts exactly one allowlisted action per request; it does not accept arbitrary JavaScript, CSS selectors, shell commands, or batches.

Supported JSON actions:
- `{"action":"observe"}`
- `{"action":"navigate","url":"https://example.com"}`
- `{"action":"back"}`, `{"action":"forward"}`, `{"action":"reload"}`
- `{"action":"capture"}`
- `{"action":"desktop_on"}`, `{"action":"desktop_off"}`
- `{"action":"stop"}`

After an action, poll `GET /v1/browser/state` and verify `agentLastAction`, `agentLastActionStatus`, `url`, `title`, `loading`, `navigationStatus`, and `lastCaptureOk`. An accepted/queued response is not proof of completion. The external controller is responsible for reading state/screenshot, choosing the next step, enforcing a maximum step count and stopping on errors. This is a safe action-loop interface, not yet an integrated AI model.

Example:
```sh
curl -sS http://127.0.0.1:8789/v1/browser/state
curl -sS -X POST http://127.0.0.1:8789/v1/browser/agent/step -H 'Content-Type: application/json' -d '{"action":"observe"}'
curl -sS http://127.0.0.1:8789/v1/browser/state
```

### Focused-field text entry

- POST /v1/browser/type with JSON {"text":"plain text"} inserts up to 4,000 characters into the currently focused input, textarea, or contenteditable element.
- Tap the desired field first with /v1/browser/tap; this endpoint does not choose fields, execute caller-supplied JavaScript, or submit forms.
- The bridge reports the field type and inserted character count only; it never returns the field contents.
- Use only on pages and accounts you are authorized to access. CAPTCHA solving and automatic login submission are not provided.
