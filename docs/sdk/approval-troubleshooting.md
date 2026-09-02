# Approval troubleshooting

## A tool immediately returns `APPROVAL_UNAVAILABLE`

Check that:

- `ToolRuntime` received `approvalDecisionHandler = controller`;
- the controller has not been closed;
- the configured queue capacity has not been reached;
- every active ToolCall has a unique call ID.

A missing or background Activity does not immediately fail a controller request. It remains `WAITING_FOR_UI` until the host becomes visible, the caller cancels, or the controller closes.

## The countdown does not start

This is expected while the request is queued or the approval host is not resumed. `ToolApprovalHost` calls `markPresented` after the dialog reaches a frame. If using `ToolApprovalDialog` directly, the application owns presentation timing and timeout behavior.

## The countdown resets after rotation

The controller was probably created in the Activity or composition. Move it to application or dependency-injection scope and pass the same instance to the recreated host.

## Two approval dialogs appear

Install exactly one visible `ToolApprovalHost`. A common mistake is placing it in multiple navigation destinations or both an Activity root and a screen.

## No background notification appears

Verify all of the following:

- the app declared and received `POST_NOTIFICATIONS` on Android 13+;
- notifications and the `app_agent_approvals` channel are enabled in system settings;
- `smallIconResourceId` references a valid monochrome notification drawable;
- the bridge is still application- or service-scoped;
- no registered approval host is currently resumed.

The notification deliberately contains no approve or deny buttons. Tapping it must open the app-owned approval host.

## A queued request never expires

This is intentional. Queue wait time does not consume decision time, so each request gets the configured window only when it is actually shown. Configure transport- or caller-level cancellation if an off-screen request must not wait indefinitely.

## The call returned `APPROVAL_TIMEOUT`

The request was presented but received no decision before its configured deadline. The result is retryable, but the agent should not loop automatically: retry only when the user still intends the action.

## A PIN or OTP appears in the dialog or logs

Stop the flow and remove that field from the tool schema and ToolCall path. Do not solve this with UI masking or a post-hoc redactor. Collect the secret only in the application's existing secure UI after the non-secret action intent is approved.

## Approvals reappear after process restart

The SDK controller does not persist approvals. If they reappear, application code is serializing or recreating them. Remove that restoration for the current ADB transport. Durable relay requests require an independently reviewed authenticated session and expiry design.

