# Approval testing

Test approval at three boundaries: runtime outcome, queue concurrency, and rendered user content.

## Runtime tests

Use a deterministic decision handler when testing a registered tool. Assert that denied, timed-out, and unavailable calls never invoke the application handler:

```kotlin
val runtime = ToolRuntime(
    approvalDecisionHandler = ToolApprovalDecisionHandler {
        ToolApprovalDecision.TIMED_OUT
    },
)

var executed = false
runtime.register(spec, ToolApproval.MOBILE) { _, _ ->
    executed = true
    JsonNull
}

val result = runtime.execute(call)

assertFalse(result.ok)
assertEquals("APPROVAL_TIMEOUT", result.error?.code)
assertFalse(executed)
```

## Queue tests

Cover these cases for any application-owned extension around `ToolApprovalController`:

- simultaneous calls are shown in FIFO arrival order;
- time spent behind another request does not reduce a request's 60-second decision window;
- the timer begins only after the current call ID is marked presented;
- a late action for a timed-out call ID cannot approve the next request;
- dismissal resolves only the current request as denied;
- caller cancellation removes both current and queued requests;
- the configured capacity fails overflow closed;
- controller close resolves all requests unavailable and accepts no new work;
- lifecycle rotation does not reset a timer or duplicate a request;
- background state posts one aggregate notification without tool content.

The SDK repository runs these cases with `kotlinx-coroutines-test` and Robolectric.

## Compose and screenshot tests

The dialog is stateless and can be rendered directly in a Compose test. Supply fixed queue and countdown values so the screenshot is deterministic:

```kotlin
ToolApprovalDialog(
    request = fixture,
    queuedRequestCount = 2,
    remainingDecisionMillis = 47_000L,
    onApprove = {},
    onDeny = {},
)
```

Assert separately that content maps only from `ToolSpec.description`, `ToolCall.name`, and validated `ToolCall.input`. Never use a fixture PIN, OTP, password, access token, or session cookie as a normal example argument.

## Device integration tests

For an ADB-connected test:

1. issue two `MOBILE` ToolCalls concurrently;
2. confirm one dialog appears with one later request indicated;
3. leave it open and verify only the current countdown changes;
4. approve it and confirm the same dialog advances to the second call with a fresh full timeout;
5. background the app and confirm one generic notification appears;
6. return through the notification and deny the request;
7. verify the handler ran only for the approved call;
8. terminate the process and verify no approval is restored after restart.

Avoid screenshot- or coordinate-driven assertions for the security boundary itself. The authoritative assertion is the runtime result and whether the application handler executed.

## Repository commands

```bash
./gradlew :app-agent-runtime:test
./gradlew :app-agent-ui-compose:testDebugUnitTest
./gradlew :app-agent-ui-compose:verifyRoborazziDebug
```

