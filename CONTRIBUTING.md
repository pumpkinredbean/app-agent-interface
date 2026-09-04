# Contributing

App Agent Interface is experimental and welcomes focused fixes, documentation improvements, and small feature proposals.

## Before opening a change

- Open an issue before changing the wire protocol or public Kotlin API.
- Keep the SDK domain-neutral. Business-specific tools belong in applications or examples.
- Keep model-visible tool definitions limited to `name`, `description`, and `inputSchema`.
- Do not add unrestricted device control or bind Android services to a public network interface.
- Do not commit credentials, `.env` files, Android keystores, APKs, build outputs, or model-provider session data.

## Documentation conventions

- Write public documentation, sample UI copy, prompts, and test fixtures in English.
- Use fenced Mermaid diagrams for architecture, execution flows, sequences, and state transitions. Do not use ASCII-art diagrams.
- Use Markdown tables or lists for inventories and exact value mappings that are not diagrams.
- Run `npm run docs:check` to verify local links, language, diagram fences, release versions, and example dependency versions.

## Development requirements

- JDK 17 or newer
- Node.js 22 or newer
- Android SDK 35 for Android builds

Run the relevant checks before opening a pull request:

```bash
./gradlew clean test \
  :app-agent-ui-compose:assembleRelease \
  :app-agent-inspector:assembleRelease \
  :app-agent-inspector-noop:assembleRelease

npm ci
npm run docs:check
npm run lint
npm test
npm run pack:check

cd examples/weather-agent/android
./gradlew :app:assembleDebug :app:assembleRelease

cd ../../trading-agent/android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug
```

The UiAutomator2 companion can be built from the repository root with:

```bash
examples/weather-agent/android/gradlew \
  -p drivers/android-uiautomator2 \
  :host:assembleDebug \
  :host:assembleDebugAndroidTest
```

By contributing, you agree that your contribution is licensed under the Apache License 2.0.
