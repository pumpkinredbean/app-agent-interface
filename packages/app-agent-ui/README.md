# `app-agent-ui`

Framework-neutral UI frames for application-defined tools. This module adds no
domain tools or visual component catalog.

A UI-producing tool returns two separate values:

- concise `modelOutput` for the next model step;
- an application-owned `UiFrame(tag, params, payload)` for a renderer.

```kotlin
runtime.registerUi(spec) { input, _ ->
    UiToolOutput(
        modelOutput = buildJsonObject { put("summary", "Portfolio opened") },
        frame = UiFrame(
            tag = "portfolio.positions.v1",
            params = buildJsonObject {},
            payload = loadPortfolioPayload(input),
        ),
    )
}
```

`UiFrameNavigator` provides a deduplicated `tag + params` back stack and
`PAUSED`, `RESUMED`, and `DISPOSED` lifecycle events. Call
`onTurnStarted()` before a new agent turn so live frame resources pause until
the user resumes them or another frame is produced.

The optional `app-agent-ui-compose` artifact adapts this contract to Compose.
See [Application-defined UI frames](../../docs/sdk/ui-frames.md).
