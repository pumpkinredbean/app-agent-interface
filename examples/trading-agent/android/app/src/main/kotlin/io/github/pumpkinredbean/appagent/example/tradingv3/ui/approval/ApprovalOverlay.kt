package io.github.pumpkinredbean.appagent.example.tradingv3.ui.approval

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.pumpkinredbean.appagent.ToolApprovalRequest
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.ActionButton
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Divider
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Eyebrow
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.LocalMarketColors
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.MarketText
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Metric
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.money
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

private enum class ApprovalStep { REVIEW, KEYPAD }

private data class OrderApprovalContent(
    val toolName: String,
    val symbol: String,
    val budget: Double,
)

@Composable
fun ApprovalOverlay(
    request: ToolApprovalRequest,
    queuedRequestCount: Int,
    remainingDecisionMillis: Long?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    val callId = request.call.id
    val content = remember(callId) {
        OrderApprovalContent(
            toolName = request.call.name,
            symbol = request.call.input["symbol"]?.jsonPrimitive?.content ?: "Asset",
            budget = request.call.input["budget"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
        )
    }
    var step by remember(callId) { mutableStateOf(ApprovalStep.REVIEW) }
    var entered by remember(callId) { mutableStateOf("") }
    val remainingSeconds = remainingDecisionMillis?.let { (it + 999L) / 1_000L }
    val keys = remember(callId) { (0..9).map(Int::toString).shuffled() }

    BackHandler(onBack = onDeny)
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        val colors = LocalMarketColors.current
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.page.copy(alpha = .84f))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(colors.surface)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Eyebrow(
                            "Device approval · " + when (step) {
                                ApprovalStep.REVIEW -> "review"
                                ApprovalStep.KEYPAD -> "secure keypad"
                            },
                        )
                        val queueLabel = if (queuedRequestCount > 0) {
                            " · $queuedRequestCount waiting"
                        } else {
                            ""
                        }
                        MarketText(
                            remainingSeconds?.let { "${it}s remaining$queueLabel" }
                                ?: "Starting approval timer$queueLabel",
                            color = if (remainingSeconds != null && remainingSeconds < 10) {
                                colors.red
                            } else {
                                colors.textDim
                            },
                            size = 12,
                            family = FontFamily.Monospace,
                        )
                    }
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(colors.soft)
                            .clickable(onClick = onDeny),
                        contentAlignment = Alignment.Center,
                    ) {
                        MarketText("×", size = 21)
                    }
                }

                when (step) {
                    ApprovalStep.REVIEW -> ReviewStep(request = content) {
                        step = ApprovalStep.KEYPAD
                    }
                    ApprovalStep.KEYPAD -> KeypadStep(
                        enteredCount = entered.length,
                        keys = keys,
                        onKey = { key -> if (entered.length < 4) entered += key },
                        onDelete = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
                        onContinue = {
                            entered = ""
                            onApprove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewStep(
    request: OrderApprovalContent,
    onKeypad: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            MarketText("Review this local order", size = 26, weight = FontWeight.Bold)
            MarketText(
                "The agent prepared the action. Authority stays in this app.",
                color = LocalMarketColors.current.textSoft,
                size = 14,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(17.dp))
                .background(LocalMarketColors.current.raised)
                .padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("ASSET", request.symbol)
                Metric(
                    "VALUE",
                    "${money(request.budget)} USDT",
                    valueColor = LocalMarketColors.current.accent,
                )
            }
            Divider()
            Metric("REQUEST", request.toolName)
        }
        MarketText(
            "The secure value stays inside this app and never becomes a tool argument or model context.",
            color = LocalMarketColors.current.textDim,
            size = 12,
        )
        ActionButton("Continue to secure keypad", onKeypad, Modifier.fillMaxWidth())
    }
}

@Composable
private fun KeypadStep(
    enteredCount: Int,
    keys: List<String>,
    onKey: (String) -> Unit,
    onDelete: () -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MarketText("Secure keypad", size = 25, weight = FontWeight.Bold)
            MarketText(
                "Enter any four positions for this demo",
                color = LocalMarketColors.current.textSoft,
                size = 13,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < enteredCount) {
                                LocalMarketColors.current.accent
                            } else {
                                LocalMarketColors.current.soft
                            },
                        ),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            keys.chunked(3).forEach { rowKeys ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    rowKeys.forEach { key ->
                        KeyButton(key, { onKey(key) }, Modifier.weight(1f))
                    }
                    if (rowKeys.size < 3) {
                        KeyButton("⌫", onDelete, Modifier.weight(1f))
                        if (rowKeys.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        ActionButton(
            "Approve local order",
            onContinue,
            Modifier.fillMaxWidth(),
            enabled = enteredCount == 4,
        )
    }
}

@Composable
private fun KeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LocalMarketColors.current.raised)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MarketText(
            label,
            size = 19,
            weight = FontWeight.SemiBold,
            family = FontFamily.Monospace,
        )
    }
}
