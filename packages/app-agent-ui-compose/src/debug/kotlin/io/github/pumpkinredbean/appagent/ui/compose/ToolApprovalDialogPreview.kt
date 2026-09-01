package io.github.pumpkinredbean.appagent.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.pumpkinredbean.appagent.ToolApprovalRequest
import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Preview(name = "Tool approval", widthDp = 411, heightDp = 891, showBackground = true)
@Composable
private fun ToolApprovalDialogPreview() {
    MaterialTheme {
        ToolApprovalDialog(
            request = previewRequest(),
            onApprove = {},
            onDeny = {},
        )
    }
}

private fun previewRequest(): ToolApprovalRequest = ToolApprovalRequest(
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
