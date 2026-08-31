# Migrating an inline application PoC to the SDK

The inline implementation was converted into the runnable example under [`examples/weather-agent`](../../examples/weather-agent/README.md). Its Android application consumes the Kotlin runtime and loopback transport from the repository-root composite build as real dependencies. Weather remains example-owned behavior and is not part of the SDK API.

## Tool specification

Before:

```json
{
  "name": "support.create_ticket",
  "description": "Create a support ticket",
  "whenToUse": "The user asks to contact support",
  "doNotUseWhen": "The user only asks for help content",
  "effect": "WRITE",
  "confirmation": "REQUIRED",
  "inputSchema": {}
}
```

After:

```json
{
  "name": "create_support_ticket",
  "description": "Creates a support ticket for the signed-in user. Use it when the user explicitly asks to contact support or submit an issue. Do not use it when the user only asks for help content.",
  "inputSchema": {
    "type": "object"
  }
}
```

Migration rules:

- merge the intent of `whenToUse` and `doNotUseWhen` into `description`;
- remove `effect` and `confirmation` from the model-visible definition;
- move approval policy to `runtime.register(..., approval = ToolApproval.MOBILE)`;
- replace `.` with `_` to satisfy the portable cross-provider naming rule.

## Protocol

| Initial PoC | v1 SDK |
|---|---|
| an application-specific prototype identifier | `app-agent/1` |
| `requestId` | `id` |
| `tool` | `name` |
| `arguments` | `input` |
| `result` | `output` |
| payload `sessionId` and `source` | harness or transport state |

`contractVersion` was removed. The protocol string itself versions the wire contract.

## Executor

The PoC's `switch(toolName)` and custom HTTP server were replaced with:

```kotlin
runtime.register(spec, approval, handler)
LoopbackToolServer(runtime).start()
```

Input validation runs in both the desktop harness and mobile runtime. Desktop validation provides early failure and detects provider mistakes. Mobile validation remains the security boundary because it is the final executor.
