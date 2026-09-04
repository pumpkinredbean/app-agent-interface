package io.github.pumpkinredbean.appagent.example.tradingv3.ui.frames

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.pumpkinredbean.appagent.example.tradingv3.model.FrameAction
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.ActionButton
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.LocalMarketColors
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.MarketText
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.StatusPill
import io.github.pumpkinredbean.appagent.ui.UiFrameLifecycleState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun FrameColumn(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(LocalMarketColors.current.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
internal fun LiveLifecycleRow(
    lifecycle: UiFrameLifecycleState,
    onAction: (FrameAction) -> Unit,
) {
    val active = lifecycle == UiFrameLifecycleState.RESUMED
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(if (active) "Live · turn scoped" else "Paused · last snapshot", active = active)
        if (!active && lifecycle != UiFrameLifecycleState.DISPOSED) {
            ActionButton(
                text = "Resume",
                onClick = { onAction(FrameAction.ResumeLive) },
                primary = false,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
internal fun ChangeText(value: Double, size: Int = 14) {
    val colors = LocalMarketColors.current
    MarketText(
        text = io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.signedPercent(value),
        color = if (value >= 0) colors.accent else colors.red,
        size = size,
        weight = FontWeight.Bold,
    )
}

internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.double(name: String): Double = getValue(name).jsonPrimitive.double
internal fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
internal fun JsonElement.obj(): JsonObject = jsonObject
