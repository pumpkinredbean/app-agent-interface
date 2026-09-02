package io.github.pumpkinredbean.appagent.inspector

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.pumpkinredbean.appagent.ToolApproval
import io.github.pumpkinredbean.appagent.ToolApprovalDecision
import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolResult
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class InspectorScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun calls() {
        composeRule.setContent {
            PreviewTheme {
                InspectorHistoryScreen(approvalSnapshot, onCallSelected = {})
            }
        }

        composeRule.onNodeWithTag("inspector-calls").captureRoboImage(
            File("src/test/screenshots/inspector-calls.png").absolutePath,
        )
    }

    @Test
    fun approvals() {
        composeRule.setContent {
            PreviewTheme {
                InspectorHistoryScreen(
                    snapshot = approvalSnapshot,
                    onCallSelected = {},
                    initialTab = HistoryTab.APPROVALS,
                )
            }
        }

        composeRule.onNodeWithTag("inspector-approvals").captureRoboImage(
            File("src/test/screenshots/inspector-approvals.png").absolutePath,
        )
    }

    @Test
    fun expandedCallDetail() {
        composeRule.setContent {
            PreviewTheme {
                InspectorCallDetailScreen(approvedCall, onBack = {})
            }
        }

        composeRule.onNodeWithTag("inspector-call-detail").captureRoboImage(
            File("src/test/screenshots/inspector-call-detail.png").absolutePath,
        )
    }

    @Test
    fun expandedApprovalAndResponse() {
        composeRule.setContent {
            PreviewTheme {
                InspectorCallDetailScreen(approvedCall, onBack = {})
            }
        }

        composeRule.onNodeWithTag("response-payload").performScrollTo()
        composeRule.onNodeWithTag("inspector-call-detail").captureRoboImage(
            File("src/test/screenshots/inspector-call-detail-response.png").absolutePath,
        )
    }

    @Test
    fun payloadSectionsCanCollapse() {
        composeRule.setContent {
            PreviewTheme {
                InspectorCallDetailScreen(approvedCall, onBack = {})
            }
        }

        composeRule.onNodeWithTag("request-section-toggle").performClick()
        composeRule.onAllNodesWithTag("request-payload").assertCountEquals(0)
    }
}

@androidx.compose.runtime.Composable
private fun PreviewTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3659C9),
            surface = Color.White,
            onSurface = Color(0xFF171A1F),
            outlineVariant = Color(0xFFE1E4E9),
        ),
        content = content,
    )
}

private val baseTime = Instant.parse("2026-09-02T05:21:03.118Z").toEpochMilli()

private val approvedCall = previewRecord(
    id = "call_01J8YV5X2Q9M4A7E",
    name = "calendar_event_create",
    description = "Creates a calendar event in the current workspace.",
    receivedAt = baseTime,
    durationMillis = 4_100L,
    approvalDecision = ToolApprovalDecision.APPROVED,
    result = ToolResult.success(
        "call_01J8YV5X2Q9M4A7E",
        buildJsonObject {
            put("eventId", "evt_8M2K91")
            put("created", true)
        },
    ),
)

private val callSnapshot = InspectorSnapshot(
    appName = "Sample app",
    attached = true,
    calls = listOf(
        approvedCall.copy(receivedAtEpochMillis = baseTime + 4_000L),
        previewRecord(
            id = "call_01J8YV3CJ81N6R2K",
            name = "workspace_summary_get",
            description = "Reads the latest workspace activity summary.",
            receivedAt = baseTime - 141_000L,
            durationMillis = 286L,
            result = ToolResult.success(
                "call_01J8YV3CJ81N6R2K",
                buildJsonObject { put("items", 12) },
            ),
        ),
        previewRecord(
            id = "call_01J8YUZZP31C5N8T",
            name = "message_send",
            description = "Sends a message to a workspace member.",
            receivedAt = baseTime - 527_000L,
            durationMillis = 8_700L,
            approvalDecision = ToolApprovalDecision.DENIED,
            result = ToolResult.failure(
                "call_01J8YUZZP31C5N8T",
                "APPROVAL_DENIED",
                "The user declined this tool call",
            ),
        ),
        previewRecord(
            id = "call_01J8YUV6M12B9K4P",
            name = "report_export",
            description = "Exports the selected report as a PDF document.",
            receivedAt = baseTime - 1_380_000L,
            durationMillis = 1_300L,
            result = ToolResult.failure(
                "call_01J8YUV6M12B9K4P",
                "EXPORT_FAILED",
                "The report could not be exported",
            ),
        ),
    ),
)

