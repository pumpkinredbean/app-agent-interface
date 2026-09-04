# Weather agent example

A runnable example that integrates the domain-neutral [App Agent Interface Kotlin SDK](../../docs/sdk/kotlin-sdk.md) into an Android weather application. A custom TypeScript desktop agent calls application-scoped tools through `@pumpkinredbean/app-agent-client` over ADB, and an in-app caller executes the same registered handlers directly. This example is not MCP. It can optionally add generic, app-scoped UiAutomator2 tools as a fallback when no semantic application tool covers an interaction.

Weather is only the sample domain. Tool names, descriptions, schemas, handlers, persistence, and approval policy belong to this application and are not predefined by the SDK.

## What the example proves

```text
Desktop Web Chat UI (127.0.0.1:4317)
  |
  v
Node.js custom agent harness
  |- provider adapters
  |    |- Codex: Vercel AI SDK -> local Codex subscription provider
  |    |- Copilot: Vercel AI SDK -> Copilot SDK/CLI subscription provider
  |    `- Claude: official Claude Agent SDK adapter
  |- optional mobile_* tools -> semantic projector -> UiAutomator2 companion :18766
  `- app-agent/1 -> Mac :18765 -> adb forward -> Kotlin ToolRuntime
       |- Open-Meteo read handlers
       `- app-owned alert handlers -> SDK approval queue -> local persistence

Android in-app button -> the same ToolRuntime execution path
```

The provider receives the capability manifest, conversation history, and ToolResults from the current agent turn. It receives neither the Android endpoint nor application credentials. On every model step it may answer directly or request one or more tools. The desktop harness validates every requested tool and its arguments before sending normalized ToolCalls to Android; Android validates again at the execution boundary.

The harness is an event-driven agent loop, not a fixed pipeline:

```text
user message
  -> model step
       |- final text --------------------------> done
       `- zero or more independent ToolCalls
            -> Android ToolResults
            -> next model step ----------------> ...
```

The loop allows a direct response with no mobile execution, multiple ToolCalls, and additional model steps after results. It stops after eight model steps or twelve ToolCalls to prevent runaway execution.

## Registered application tools

| Tool | Purpose | Runtime approval |
|---|---|---|
| `weather_get_current` | Current conditions for one named location | None |
| `weather_get_forecast` | One-to-seven-day forecast for one named location | None |
| `weather_compare_locations` | Current-condition comparison for two to four locations | None |
| `weather_alert_list` | App-owned local alert rules | None |
| `weather_alert_create` | Create a one-time forecast rule | Mobile |
| `weather_alert_delete` | Delete one local rule by `alertId` | Mobile |

Every model-visible entry contains exactly `name`, `description`, and `inputSchema`. The two approval values in the table are runtime registration policy and never appear in the capability manifest.

When its separate companion is running, the harness appends generic `mobile_observe`, screenshot, tap, ordered multi-tap, long-press, text, swipe, restricted-key, and wait tools. These are harness capabilities, not weather tools and not Kotlin SDK defaults. `mobile_observe` returns separate flat `content` and `controls` arrays; raw XML and hierarchy depth never enter model context. See [Android UI automation fallback](../../docs/sdk/android-ui-automation.md).

The write tools do not attempt to write to Open-Meteo. They change state owned by the Android app:

- rules are persisted in `SharedPreferences`;
- the foreground service evaluates scheduled rules every 15 minutes while it is running;
- a newly created rule is also evaluated immediately;
- a matching rule becomes `TRIGGERED` and posts an Android notification;
- create and delete handlers run only after the Activity approves the exact tool name, input, and call ID.
- concurrent approvals use one FIFO dialog; only the currently displayed request consumes its 60-second decision window;
- when the Activity is not visible, one generic notification returns the user to the approval host.

This scheduler is intentionally small enough to audit in a sample. A production app should use its normal durable job system, define reboot and battery behavior, and test target-SDK foreground-service limits.

## SDK integration

The Android project consumes the repository root as a composite build:

```groovy
includeBuild("../../..")
```

```groovy
implementation "io.github.pumpkinredbean:app-agent-runtime:0.4.0-SNAPSHOT"
implementation "io.github.pumpkinredbean:app-agent-transport-loopback:0.4.0-SNAPSHOT"
implementation "io.github.pumpkinredbean:app-agent-ui-compose:0.4.0-SNAPSHOT"
debugImplementation "io.github.pumpkinredbean:app-agent-inspector:0.4.0-SNAPSHOT"
releaseImplementation "io.github.pumpkinredbean:app-agent-inspector-noop:0.4.0-SNAPSHOT"
```

[`AgentToolServer.java`](android/app/src/main/java/io/github/pumpkinredbean/appagent/example/weather/AgentToolServer.java) shows Java interoperability. [`AgentToolService.java`](android/app/src/main/java/io/github/pumpkinredbean/appagent/example/weather/AgentToolService.java) installs the same Inspector API from common source code; debug builds get the real audit UI, while release builds resolve the no-op artifact. The SDK owns registration, portable input validation, FIFO approval coordination, the optional Compose approval host, protocol encoding, and loopback HTTP transport. The application owns Open-Meteo mapping, local alert state, service lifecycle, and notification navigation.

## Run Android

