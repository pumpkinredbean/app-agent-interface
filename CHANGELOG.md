# Changelog

All notable changes will be documented in this file. The project follows [Semantic Versioning](https://semver.org/) once public artifacts are released.

## Unreleased

- Added the framework-neutral `app-agent-ui` frame contract, model/UI result
  separation, tag-and-parameter navigation, and turn-scoped lifecycle.
- Added a Compose frame host and application-owned approval-content overload
  while retaining the SDK FIFO queue and presentation timeout.
- Added a native trading-service example with public market data fallback,
  interactive context frames, local-only simulated orders, and an app-owned
  secure keypad without Android device authentication.

## 0.3.0 - 2026-09-02

- Added typed `ToolRuntime` audit lifecycle events with failure isolation and detachable listeners.
- Added debug-only Calls, Approvals, lifecycle, and expandable request/response payload inspection.
- Added a foreground-only shake entry point and a bounded in-memory audit store.
- Added an API-compatible `app-agent-inspector-noop` artifact for release variants.

## 0.2.0 - 2026-09-02

- Added an application-scoped FIFO `ToolApprovalController` with configurable queue capacity and presentation-only decision timeout.
- Added a lifecycle-aware Compose approval host, queue count, countdown, and privacy-safe background notification bridge.
- Added distinct denied, timed-out, and unavailable approval results while retaining the Boolean approval adapters.
- Documented secure-input boundaries, fail-closed process death, approval testing, and troubleshooting.
- Added the typed `@pumpkinredbean/app-agent-client` protocol client.
- Converted the generic Android UiAutomator2 package to strict TypeScript and separated bridge, hierarchy, and tool layers.
- Converted the Weather desktop agent, provider adapters, CLI, and web server to strict TypeScript.
- Reorganized the repository around publishable packages with one npm workspace and a language-neutral protocol root.

## 0.1.1 - 2026-09-01

- Added the optional `app-agent-ui-compose` Material 3 approval dialog and Roborazzi preview coverage.

## 0.1.0

- Initial public release of the Kotlin runtime and loopback transport.
