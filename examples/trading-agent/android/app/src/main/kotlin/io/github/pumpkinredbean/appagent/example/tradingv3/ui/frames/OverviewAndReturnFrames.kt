package io.github.pumpkinredbean.appagent.example.tradingv3.ui.frames

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.pumpkinredbean.appagent.example.tradingv3.model.FrameAction
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.ActionButton
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Divider
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Eyebrow
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.LocalMarketColors
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.MarketText
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Panel
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.money
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.signedMoney
import io.github.pumpkinredbean.appagent.ui.UiFrame
import io.github.pumpkinredbean.appagent.ui.UiFrameLifecycleState
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs

@Composable
fun OverviewFrame(
    frame: UiFrame,
    lifecycle: UiFrameLifecycleState,
    onAction: (FrameAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val payload = frame.payload.jsonObject
    val markets = payload.array("markets")
    FrameColumn(modifier) {
        LiveLifecycleRow(lifecycle, onAction)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Portfolio-aware market brief")
            MarketText("Today’s move matters because you already own it.", size = 28, weight = FontWeight.Bold)
            MarketText(
                "Market movement is connected to your current positions, so the next useful action is one tap away.",
                color = LocalMarketColors.current.textSoft,
                size = 15,
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MarketText("TODAY’S PORTFOLIO RESULT", color = LocalMarketColors.current.textDim, size = 11, family = FontFamily.Monospace)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    MarketText(signedMoney(payload.double("returnAmount")), size = 27, weight = FontWeight.Bold, family = FontFamily.Monospace)
                    ChangeText(payload.double("returnPercent"), size = 17)
                }
                Divider()
                MarketText("BTC currently explains most of the move.", color = LocalMarketColors.current.textSoft, size = 14)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Explain return", { onAction(FrameAction.ShowReturn) }, Modifier.weight(1f))
            ActionButton("Compare 1,000", { onAction(FrameAction.CompareAllocation()) }, Modifier.weight(1f), primary = false)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("Markets")
            markets.forEach { item ->
                val market = item.obj()
                MarketRow(
                    symbol = market.string("symbol"),
                    name = market.string("name"),
                    price = market.double("price"),
                    change = market.double("changePercent"),
                    onClick = { onAction(FrameAction.OpenAsset(market.string("symbol"))) },
                )
            }
        }
    }
}

@Composable
private fun MarketRow(
    symbol: String,
    name: String,
    price: Double,
    change: Double,
    onClick: () -> Unit,
) {
    val colors = LocalMarketColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.raised)
            .clickable(onClick = onClick)
            .padding(15.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(colors.soft),
            contentAlignment = Alignment.Center,
        ) { MarketText(symbol.take(1), size = 16, weight = FontWeight.Bold) }
        Column(Modifier.weight(1f)) {
            MarketText(symbol, size = 16, weight = FontWeight.Bold)
            MarketText(name, color = colors.textDim, size = 12)
        }
        Column(horizontalAlignment = Alignment.End) {
            MarketText(money(price), size = 15, weight = FontWeight.SemiBold, family = FontFamily.Monospace)
            ChangeText(change, 12)
        }
    }
}

@Composable
fun ReturnsFrame(
    frame: UiFrame,
    lifecycle: UiFrameLifecycleState,
    onAction: (FrameAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val payload = frame.payload.jsonObject
    val drivers = payload.array("drivers")
    val maxPnl = drivers.maxOfOrNull { kotlin.math.abs(it.obj().double("pnl")) }?.coerceAtLeast(1.0) ?: 1.0
    FrameColumn(modifier) {
        LiveLifecycleRow(lifecycle, onAction)
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Eyebrow("Today’s unrealized result")
            MarketText("${signedMoney(payload.double("amount"))} USDT", size = 31, weight = FontWeight.Bold, family = FontFamily.Monospace)
            ChangeText(payload.double("percent"), size = 17)
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                drivers.forEachIndexed { index, item ->
                    val driver = item.obj()
                    val pnl = driver.double("pnl")
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                MarketText(driver.string("symbol"), size = 16, weight = FontWeight.Bold)
                                MarketText(driver.string("name"), color = LocalMarketColors.current.textDim, size = 12)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                MarketText(signedMoney(pnl), size = 15, weight = FontWeight.Bold, family = FontFamily.Monospace)
                                ChangeText(driver.double("changePercent"), 12)
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(9.dp)).background(LocalMarketColors.current.soft)) {
                            Box(
                                Modifier
                                    .fillMaxWidth((abs(pnl) / maxPnl).toFloat().coerceIn(.05f, 1f))
                                    .height(5.dp)
                                    .background(if (pnl >= 0) LocalMarketColors.current.accent else LocalMarketColors.current.red),
                            )
                        }
                    }
                    if (index != drivers.lastIndex) Divider()
                }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Eyebrow("What matters")
                MarketText("Healthy return. Concentrated driver.", size = 21, weight = FontWeight.Bold)
                MarketText("BTC contributes most of today’s change and is already your largest exposure.", color = LocalMarketColors.current.textSoft, size = 14)
                Spacer(Modifier.height(2.dp))
                ActionButton("Evaluate the next 1,000", { onAction(FrameAction.CompareAllocation()) }, Modifier.fillMaxWidth())
            }
        }
    }
}