Requirements:

- Android SDK 35;
- JDK 17 or newer;
- `adb`;
- an emulator or USB-debugging-enabled device.

```bash
cd examples/weather-agent/android
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :app:assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.pumpkinredbean.appagent.example.weather/.MainActivity
adb forward tcp:18765 tcp:8765
```

Use the actual SDK location for `ANDROID_HOME`. A Homebrew command-line-tools installation may use `/opt/homebrew/share/android-commandlinetools`.

Inspect the connection without a model:

```bash
curl http://127.0.0.1:18765/healthz
curl http://127.0.0.1:18765/v1/tools | jq

curl -X POST http://127.0.0.1:18765/v1/tool-calls \
  -H 'Content-Type: application/json' \
  -d '{
    "protocol":"app-agent/1",
    "type":"tool.call",
    "id":"manual-current-1",
    "name":"weather_get_current",
    "input":{"location":"Seoul"}
  }' | jq
```

The sample omits a bearer token so the PoC connection is easy to inspect. A production application must install a pairing-session token.

## Run the desktop chat UI

Node.js 22 or newer is required.

```bash
# From the repository root
npm ci
cd examples/weather-agent/desktop
npm run ui
```

The semantic weather tools work without the UI companion. To enable the optional UiAutomator2 fallback, keep this command running in another terminal before starting the chat UI:

```bash
npm run ui:driver:build
npm run ui:driver:install
npm run ui:driver -- start --target=io.github.pumpkinredbean.appagent.example.weather
```

Open `http://127.0.0.1:4317`. The UI shows:

- the connected Android app, registered app tool count, and optional UiAutomator2 driver state;
- provider selection and an editable model field;
- multi-turn chat;
- direct answers with no fabricated mobile execution;
- one card for each ToolCall that actually occurs, including arguments, mobile call ID, result summary, and duration;
- multiple ToolCalls and repeated model/tool steps when the request needs them;
- the same call ID in the Android execution timeline;
- an Android approval dialog when the model requests a write tool.

Try these requests:

```text
Which application tools can you use, and when would you use each one?
What is the weather in Seoul right now?
Compare the current weather in Seoul and Tokyo.
Show Seoul's three-day forecast.
Alert me if precipitation probability in Seoul reaches 70% tomorrow.
Which weather alerts do I have?
```

Use a date within the next 16 days for alert creation.

## Model providers

| Provider | Execution path | Authentication | Current verification |
|---|---|---|---|
| Codex | Vercel AI SDK -> `ai-sdk-provider-codex-cli` -> local Codex CLI | `codex login` | Real Android path supported |
| GitHub Copilot | Vercel AI SDK -> `ai-sdk-provider-github` -> Copilot SDK/CLI | Copilot login | Real Android path supported |
| Claude | Claude Agent SDK `query()` | `ANTHROPIC_API_KEY` | Adapter tests only |

```bash
# Codex
codex login status
npm run models:codex
npm run demo -- --provider=codex "Weather in Seoul now"

# GitHub Copilot
./node_modules/.bin/copilot login
npm run demo -- --provider=copilot --model=auto "Compare Seoul and Tokyo weather"

# Claude
export ANTHROPIC_API_KEY=...
npm run demo -- --provider=claude "Show Seoul's three-day forecast"
```

The harness gives these provider adapters no coding, shell, network, or filesystem tools. Each model step returns either final text or up to four available ToolCalls. App-defined calls use `app-agent/1`; optional `mobile_*` calls use the separate scoped driver. Both return the same normalized ToolResult envelope to the next model step. The complete turn is capped at eight model steps and twelve ToolCalls.

## Open-Meteo use

The Android handlers resolve place names through the Open-Meteo Geocoding API and request current and daily data from the Forecast API. The PoC uses the free non-commercial endpoint with no API key and displays the required attribution. Open-Meteo data are offered under CC BY 4.0.

Before shipping a commercial product, configure the appropriate commercial or self-hosted endpoint and review current terms, rate limits, attribution, and upstream data-source licences. See the [Open-Meteo API documentation](https://open-meteo.com/en/docs), [licence](https://open-meteo.com/en/license), and [terms](https://open-meteo.com/en/terms).

## Tests

```bash
# From the repository root
npm test
./gradlew test
```

Desktop tests verify direct responses with zero ToolCalls, multiple ToolCalls, repeated model steps, provider-output normalization, rejection of unknown tools, JSON Schema argument validation, protocol calls, tool-aware conversation history, UI event streaming, generic Android/Compose/WebView semantic projection, stale snapshots, ordered multi-position taps, and image-part handling without making provider account calls.

## Security boundary

- The Android server binds only to `127.0.0.1`.
- App-defined semantic tools remain the primary interface. If the optional UI driver is enabled, model actions are restricted to the target package fixed at driver startup.
- The UI catalog does not expose Home, Recents, notifications, arbitrary keycodes, package switching, or a model-controlled package name.
- The model never decides whether mobile approval can be skipped.
- Write handlers are not entered after denial, cancellation, timeout, or unavailable UI.
- The APK does not bundle ngrok and never binds the server to an external interface.
- Remote access requires a separate authenticated relay transport.

See [Android integration](../../docs/sdk/android-integration.md) for production guidance.
