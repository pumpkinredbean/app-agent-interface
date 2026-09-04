# Application-defined UI frames

`app-agent-ui` is an optional, UI-framework-neutral module for tools that need to return both model-readable data and an application-defined interface.

It does not generate UI, ship a component catalog, or add UI fields to `ToolSpec`. The application owns every frame tag, payload, renderer, and visual design.

## The contract

A frame has only three serialized fields:

```kotlin
data class UiFrame(
    val tag: String,
    val params: JsonObject,
    val payload: JsonElement,
)
```

| Field | Purpose |
|---|---|
| `tag` | Opaque application-defined renderer key, such as `portfolio.positions.v1` |
| `params` | Small values that identify this frame in navigation, such as `symbol=BTC` |
| `payload` | Data required by the registered renderer |

`tag + canonical(params)` is the frame identity. Parameter names are sorted and names and values are RFC 3986 percent-encoded. Payload does not participate in identity, so live data can update without creating another navigation entry.

Keep connection objects, access tokens, cookies, passkey assertions, PINs, and secure-keypad values out of both `params` and `payload`. A renderer should recreate subscriptions through application-owned services when it resumes.

## Return a frame from a tool

Add the optional module:

```kotlin
dependencies {
    implementation("io.github.pumpkinredbean:app-agent-runtime:<version>")
    implementation("io.github.pumpkinredbean:app-agent-ui:<version>")
}
```

Use the same three-field `ToolSpec` as every other tool:

```kotlin
runtime.registerUi(
    spec = ToolSpec(
        name = "visualize_portfolio",
        description =
            "Opens an interactive view of the signed-in user's positions. " +
            "Use it when the user explicitly asks to view or explore the portfolio.",
        inputSchema = ToolJson.parseObject(
            """
            {
              "type": "object",
              "properties": {},
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
    ),
) { _, _ ->
    val positions = portfolioRepository.getPositions()
    UiToolOutput(
        modelOutput = buildJsonObject {
            put("positionCount", positions.size)
            put("summary", "Portfolio view opened")
        },
        frame = UiFrame(
            tag = "portfolio.positions.v1",
            params = buildJsonObject {},
            payload = portfolioFrameJson(positions),
        ),
    )
}
```

`modelOutput` is the concise value returned to the model. `frame` is sent to the host renderer. This separation avoids placing presentation payloads in model context.

Ordinary handlers and ordinary JSON results remain valid. Applications only use `registerUi` for tools that intentionally produce a frame.

## Existing protocol compatibility

No `app-agent/1` message or schema changes. `ToolResult.output` already accepts any JSON value, so `app-agent-ui` uses a marked envelope inside it:

```json
{
  "type": "app-agent.ui-frame/1",
  "modelOutput": {
    "positionCount": 3,
    "summary": "Portfolio view opened"
  },
  "frame": {
    "tag": "portfolio.positions.v1",
    "params": {},
    "payload": {
      "cash": 100000,
      "positions": []
    }
  }
}
```

A compatible harness splits the result before continuing the model turn:

```kotlin
val output = checkNotNull(result.output)
val modelOutput = UiToolOutputCodec.modelOutput(output)
val frame = UiToolOutputCodec.frame(output)

if (frame != null) {
    navigator.navigate(frame, UiFrameNavigationMode.PUSH)
}

continueModelTurn(modelOutput)
```

`decodeOrNull` returns `null` for any ordinary result. It rejects malformed values that claim the `app-agent.ui-frame/1` type.

## Register application renderers

The SDK registry contains no Compose, View, web, or domain dependency. The host chooses its renderer type:

```kotlin
interface AppFrameRenderer {
    @Composable
    fun Content(frame: UiFrame)
}

val renderers = UiFrameRendererRegistry<AppFrameRenderer>()

renderers.register("portfolio.positions.v1", PortfolioPositionsRenderer())
renderers.register("market.asset.v1", MarketAssetRenderer())

val renderer = renderers.require(navigator.current ?: return)
renderer.Content(navigator.current!!)
```

