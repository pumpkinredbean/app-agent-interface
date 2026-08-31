# App Agent Interface SDK

A provider-neutral Kotlin SDK for exposing application-scoped capabilities to desktop and in-app AI agents.

[![CI](https://github.com/pumpkinredbean/app-agent-interface/actions/workflows/ci.yml/badge.svg)](https://github.com/pumpkinredbean/app-agent-interface/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Applications define their own tools. The SDK registers, validates, approves, and executes those tools through the custom `app-agent/1` protocol. It does not expose the Android UI or operating system to the model.

> Status: experimental. The public API and wire protocol may change before `1.0.0`.

## Design principles

- This is a custom application-agent protocol, not MCP.
- The SDK contains no finance, commerce, or other domain-specific tools.
- Every application defines its own `name`, `description`, `inputSchema`, and handler.
- Desktop and in-app agents can share the same mobile `ToolRuntime`.
- Application credentials remain inside the mobile handler and are never sent to the model.
- Approval is runtime policy, not part of the model-visible tool definition.
- An optional harness-owned UiAutomator2 companion provides app-scoped screen interaction when no semantic application tool exists.

## Repository layout

```text
sdk/
  app-agent-runtime/                 Kotlin registry, validation, approval, and execution
  app-agent-transport-loopback/      Loopback HTTP transport for Android/JVM and ADB
  protocol/                          Canonical app-agent/1 JSON Schema
drivers/android-uiautomator2/        Optional generic instrumentation companion
harness/android-ui/                  Reusable desktop semantic projector and UI ToolSpecs
docs/sdk/                            Architecture and integration documentation
examples/weather-agent/              Android weather and desktop agent example
```

## Model-visible contract

```kotlin
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)
```

Handlers, approval policy, authentication state, transport settings, and call IDs are deliberately kept outside `ToolSpec`.

## Start here

- [Kotlin SDK](sdk/README.md)
- [Architecture](docs/sdk/architecture.md)
- [Tool authoring](docs/sdk/tool-authoring.md)
- [Android integration](docs/sdk/android-integration.md)
- [Protocol](docs/sdk/protocol.md)
- [Android UI automation fallback](docs/sdk/android-ui-automation.md)
- [Android UI harness package](harness/android-ui/README.md)
- [Distribution](docs/sdk/distribution.md)
- [Weather agent example](examples/weather-agent/README.md)

## Distribution

The repository includes a GitHub Packages publishing workflow. Pushing a tag such as `sdk-v0.1.0` publishes both modules:

```text
io.github.pumpkinredbean:app-agent-runtime:0.1.0
io.github.pumpkinredbean:app-agent-transport-loopback:0.1.0
```

See [SDK distribution and installation](docs/sdk/distribution.md) for credentials, release steps, and consumer configuration.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a change. Please report vulnerabilities through the process in [SECURITY.md](SECURITY.md), not a public issue.

## License

Licensed under the [Apache License 2.0](LICENSE).
