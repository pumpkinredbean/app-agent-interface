# AndroidX UiAutomator2 companion driver

This generic instrumentation APK exposes a small loopback HTTP bridge for hierarchy capture, screenshots, and scoped input injection. The target application package is supplied at instrumentation start; the module contains no sample-app selectors or domain rules.

It binds to device `127.0.0.1:8766`. A desktop process normally reaches it through `adb forward tcp:18766 tcp:8766`.

## Build

Any compatible Gradle wrapper can build this standalone project. The example manager uses the repository's wrapper:

```bash
cd examples/weather-agent/desktop
npm run ui:driver:build
npm run ui:driver:install
npm run ui:driver -- start --target=com.example.target
```

The target package must be installed and foregrounded before UI actions. See [Android UI automation fallback](../../docs/sdk/android-ui-automation.md) for the model-facing semantic projection and security boundaries.

## Device endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/healthz` | Driver, target package, foreground package, and display state |
| `GET` | `/v1/hierarchy` | Raw XML for the trusted desktop projector only |
| `GET` | `/v1/screenshot` | PNG capture while the target package remains foregrounded |
| `POST` | `/v1/actions` | Restricted tap, long-press, ordered multi-tap, text, swipe, and key actions |
| `POST` | `/shutdown` | Stop the instrumentation server |

The raw hierarchy endpoint is not a model tool. The desktop projector removes layout depth and returns the compact `content`/`controls` JSON contract.