private val approvalSnapshot = InspectorSnapshot(
    appName = "Sample app",
    attached = true,
    calls = buildList {
        add(
            previewRecord(
                id = "call_01J8Z0A4F2D7S6QW",
                name = "workspace_member_invite",
                description = "Invites a new member to the current workspace.",
                receivedAt = baseTime + 30_000L,
                durationMillis = null,
                approvalDecision = null,
                result = null,
            ),
        )
        addAll(callSnapshot.calls)
        add(
            previewRecord(
                id = "call_01J8YT1RX03M7V5C",
                name = "contact_export",
                description = "Exports contact records from this application.",
                receivedAt = baseTime - 2_803_000L,
                durationMillis = 60_000L,
                approvalDecision = ToolApprovalDecision.TIMED_OUT,
                result = ToolResult.failure(
                    "call_01J8YT1RX03M7V5C",
                    "APPROVAL_TIMEOUT",
                    "The approval request timed out",
                    retryable = true,
                ),
            ),
        )
    },
)

private fun previewRecord(
    id: String,
    name: String,
    description: String,
    receivedAt: Long,
    durationMillis: Long?,
    approvalDecision: ToolApprovalDecision? = null,
    result: ToolResult?,
): InspectorCallRecord {
    val approvalRequired = approvalDecision != null || result == null
    val call = ToolCall(
        id = id,
        name = name,
        input = if (name == "calendar_event_create") {
            buildJsonObject {
                put("title", "Project review")
                put("startsAt", "2026-09-03T10:00:00+09:00")
                put("attendees", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("alex@example.com"))
                })
            }
        } else {
            buildJsonObject { put("sample", true) }
        },
    )
    val approvalStart = if (approvalRequired) 18L else null
    val approvalEnd = if (approvalDecision != null) {
        when (approvalDecision) {
            ToolApprovalDecision.TIMED_OUT -> 60_018L
            else -> 3_218L
        }
    } else {
        null
    }
    val lifecycle = buildList {
        add(InspectorLifecycleEvent("Received", receivedAt, 0L, InspectorEventTone.NEUTRAL))
        add(InspectorLifecycleEvent("Validated", receivedAt + 12L, 12L, InspectorEventTone.SUCCESS))
        if (approvalRequired) {
            add(InspectorLifecycleEvent("Approval requested", receivedAt + 18L, 18L, InspectorEventTone.WARNING))
        }
        approvalDecision?.let {
            add(
                InspectorLifecycleEvent(
                    label = when (it) {
                        ToolApprovalDecision.APPROVED -> "Approved"
                        ToolApprovalDecision.DENIED -> "Denied"
                        ToolApprovalDecision.TIMED_OUT -> "Timed out"
                        ToolApprovalDecision.UNAVAILABLE -> "Unavailable"
                    },
                    occurredAtEpochMillis = receivedAt + requireNotNull(approvalEnd),
                    elapsedMillis = approvalEnd,
                    tone = if (it == ToolApprovalDecision.APPROVED) InspectorEventTone.SUCCESS else InspectorEventTone.ERROR,
                ),
            )
        }
        if (approvalDecision == ToolApprovalDecision.APPROVED || !approvalRequired) {
            val executionStartedAt = if (approvalRequired) 3_220L else 14L
            add(
                InspectorLifecycleEvent(
                    "Execution started",
                    receivedAt + executionStartedAt,
                    executionStartedAt,
                    InspectorEventTone.NEUTRAL,
                ),
            )
        }
        if (result != null) {
            add(
                InspectorLifecycleEvent(
                    label = if (result.ok) "Completed" else "Returned error",
                    occurredAtEpochMillis = receivedAt + requireNotNull(durationMillis),
                    elapsedMillis = durationMillis,
                    tone = if (result.ok) InspectorEventTone.SUCCESS else InspectorEventTone.ERROR,
                ),
            )
        }
    }
    return InspectorCallRecord(
        call = call,
        description = description,
        approvalPolicy = if (approvalRequired) ToolApproval.MOBILE else ToolApproval.NONE,
        attributes = mapOf("source" to "Desktop agent", "transport" to "adb-loopback"),
        receivedAtEpochMillis = receivedAt,
        lifecycle = lifecycle,
        validationViolations = emptyList(),
        approvalRequestedElapsedMillis = approvalStart,
        approvalResolvedElapsedMillis = approvalEnd,
        approvalDecision = approvalDecision,
        approvalFailureCode = null,
        result = result,
        durationMillis = durationMillis,
        cancelledStage = null,
    )
}
