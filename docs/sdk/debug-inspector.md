# Debug audit inspector

`app-agent-inspector` provides an on-device view of the tool calls that
actually passed through one `ToolRuntime`. It is designed for development and
QA builds. It is not a model-facing tool, a transport, an approval UI, or a
durable compliance audit system.

## Variant-safe installation

Use the real implementation only in debug variants. Give release variants the
API-compatible no-op artifact:

```kotlin
dependencies {
    implementation("io.github.pumpkinredbean:app-agent-runtime:<version>")

    debugImplementation("io.github.pumpkinredbean:app-agent-inspector:<version>")
    releaseImplementation("io.github.pumpkinredbean:app-agent-inspector-noop:<version>")
}
```

Do not add both inspector artifacts to the same variant. They intentionally
contain the same public classes.

This pairing lets application code in `src/main` compile in every variant:

```kotlin
class App : Application() {
    private lateinit var inspector: InspectorHandle

    override fun onCreate() {
        super.onCreate()
        inspector = AppAgentInspector.install(
            application = this,
            runtime = appGraph.toolRuntime,
            configuration = InspectorConfiguration(
                openOnShake = true,
                maxEntries = 200,
            ),
        )
    }
}
```

The default history limit is 200 calls. Installation replaces an earlier
process-wide inspector installation and detaches its listener. Retain the
returned handle when the application needs `open()` or explicit `close()`.

## Opening the inspector

When `openOnShake` is enabled, the real artifact registers the accelerometer
only while at least one application Activity is resumed. Two strong samples
within a short window open a non-exported Inspector Activity. The detector is
debounced and does not reopen the inspector while that Activity is foreground.
It handles multiple resumed Activities without prematurely unregistering the
sensor.

No Android permission is required for the accelerometer. A device without an
accelerometer can still use:

```kotlin
inspector.open()
```

Release builds execute the same method calls against the no-op implementation.
It registers no runtime listener, Activity lifecycle callback, or sensor
listener.

## What the UI shows

The Inspector has two history views:

- **Calls** shows one row per tool call with its result, origin, transport,
  duration, and approval outcome.
- **Approvals** shows only calls registered with `ToolApproval.MOBILE`,
  including waiting, approved, denied, timed-out, unavailable, and failed
  outcomes.

Opening a call shows:

- the registered tool name and description;
- call ID, source, transport, timestamp, and additional execution attributes;
- only the lifecycle events that actually occurred;
- the complete `app-agent/1` `tool.call` envelope;
- the observed approval policy, outcome, and total wait;
- validation violations when input was rejected;
- the complete `tool.result` envelope, or the absence of a result while a
  call is running or cancelled.

Request, approval, validation, and response sections are independently
collapsible. JSON can scroll in both directions and can be copied.

## Runtime audit events

`ToolRuntime.addAuditListener` exposes the same process-local event source
used by the Inspector:

```kotlin
val subscription = runtime.addAuditListener { event ->
    auditExecutor.execute {
        applicationAuditSink.append(event)
    }
}

subscription.close()
```

Listeners receive `Received`, validation, approval, execution, cancellation,
and completion events only when those phases happen. Listener failures are
isolated from the tool result. The callback itself is synchronous, so custom
listeners must offload file, database, or network work.

The runtime can observe total time spent waiting for an approval handler, but it
cannot infer which human acted or split queue time from visible decision time.
The Inspector therefore does not invent those values. A future integration with
`ToolApprovalController` may add precise queue and presentation events.

## Retention and privacy

The real Inspector keeps a bounded in-memory history for the current Android
process:

- nothing is restored after process death;
- no file, database, analytics event, or network request is created;
- closing the handle stops future collection;
- old terminal calls are evicted before active calls when the limit is reached.

The Inspector displays full `ToolCall` and `ToolResult` payloads. Never put
passwords, PINs, OTPs, access tokens, or secure-keypad values in those payloads.
If an application needs persistent production auditing, install a separate
application-owned `ToolAuditListener` with its own retention, access control,
and data-minimization policy.
