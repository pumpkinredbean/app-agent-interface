package io.github.pumpkinredbean.appagent.example.tradingv3.ui.frames

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.pumpkinredbean.appagent.example.tradingv3.data.MarketRepository
import io.github.pumpkinredbean.appagent.example.tradingv3.model.DepthLevel
import io.github.pumpkinredbean.appagent.example.tradingv3.model.FrameAction
import io.github.pumpkinredbean.appagent.example.tradingv3.model.MarketAsset
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.ActionButton
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Divider
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Eyebrow
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.LocalMarketColors
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.MarketText
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Metric
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Panel
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.compactMoney
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.money
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.signedPercent
import io.github.pumpkinredbean.appagent.ui.UiFrame
import io.github.pumpkinredbean.appagent.ui.UiFrameLifecycleState
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Composable
fun AssetFrame(
    frame: UiFrame,
    lifecycle: UiFrameLifecycleState,
    repository: MarketRepository,
    onAction: (FrameAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember(frame.key) { frame.payload.jsonObject.toMarketAsset() }
    var market by remember(frame.key) { mutableStateOf(initial) }

    LaunchedEffect(frame.key, lifecycle) {
        if (lifecycle != UiFrameLifecycleState.RESUMED) return@LaunchedEffect
        while (true) {
            delay(2_200)
            market = repository.quote(market.symbol)
        }
    }

    FrameColumn(modifier) {
        LiveLifecycleRow(lifecycle, onAction)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Eyebrow(market.name)
                MarketText(market.pair, size = 28, weight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                MarketText(money(market.price), size = 25, weight = FontWeight.Bold, family = FontFamily.Monospace)
                ChangeText(market.changePercent, 14)
            }
        }

        Panel(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            PriceChart(market.series, Modifier.fillMaxWidth().height(176.dp))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Metric("24H HIGH", money(market.high), Modifier.fillMaxWidth())
            }
            Panel(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Metric("24H LOW", money(market.low), Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Metric("VOLUME", "${compactMoney(market.quoteVolume)} USDT", Modifier.fillMaxWidth())
            }
            Panel(Modifier.weight(1f), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                Metric("YOUR EXPOSURE", "${money(frame.payload.jsonObject.double("exposure"), 1)}%", Modifier.fillMaxWidth(), LocalMarketColors.current.accent)
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MarketText("Order book", size = 18, weight = FontWeight.Bold)
                    MarketText(if (lifecycle == UiFrameLifecycleState.RESUMED) "updating" else "snapshot", color = LocalMarketColors.current.textDim, size = 12)
                }
                Row(Modifier.fillMaxWidth()) {
                    DepthColumn("BID", market.bids, LocalMarketColors.current.accent, Modifier.weight(1f))
                    Spacer(Modifier.weight(.08f))
                    DepthColumn("ASK", market.asks, LocalMarketColors.current.red, Modifier.weight(1f))
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Compare", { onAction(FrameAction.CompareAllocation(market.symbol)) }, Modifier.weight(1f), primary = false)
            ActionButton("Prepare order", { onAction(FrameAction.PrepareOrder(market.symbol, 1_000.0)) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PriceChart(series: List<Double>, modifier: Modifier = Modifier) {
    val colors = LocalMarketColors.current
    Canvas(modifier) {
        repeat(4) { index ->
            val y = size.height * index / 3f
            drawLine(colors.line, Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (series.size < 2) return@Canvas
        val min = series.min()
        val range = (series.max() - min).coerceAtLeast(.0001)
        val path = Path()
        series.forEachIndexed { index, value ->
            val x = size.width * index / (series.lastIndex.toFloat())
            val y = size.height - ((value - min) / range).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, colors.accent.copy(alpha = .18f), style = Stroke(width = 9f, cap = StrokeCap.Round))
        drawPath(path, colors.accent, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
    }
}

@Composable
private fun DepthColumn(
    label: String,
    levels: List<DepthLevel>,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MarketText(label, color = color, size = 11, weight = FontWeight.Bold, family = FontFamily.Monospace)
        levels.take(5).forEach { level ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MarketText(money(level.price), color = color, size = 11, family = FontFamily.Monospace)
                MarketText(String.format("%.4f", level.quantity), color = LocalMarketColors.current.textDim, size = 11, family = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun AllocationFrame(
    frame: UiFrame,
    lifecycle: UiFrameLifecycleState,
    onAction: (FrameAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val payload = frame.payload.jsonObject
    val candidate = payload.string("candidate")
    val budget = payload.double("budget")
    val candidates = payload.array("candidates")
    val selected = candidates.first { it.obj().string("symbol") == candidate }.obj()

    FrameColumn(modifier) {
        LiveLifecycleRow(lifecycle, onAction)
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Eyebrow("Allocation comparison")
            MarketText("Choose the portfolio consequence.", size = 28, weight = FontWeight.Bold)
            MarketText("The decision stays editable until this device approves an order.", color = LocalMarketColors.current.textSoft, size = 14)
        }

        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("DECISION BUDGET", "${money(budget)} USDT")
                Metric("AVAILABLE CASH", "${money(payload.double("cash"))} USDT", valueColor = LocalMarketColors.current.accent)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            candidates.forEach { item ->
                val value = item.obj()
                CandidateRow(
                    value = value,
                    selected = value.string("symbol") == candidate,
                    onClick = { onAction(FrameAction.ChangeCandidate(value.string("symbol"), budget)) },
                )
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow("If you use $candidate")
                MarketText(
                    if (selected.double("afterExposure") > 50) "Momentum improves, concentration rises." else "Exposure stays comparatively balanced.",
                    size = 21,
                    weight = FontWeight.Bold,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("EXPOSURE", "${money(selected.double("currentExposure"), 1)} → ${money(selected.double("afterExposure"), 1)}%")
                    Metric("24H MOVE", signedPercent(selected.double("changePercent")), valueColor = if (selected.double("changePercent") >= 0) LocalMarketColors.current.accent else LocalMarketColors.current.red)
                }
                Divider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf(500.0, 1_000.0).forEach { amount ->
                        ActionButton(
                            text = if (amount == budget) "${money(amount, 0)} selected" else money(amount, 0),
                            onClick = { onAction(FrameAction.ChangeCandidate(candidate, amount)) },
                            modifier = Modifier.weight(1f),
                            primary = amount == budget,
                        )
                    }
                }
            }
        }

        ActionButton(
            text = "Prepare $candidate order",
            onClick = { onAction(FrameAction.PrepareOrder(candidate, budget)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CandidateRow(value: JsonObject, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalMarketColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.accentSoft else colors.raised)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) colors.accent else colors.soft).padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            MarketText(value.string("symbol").take(1), color = if (selected) colors.page else colors.text, size = 16, weight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            MarketText(value.string("symbol"), size = 17, weight = FontWeight.Bold)
            MarketText(value.string("name"), color = colors.textDim, size = 12)
        }
        Column(horizontalAlignment = Alignment.End) {
            ChangeText(value.double("changePercent"), 13)
            MarketText("${money(value.double("afterExposure"), 1)}% after", color = colors.textDim, size = 11)
        }
    }
}

private fun JsonObject.toMarketAsset(): MarketAsset = MarketAsset(
    symbol = string("symbol"),
    name = string("name"),
    price = double("price"),
    changePercent = double("changePercent"),
    high = double("high"),
    low = double("low"),
    quoteVolume = double("quoteVolume"),
    series = array("series").map { it.toString().toDouble() },
    bids = array("bids").map { DepthLevel(it.obj().double("price"), it.obj().double("quantity")) },
    asks = array("asks").map { DepthLevel(it.obj().double("price"), it.obj().double("quantity")) },
)
