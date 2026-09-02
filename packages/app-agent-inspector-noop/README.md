# `app-agent-inspector-noop`

API-compatible release counterpart for `app-agent-inspector`. It exists so
application `src/main` code can call `AppAgentInspector.install(...)` while
release variants contain no inspector Activity, audit collector, Compose UI, or
shake listener.

```kotlin
dependencies {
    debugImplementation("io.github.pumpkinredbean:app-agent-inspector:<version>")
    releaseImplementation("io.github.pumpkinredbean:app-agent-inspector-noop:<version>")
}
```
