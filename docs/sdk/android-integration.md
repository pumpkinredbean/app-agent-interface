# Android integration

## Recommended ownership

The application dependency-injection container should own a singleton `ToolRuntime`. The foreground service and in-app agent then share the same instance.

```text
Application / DI
  `- ToolRuntime
       |- registered application handlers
       |- Foreground Service -> LoopbackToolServer
       `- In-app Agent       -> direct execute()
```

A handler captures an application repository in its closure:

```kotlin
class AppToolModule(
    private val accountRepository: AccountRepository,
    private val approvalCoordinator: ApprovalCoordinator,
) {
    val runtime = ToolRuntime(
        approvalHandler = ToolApprovalHandler(approvalCoordinator::awaitDecision),
    ).apply {
        register(accountBalanceSpec) { input, _ ->
            accountRepository.getBalanceJson(input)
        }
    }
}
```

The signed-in session and access token remain inside the application process.

## Foreground service

The SDK does not declare an Android `Service` because notification behavior, lifecycle policy, and dependency injection differ by application. Start and stop the server from an application-owned service:

```kotlin
class AppToolService : Service() {
    private lateinit var server: LoopbackToolServer

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createNotification())
        server = LoopbackToolServer(
            runtime = appGraph.toolRuntime,
            port = 8765,
            bearerToken = appGraph.pairingSession.currentToken,
        )
        server.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

The example manifest demonstrates foreground-service and notification permissions. A production application must review service lifetime against its target SDK, foreground-service type, user-visible behavior, and store policy. The SDK does not assume permanent background execution.

## ADB connection

The server listens only on device `127.0.0.1:8765`:

```bash
adb forward tcp:18765 tcp:8765
curl http://127.0.0.1:18765/healthz
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18765/v1/tools
```

The model provider process never connects to Android directly. The desktop harness reads the manifest and forwards validated ToolCalls.

## In-app calls

An in-app agent bypasses HTTP:

```kotlin
val result = toolRuntime.execute(
    call = ToolCall(callId, selectedTool, modelInput),
    attributes = mapOf("caller" to "in-app-agent"),
)
```

Desktop and in-app callers therefore share input validation, approval, handler behavior, and error semantics.

## Optional UI fallback for a desktop harness

The Kotlin runtime intentionally does not expose screen automation. A desktop harness can separately install the repository's generic UiAutomator2 instrumentation companion and append `mobile_*` base tools when an existing UI flow has no semantic application tool. That companion is fixed to one target package at startup and returns compact flat JSON rather than raw XML. See [Android UI automation fallback](android-ui-automation.md).

## Mobile approval UI

`ToolApprovalHandler` is a suspending function. The application implements a coordinator that publishes a request to Activity or Compose UI and waits for the user's decision:

```kotlin
val runtime = ToolRuntime(
    approvalHandler = ToolApprovalHandler { request ->
        approvalCoordinator.awaitDecision(
            callId = request.call.id,
            toolName = request.spec.name,
            input = request.call.input,
        )
    },
)
```

An existing Java application can use the blocking adapter. Invoke it from the runtime's worker thread, never the Android main thread:

```java
ToolRuntime runtime = ToolRuntime.withBlockingApproval(
    request -> approvalCoordinator.awaitDecision(request)
);
```

- Display the registered ToolSpec and actual input, not untrusted model prose.
- Correlate the decision with the execution request using the call ID.
- Treat rejection, cancellation, and timeout as `false`.
- If no Activity is available or UI cannot be presented, treat approval as unavailable.

## Remote transport

Do not change `LoopbackToolServer` to bind to `0.0.0.0`, and do not bundle ngrok in the APK. Implement remote connectivity as a separate relay transport with:

- explicit device pairing;
- short-lived session credentials;
- TLS;
- call ID or nonce replay protection;
- connection and approval auditing;
- immediate session invalidation when the user signs out.

Adding a relay transport does not change ToolSpec, registration, or handler APIs.
