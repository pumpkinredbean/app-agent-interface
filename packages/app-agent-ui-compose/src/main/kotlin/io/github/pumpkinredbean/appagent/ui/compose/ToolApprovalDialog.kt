package io.github.pumpkinredbean.appagent.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pumpkinredbean.appagent.ToolApprovalRequest
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Displays one app-owned approval request. Dismissal is treated as denial.
 *
 * Dynamic content is derived only from the registered tool specification and
 * the actual tool call. Model-authored explanatory prose is not rendered.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
public fun ToolApprovalDialog(
    request: ToolApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    queuedRequestCount: Int = 0,
    remainingDecisionMillis: Long? = null,
) {
    BasicAlertDialog(
        onDismissRequest = onDeny,
        modifier = modifier,
    ) {
        ToolApprovalDialogSurface(
            content = request.toDialogContent(),
            onApprove = onApprove,
            onDeny = onDeny,
            queuedRequestCount = queuedRequestCount,
            remainingDecisionMillis = remainingDecisionMillis,
        )
    }
}

@Composable
internal fun ToolApprovalDialogSurface(
    content: ToolApprovalDialogContent,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    queuedRequestCount: Int = 0,
    remainingDecisionMillis: Long? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tool-approval-dialog"),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
            Text(
                text = stringResource(R.string.app_agent_approval_request_label),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp,
            )
            Text(
                text = stringResource(R.string.app_agent_approval_request_supporting_text),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.app_agent_approval_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = content.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 21.sp,
            )

            Spacer(Modifier.height(20.dp))
            ApprovalDetails(content)
            ApprovalQueueStatus(
                queuedRequestCount = queuedRequestCount,
                remainingDecisionMillis = remainingDecisionMillis,
            )
            Spacer(Modifier.height(16.dp))

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.app_agent_approval_once_disclaimer),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDeny) {
                    Text(stringResource(R.string.app_agent_approval_deny))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onApprove,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(stringResource(R.string.app_agent_approval_allow_once))
                }
            }
        }
    }
}

@Composable
private fun ApprovalQueueStatus(
    queuedRequestCount: Int,
    remainingDecisionMillis: Long?,
) {
    if (queuedRequestCount <= 0 && remainingDecisionMillis == null) return

    Spacer(Modifier.height(14.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (queuedRequestCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.app_agent_approval_waiting_count,
                    queuedRequestCount,
                    queuedRequestCount,
                ),
                modifier = Modifier.testTag("tool-approval-queued-count"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (remainingDecisionMillis != null) {
            Text(
                text = stringResource(
                    R.string.app_agent_approval_timeout,
                    remainingDecisionMillis.toCountdown(),
                ),
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("tool-approval-countdown"),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun ApprovalDetails(content: ToolApprovalDialogContent) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Text(
                text = stringResource(R.string.app_agent_approval_tool_label),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = content.toolName,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (content.details.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(6.dp))
                content.details.forEach { detail ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = detail.label,
                            modifier = Modifier.weight(0.38f),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = detail.value,
                            modifier = Modifier.weight(0.62f),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Immutable
internal data class ToolApprovalDialogContent(
    val description: String,
    val toolName: String,
    val details: List<ToolApprovalDialogDetail>,
)

@Immutable
internal data class ToolApprovalDialogDetail(
    val label: String,
    val value: String,
)

internal fun ToolApprovalRequest.toDialogContent(): ToolApprovalDialogContent = ToolApprovalDialogContent(
    description = spec.description,
    toolName = call.name,
    details = call.input.toDialogDetails(),
)

private fun JsonObject.toDialogDetails(): List<ToolApprovalDialogDetail> = entries.map { (key, value) ->
    ToolApprovalDialogDetail(
        label = key.humanize(),
        value = value.displayValue(),
    )
}

private fun String.humanize(): String {
    val words = replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .lowercase(Locale.ROOT)
    return words.replaceFirstChar { character -> character.titlecase() }
}

private fun JsonElement.displayValue(): String = when (this) {
    JsonNull -> "null"
    is JsonPrimitive -> contentOrNull ?: toString()
    is JsonArray, is JsonObject -> toString()
}

private fun Long.toCountdown(): String {
    val totalSeconds = (coerceAtLeast(0L) + 999L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}
