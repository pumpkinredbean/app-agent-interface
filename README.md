# App Agent Interface

Provider-neutral Kotlin and TypeScript SDKs for turning an existing application into an application that agents can safely use.

[![CI](https://github.com/pumpkinredbean/app-agent-interface/actions/workflows/ci.yml/badge.svg)](https://github.com/pumpkinredbean/app-agent-interface/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Applications define their own tools. The Kotlin runtime registers, validates, approves, and executes those tools through the custom `app-agent/1` protocol. The TypeScript client discovers and calls them from desktop, remote, or other agent harnesses. Optional Android UI automation is a separate app-scoped fallback rather than the primary interface.

> Status: experimental. The public API and wire protocol may change before `1.0.0`.

## Design principles

- This is a custom application-agent protocol, not MCP.
- The packages contain no finance, commerce, weather, or other domain-specific tools.
- Every application defines its own `name`, `description`, `inputSchema`, and handler.
- Desktop and in-app agents can share the same mobile `ToolRuntime`.
- Application credentials remain inside the mobile handler and are never sent to the model.
- Approval is runtime policy, not part of the model-visible tool definition.
- An optional harness-owned UiAutomator2 companion provides app-scoped screen interaction when no semantic application tool exists.

## Repository layout

```text
protocol/                            Canonical, language-neutral app-agent/1 JSON Schema
packages/
  app-agent-runtime/                 Kotlin registry, validation, approval, and execution
  app-agent-transport-loopback/      Kotlin loopback HTTP transport for Android/JVM and ADB
  app-agent-ui-compose/              Optional approval queue UI and background notification
  app-agent-inspector/               Debug tool-call audit UI and foreground shake entry point
  app-agent-inspector-noop/          API-compatible release no-op for the Inspector
  app-agent-client/                  Typed TypeScript protocol client
  app-agent-android-ui/              Typed TypeScript Android UI fallback tools
drivers/android-uiautomator2/        Optional generic instrumentation companion
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

- [Kotlin SDK](docs/sdk/kotlin-sdk.md)
- [On-device approval](docs/sdk/on-device-approval.md)
- [Compose approval UI](docs/sdk/compose-approval-ui.md)
- [Debug audit inspector](docs/sdk/debug-inspector.md)
- [TypeScript client](packages/app-agent-client/README.md)
- [TypeScript package architecture](docs/sdk/typescript.md)
- [Architecture](docs/sdk/architecture.md)
- [Tool authoring](docs/sdk/tool-authoring.md)
- [Android integration](docs/sdk/android-integration.md)
- [Approval testing](docs/sdk/approval-testing.md)
- [Approval troubleshooting](docs/sdk/approval-troubleshooting.md)
- [Protocol](docs/sdk/protocol.md)
- [Android UI automation fallback](docs/sdk/android-ui-automation.md)
- [Android UI package](packages/app-agent-android-ui/README.md)
- [Distribution](docs/sdk/distribution.md)
- [Weather agent example](examples/weather-agent/README.md)

## Distribution

The repository includes a GitHub Packages publishing workflow. Pushing an SDK version tag publishes the Kotlin runtime, loopback transport, approval UI, and debug Inspector pair:

```text
io.github.pumpkinredbean:app-agent-runtime:<version>
io.github.pumpkinredbean:app-agent-transport-loopback:<version>
io.github.pumpkinredbean:app-agent-ui-compose:<version>
io.github.pumpkinredbean:app-agent-inspector:<version>
io.github.pumpkinredbean:app-agent-inspector-noop:<version>
```

The npm workspaces build publication-ready ESM packages with declarations:

```text
@pumpkinredbean/app-agent-client
@pumpkinredbean/app-agent-android-ui
```

See [SDK distribution and installation](docs/sdk/distribution.md) for credentials, release steps, and consumer configuration.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a change. Please report vulnerabilities through the process in [SECURITY.md](SECURITY.md), not a public issue.

## License

Licensed under the [Apache License 2.0](LICENSE).
