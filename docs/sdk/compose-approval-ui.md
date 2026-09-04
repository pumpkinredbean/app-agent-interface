# Compose approval UI

`app-agent-ui-compose` provides the only SDK-owned product UI currently required by the protocol: a Material 3 one-time tool approval dialog. It also includes a lifecycle-aware queue host and an optional background notification bridge.

## Install

```kotlin
dependencies {
    implementation("io.github.pumpkinredbean:app-agent-runtime:<version>")
    implementation("io.github.pumpkinredbean:app-agent-ui-compose:<version>")
}
```

The UI artifact exposes the runtime transitively, so an application can omit the direct runtime dependency when no other module needs to declare it explicitly.

## Create application-scoped objects

Create the controller before the runtime and retain both outside Activity lifecycle:

```kotlin
class AgentGraph {
    val approvals = ToolApprovalController(
        ToolApprovalConfiguration(
            decisionTimeoutMillis = 60_000L,
            maxPendingRequests = 16,
        ),
    )

    val runtime = ToolRuntime(
        approvalDecisionHandler = approvals,
    )
}
```

Register only tools that need confirmation with `ToolApproval.MOBILE`:

```kotlin
graph.runtime.register(
    spec = deleteSavedItemSpec,
    approval = ToolApproval.MOBILE,
) { input, _ ->
    savedItemRepository.delete(input.getValue("itemId").jsonPrimitive.content)
    buildJsonObject { put("deleted", true) }
}
```

## Install one dialog host

Place exactly one `ToolApprovalHost` in a foreground destination that remains part of the Activity's composition:

```kotlin
setContent {
    AppTheme {
        AppNavigation()
        ToolApprovalHost(controller = graph.approvals)
    }
}
```

The host:

- observes queue state with lifecycle-aware collection;
- renders nothing when there is no request;
- keeps one dialog while requests advance in FIFO order;
- starts the current request's timer only after the dialog has reached a rendered frame;
- displays the current countdown and number of later requests;
- treats system dismissal as denial.

Do not collect `controller.state.current` destructively or install a host in every navigation screen. Multiple visible hosts would duplicate the same approval UI even though stale call-ID actions remain protected by the controller.

## Supply application-owned approval content

Keep the controller and host when a service needs an interaction beyond the
default dialog. The custom-content overload retains FIFO ordering, foreground
lifecycle handling, presentation-only timeout, and call-ID safety:

```kotlin
ToolApprovalHost(controller = graph.approvals) { approval ->
    SecureOrderApproval(
        request = approval.request,
        queuedRequestCount = approval.queuedRequestCount,
        remainingDecisionMillis = approval.remainingDecisionMillis,
        onApprove = approval::approve,
        onDeny = approval::deny,
    )
}
```

A secure keypad, OTP field, or password field must remain inside the
application-owned Composable. Never add its value to `ToolCall.input`, a frame
payload, model context, logs, or the approval callback. Only the final approve
or deny decision reaches `ToolRuntime`.

## Add the background notification

The optional bridge publishes one aggregate, privacy-safe notification only when at least one request exists and no registered approval host is visible. It never puts tool names, descriptions, arguments, or approve/deny actions in the notification.

```kotlin
val openApprovals = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    },
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)

val approvalNotifications = ToolApprovalNotificationBridge(
    context = context,
    controller = graph.approvals,
    configuration = ToolApprovalNotificationConfiguration(
        smallIconResourceId = R.drawable.ic_agent_approval,
        contentIntent = openApprovals,
    ),
)
```

Own the bridge in the same long-lived graph or service as the controller and close it when that owner shuts down. The application owns navigation: the supplied `PendingIntent` should open the Activity containing the single `ToolApprovalHost`.

On Android 13 and later, declare and request notification permission in the host application:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

The library does not add that permission to its manifest and does not show a permission prompt. If notifications are disabled, the bridge fails silently and the queued approval remains available when the user opens the app.

The default channel ID is `app_agent_approvals`. Applications can override the SDK string resources to localize the channel and notification copy. Android retains channel settings after creation, so changing defaults does not overwrite user-selected settings.

## Render the stateless dialog directly

`ToolApprovalDialog` remains available when an application already has its own coordinator:

```kotlin
ToolApprovalDialog(
    request = request,
    queuedRequestCount = 2,
    remainingDecisionMillis = 47_000L,
    onApprove = { resolve(request.call.id, true) },
    onDeny = { resolve(request.call.id, false) },
)
```

The application owns queueing, timeout, process lifecycle, and call-ID safety in this lower-level path. Prefer `ToolApprovalHost` with `ToolApprovalController` for new integrations.

## Theming, localization, and accessibility

The dialog consumes the host `MaterialTheme`; it does not install a theme. Static copy lives in namespaced Android string resources and can be overridden by the application. The title is exposed as an accessibility heading, content scrolls when arguments are long, and actions use text labels rather than icon-only controls.

The SDK intentionally does not add provider selection, model selection, connection management, chat, onboarding, or secure keypad UI. Those experiences belong to the host application or agent harness.
