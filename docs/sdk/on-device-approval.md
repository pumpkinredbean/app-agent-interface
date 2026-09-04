# On-device tool approval

App Agent Interface treats approval as application runtime policy. It is not a model-visible tool field and it is not an Android runtime permission.

Use `ToolApproval.MOBILE` when a registered tool must pause before its handler executes. `ToolApprovalController` serializes those pauses into one bounded FIFO queue, while the optional Compose module presents the current request and notifies the user when the app is in the background.

## Ownership and flow

Create one controller in the same application or dependency-injection scope as `ToolRuntime`:

```text
Application / DI scope
  |- ToolApprovalController -- StateFlow<ToolApprovalQueueState>
  |- ToolRuntime(approvalDecisionHandler = controller)
  |- ToolApprovalNotificationBridge
  `- Activity content
       `- ToolApprovalHost(controller)
```

For each `MOBILE` tool call:

1. `ToolRuntime` validates the call against the registered input schema.
2. The controller accepts it if the bounded queue has capacity.
3. The first request becomes `WAITING_FOR_UI`; later requests remain FIFO queued.
4. `ToolApprovalHost` renders the current request in the single dialog and marks it `PRESENTED` after a frame is committed.
5. Only then does the decision timer start.
6. Allow, deny, or timeout resolves that call. The same dialog host switches to the next request without stacking another dialog.
7. Only an approved call reaches the application handler.

## Queue semantics

| Concern | Behavior |
|---|---|
| Ordering | FIFO by controller arrival |
| Consumer | One current request; install exactly one visible `ToolApprovalHost` |
| Default capacity | 16 requests including the current request |
| Default decision timeout | 60 seconds |
| Queued time | Does not consume the decision timeout |
| Timer start | First visible presentation of the current request |
| Background after presentation | The timer continues; it is not reset by rotation or backgrounding |
| Duplicate call ID | Fails closed with `APPROVAL_UNAVAILABLE` |
| Queue overflow | Fails closed with `APPROVAL_UNAVAILABLE` |
| Dismissal | Denial |
| Controller close | All current and queued requests resolve as unavailable |

`WAITING_FOR_UI` intentionally has no decision deadline. The caller can still cancel its suspended execution, and transport-level request deadlines remain the caller's responsibility. Once a request reaches `PRESENTED`, its deadline is never extended by another request.

Configure the two queue limits at construction:

```kotlin
val approvals = ToolApprovalController(
    ToolApprovalConfiguration(
        decisionTimeoutMillis = 60_000L,
        maxPendingRequests = 16,
    ),
)

val runtime = ToolRuntime(
    approvalDecisionHandler = approvals,
)
```

Use one controller per runtime. Do not create it in an Activity or composable; configuration changes would otherwise discard pending decisions.

## Runtime outcomes

The detailed handler preserves distinct outcomes:

| Decision | Tool result |
|---|---|
| `APPROVED` | Handler executes |
| `DENIED` | `APPROVAL_DENIED`, not retryable |
| `TIMED_OUT` | `APPROVAL_TIMEOUT`, retryable |
| `UNAVAILABLE` | `APPROVAL_UNAVAILABLE`, not retryable |

The earlier Boolean `ToolApprovalHandler` and `ToolRuntime.withBlockingApproval(...)` remain available for compatibility. They cannot distinguish denial from timeout. New integrations should install `ToolApprovalController` through `approvalDecisionHandler`.

## What the approval UI may display

The provided dialog accepts no model-authored presentation object. Its dynamic values come from:

- the application-registered `ToolSpec.description`;
- the actual `ToolCall.name`;
- the validated `ToolCall.input`.

Write the description so a person can understand the concrete effect of allowing the tool. Keep approval policy, credentials, provider metadata, and UI copy out of `ToolSpec`.

### PIN, OTP, passwords, and secure keypads

Secrets must never enter a model prompt, `ToolCall.input`, SDK-provided
approval details, desktop trace, frame payload, or tool result. A redactor is
not a sufficient security boundary because it runs after the secret has already
crossed into the tool-call path.

For a secure-input operation:

1. expose a semantic tool containing only non-secret intent data;
2. present the validated intent through `ToolApprovalHost`;
3. collect any required secret inside application-owned approval content, or
   in a subsequent application-owned secure flow;
4. resolve only the final approve/deny decision to `ToolApprovalController`;
5. return a minimal, non-sensitive outcome to the agent.

The SDK therefore does not provide a PIN/OTP redactor API or
coordinate-based secure keypad entry. The native trading example demonstrates
a custom keypad whose entered value never leaves its Composable.

## Android permissions are separate

Tool approval answers “may this registered application action run once?” Android permissions answer “may this application use an operating-system capability?”

Continue to request camera, location, notification, and similar permissions through Android's Activity Result APIs and system UI. A tool approval must not grant, emulate, or bypass an OS permission.

## Process death and restart

The current controller is deliberately in-memory and non-persistent. With the ADB loopback transport, Android process death also terminates the device-side server connection. On restart, the approval queue is empty and callers must issue a new call.

Do not serialize and restore stale approvals. A future relay transport that supports durable requests needs a separate authenticated server-side session, expiry, replay protection, cancellation, and revalidation design before it can recreate any user decision flow.

## Next steps

- [Compose approval UI](compose-approval-ui.md)
- [Approval testing](approval-testing.md)
- [Approval troubleshooting](approval-troubleshooting.md)
- [Android integration](android-integration.md)
- [Tool authoring](tool-authoring.md)
