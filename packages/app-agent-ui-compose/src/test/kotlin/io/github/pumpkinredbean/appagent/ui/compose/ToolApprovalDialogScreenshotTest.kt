package io.github.pumpkinredbean.appagent.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.pumpkinredbean.appagent.ToolApprovalRequest
import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolSpec
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ToolApprovalDialogScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun toolApprovalDialog() {
        composeRule.setContent {
            MaterialTheme(colorScheme = previewColorScheme) {
                ToolApprovalDialog(
                    request = previewRequest,
                    onApprove = {},
                    onDeny = {},
                    queuedRequestCount = 2,
                    remainingDecisionMillis = 47_000L,
                )
            }
        }

        composeRule
            .onNodeWithTag("tool-approval-dialog")
            .captureRoboImage(
                File("src/test/screenshots/tool-approval-dialog.png").absolutePath,
            )
    }

    @Test
    fun dialogContentUsesOnlyTheRegisteredSpecAndActualCall() {
        val content = previewRequest.toDialogContent()

        assertEquals(previewRequest.spec.description, content.description)
        assertEquals(previewRequest.call.name, content.toolName)
        assertEquals(
            listOf("Location", "Date", "Condition", "Threshold"),
            content.details.map(ToolApprovalDialogDetail::label),
        )
        assertEquals(
            listOf("Seoul", "2026-09-03", "TEMPERATURE_ABOVE", "30"),
            content.details.map(ToolApprovalDialogDetail::value),
        )
    }
}

private val previewColorScheme = lightColorScheme(
    primary = Color(0xFF006C50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8FF8CD),
    onPrimaryContainer = Color(0xFF002117),
    surface = Color(0xFFF7FAF7),
    surfaceContainer = Color(0xFFEDF1ED),
    surfaceContainerHigh = Color(0xFFE7ECE8),
    onSurface = Color(0xFF171D1A),
    onSurfaceVariant = Color(0xFF3F4944),
    outlineVariant = Color(0xFFBFC9C3),
)

private val previewRequest = ToolApprovalRequest(
    call = ToolCall(
        id = "call_weather_alert_01",
        name = "weather_alert_create",
        input = buildJsonObject {
            put("location", "Seoul")
            put("date", "2026-09-03")
            put("condition", "TEMPERATURE_ABOVE")
            put("threshold", 30)
        },
    ),
    spec = ToolSpec(
        name = "weather_alert_create",
        description = "Creates a one-time weather alert stored by this app.",
        inputSchema = buildJsonObject { put("type", "object") },
    ),
)