Register exact tags. If a payload change is not backward compatible, use a new tag version and keep the old renderer while older clients may still receive it.

### Optional Compose host

Compose applications can add the small platform adapter instead of wiring navigator observations by hand:

```kotlin
dependencies {
    implementation("io.github.pumpkinredbean:app-agent-ui-compose:<version>")
}
```

The application still supplies the tag registry and every Composable:

```kotlin
val renderers = remember {
    UiFrameRendererRegistry<ComposeUiFrameRenderer>().apply {
        register(
            "portfolio.positions.v1",
            ComposeUiFrameRenderer { frame, lifecycle, modifier ->
                PortfolioPositionsFrame(
                    payload = frame.payload,
                    live = lifecycle == UiFrameLifecycleState.RESUMED,
                    modifier = modifier,
                )
            },
        )
    }
}

UiFrameHost(
    navigator = navigator,
    renderers = renderers,
    modifier = Modifier.fillMaxSize(),
)
```

`UiFrameHost` observes the framework-neutral navigator, dispatches the current lifecycle state to the renderer, animates push/back transitions, and can handle Android system back. It does not create a second navigation stack. Set `handleSystemBack = false` when the surrounding screen owns back dispatch.

The adapter intentionally does not depend on Circuit, Navigation Compose, or another screen framework. A consuming app can host `UiFrameHost` inside any of them, while frame identity and turn lifecycle stay portable to desktop and non-Compose renderers.

## Navigation semantics

`UiFrameNavigator` is a stack for the service UI surface, independent of the conversation list.

- `PUSH` adds a new identity to the top.
- Pushing an identity already in the stack disposes its older instance, removes it from its previous position, and moves the new frame to the top. Stack depth does not grow.
- `REPLACE` replaces the top without creating another back step.
- `back()` disposes the top and resumes the previous frame.
- `clear()` or `close()` disposes every frame.

Use `PUSH` when a result represents a new service destination. Use `REPLACE` for an update that should not create a back step, such as changing a filter inside the current component.

`params` should contain only identity-bearing values. For example:

```text
market.asset.v1?symbol=BTC
market.asset.v1?symbol=ETH
portfolio.return.v1?period=today
```

A changing price belongs in `payload`, not `params`, because each tick is not a new navigation destination.

## Turn lifecycle

Lifecycle is host runtime state; it is not serialized into `UiFrame`.

| Event | Host responsibility |
|---|---|
| `RESUMED` | Start or recreate frame-owned live work |
| `PAUSED` | Stop live work and retain the last renderable snapshot |
| `DISPOSED` | Release the frame permanently |

Before starting every new user turn, pause the current frame:

```kotlin
navigator.onTurnStarted()
agent.submit(userMessage)
```

When a tool returns the next frame, `navigate` resumes it. A host can also call `resumeCurrent()` when the user explicitly resumes a paused live component.

Lifecycle callbacks are synchronous and should be coordinated from one host event thread. Callback implementations should be idempotent because platform lifecycle events and user actions may request the same state more than once.

## Ownership boundary

The SDK provides:

- the minimal frame value;
- the result envelope and codec;
- navigation identity and stack behavior;
- `paused`, `resumed`, and `disposed` events;
- an exact tag-to-renderer registry.

The application or agent client provides:

- frame tags and payload schemas;
- Compose, View, web, or other renderers;
- animations, layout, and design system;
- live data subscription implementation;
- persistence policy;
- the surrounding conversation UI;
- user-facing resume and back controls.

This boundary lets desktop and in-app agents render the same semantic frame contract without forcing either client into a shared UI framework.

See the [native trading-agent example](../../examples/trading-agent/README.md) for live market polling that runs only while a frame is `RESUMED`, an app-owned renderer registry, conversation/context focus switching, and a device-approved local order.
