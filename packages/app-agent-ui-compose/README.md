# `app-agent-ui-compose`

Optional Compose adapters for application-owned UI frames and tool approval. The module does not add fields to `ToolSpec` or change the wire protocol.

![Tool approval dialog](src/test/screenshots/tool-approval-dialog.png)

```kotlin
val approvals = ToolApprovalController(
    ToolApprovalConfiguration(
        decisionTimeoutMillis = 60_000L,
        maxPendingRequests = 16,
    ),
)

val runtime = ToolRuntime(approvalDecisionHandler = approvals)

// Install exactly once in foreground Activity content.
ToolApprovalHost(controller = approvals)
```

The controller maintains a bounded FIFO queue. Queued requests do not consume decision time; the default 60-second timer begins only after the current dialog is rendered. The same host advances through later requests without stacking dialogs.

`ToolApprovalNotificationBridge` can publish one generic notification when approvals are waiting and the host is not visible. The application supplies the notification icon and navigation `PendingIntent`. Tool names and arguments are never placed in the notification.

Dismissal invokes denial. Static labels and buttons are SDK-owned string resources. Dynamic dialog content comes only from the registered `ToolSpec.description` and the actual `ToolCall.name` and `ToolCall.input`. Model-authored explanatory prose is not accepted by this UI.

PINs, OTPs, passwords, access tokens, and other secrets must never be ToolCall arguments. Collect them only in app-owned secure UI after approval; the library intentionally does not provide a redactor.

For service-specific approval interactions, install application-owned content
while retaining the SDK queue, lifecycle, and timeout behavior:

```kotlin
ToolApprovalHost(controller = approvals) { approval ->
    SecureOrderApproval(
        request = approval.request,
        remainingMillis = approval.remainingDecisionMillis,
        onApprove = approval::approve,
        onDeny = approval::deny,
    )
}
```

The secure value stays inside `SecureOrderApproval`; it is not added to the
tool call or returned to the runtime.

`UiFrameHost` is the optional Compose adapter for the framework-neutral
`app-agent-ui` frame navigator and renderer registry. Applications still own
every frame tag, payload schema, and Composable. See
[Application-defined UI frames](../../docs/sdk/ui-frames.md).

See the full [on-device approval contract](../../docs/sdk/on-device-approval.md) and [Compose integration guide](../../docs/sdk/compose-approval-ui.md).

Regenerate the checked-in preview with Roborazzi:

```bash
./gradlew :app-agent-ui-compose:recordRoborazziDebug
```
