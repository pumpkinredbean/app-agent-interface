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
import androidx.compose.foundation.shape.CircleShape
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
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Metric
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.Panel
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.money
import io.github.pumpkinredbean.appagent.ui.UiFrame
import io.github.pumpkinredbean.appagent.ui.UiFrameLifecycleState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Composable
fun OrderReviewFrame(
    frame: UiFrame,
    lifecycle: UiFrameLifecycleState,
    onAction: (FrameAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val payload = frame.payload.jsonObject
    val symbol = payload.string("symbol")
    val budget = payload.double("budget")
    val filled = payload.string("status") == "filled"
    FrameColumn(modifier) {
        LiveLifecycleRow(lifecycle, onAction)
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Eyebrow(if (filled) "Local result" else "Reviewable draft")
            MarketText(if (filled) "Portfolio updated" else "Buy $symbol", size = 30, weight = FontWeight.Bold)
            MarketText(
                if (filled) "The approved outcome is now part of this app session."
                else "Nothing executes until this device completes secure approval.",
                color = LocalMarketColors.current.textSoft,
                size = 15,
            )
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(17.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Metric(if (filled) "FILLED QUANTITY" else "ESTIMATED QUANTITY", "${String.format("%.5f", if (filled) payload.double("filledQuantity") else payload.double("estimatedQuantity"))} $symbol")
                    MarketText("${money(budget)} USDT", size = 20, weight = FontWeight.Bold, family = FontFamily.Monospace)
                }
                Divider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("ORDER TYPE", "Market · simulated")
                    Metric("PRICE", money(if (filled) payload.double("filledPrice") else payload.double("price")))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("EXECUTION", "Process-local")
                    Metric("AVAILABLE CASH", "${money(payload.double("availableCash"))} USDT")
                }
                if (filled) {
                    Divider()
                    Metric("ORDER ID", payload.string("orderId"), valueColor = LocalMarketColors.current.accent)
                }
            }
        }

        Panel(Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Box(
                    Modifier.size(54.dp).clip(CircleShape).background(if (filled) LocalMarketColors.current.accent else LocalMarketColors.current.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    MarketText(if (filled) "✓" else "◇", color = if (filled) LocalMarketColors.current.page else LocalMarketColors.current.accent, size = 24, weight = FontWeight.Bold)
                }
                MarketText(if (filled) "Approved on this device" else "This phone makes the decision", size = 20, weight = FontWeight.Bold)
                MarketText(
                    if (filled) {
                        "Only the approved result entered the tool runtime. Authentication data stayed inside the app boundary."
                    } else {
                        "The app-owned secure keypad keeps its input outside the agent and ToolCall context."
                    },
                    color = LocalMarketColors.current.textSoft,
                    size = 14,
                    align = androidx.compose.ui.text.style.TextAlign.Center,
                )
                ApprovalTrack(filled)
                ActionButton(
                    text = if (filled) "View updated portfolio" else "Continue securely",
                    onClick = {
                        if (filled) onAction(FrameAction.OpenPortfolio)
                        else onAction(FrameAction.SubmitOrder(symbol, budget))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ApprovalTrack(complete: Boolean) {
    val labels = listOf("Review", "Secure keypad", "Apply")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(if (complete || index == 0) LocalMarketColors.current.accent else LocalMarketColors.current.textDim),
                )
                Spacer(Modifier.height(5.dp))
                MarketText(label, color = if (complete || index == 0) LocalMarketColors.current.text else LocalMarketColors.current.textDim, size = 10)
            }
            if (index < labels.lastIndex) {
                Box(Modifier.weight(1f).padding(horizontal = 7.dp).height(1.dp).background(LocalMarketColors.current.lineStrong))
            }
        }
    }
}

@Composable
fun PortfolioFrame(
    frame: UiFrame,
    lifecycle: UiFrameLifecycleState,
    onAction: (FrameAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val payload = frame.payload.jsonObject
    FrameColumn(modifier) {
        LiveLifecycleRow(lifecycle, onAction)
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Eyebrow("Local sample account")
            MarketText("Portfolio positions", size = 29, weight = FontWeight.Bold)
        }
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Metric("AVAILABLE CASH", "${money(payload.double("cash"))} USDT", valueColor = LocalMarketColors.current.accent)
                Metric("POSITIONS", payload.array("positions").size.toString())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            payload.array("positions").forEach { item ->
                val position = item.obj()
                PositionRow(position) { onAction(FrameAction.OpenAsset(position.string("symbol"))) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Explain return", { onAction(FrameAction.ShowReturn) }, Modifier.weight(1f))
            ActionButton("Recent activity", { onAction(FrameAction.OpenOrders) }, Modifier.weight(1f), primary = false)
        }
    }
}

@Composable
private fun PositionRow(position: JsonObject, onClick: () -> Unit) {
    val colors = LocalMarketColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(colors.raised).clickable(onClick = onClick).padding(17.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                MarketText(position.string("symbol"), size = 19, weight = FontWeight.Bold)
                MarketText(position.string("name"), color = colors.textDim, size = 12)
            }
            Column(horizontalAlignment = Alignment.End) {
                MarketText("${money(position.double("value"))} USDT", size = 15, weight = FontWeight.Bold, family = FontFamily.Monospace)
                MarketText("${money(position.double("exposure"), 1)}% exposure", color = colors.accent, size = 12)
            }
        }
        Divider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("QUANTITY", String.format("%.4f %s", position.double("quantity"), position.string("symbol")))
            Metric("AVERAGE COST", money(position.double("averageCost")))
        }
    }
}

@Composable
fun OrdersFrame(
    frame: UiFrame,
    lifecycle: UiFrameLifecycleState,
    onAction: (FrameAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val orders = frame.payload.jsonObject.array("orders")
    FrameColumn(modifier) {
        LiveLifecycleRow(lifecycle, onAction)
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Eyebrow("Process-local activity")
            MarketText("Recent orders", size = 29, weight = FontWeight.Bold)
            MarketText("These fills live only in memory and never reach an exchange.", color = LocalMarketColors.current.textSoft, size = 14)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            orders.forEach { item ->
                val order = item.obj()
                Panel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                MarketText("${order.string("symbol")} / USDT", size = 18, weight = FontWeight.Bold)
                                MarketText("Buy · market", color = LocalMarketColors.current.textDim, size = 12)
                            }
                            MarketText("FILLED", color = LocalMarketColors.current.accent, size = 12, weight = FontWeight.Bold)
                        }
                        Divider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Metric("FILLED", String.format("%.5f", order.double("quantity")))
                            Metric("VALUE", "${money(order.double("budget"))} USDT")
                        }
                        MarketText(order.string("id"), color = LocalMarketColors.current.textDim, size = 10, family = FontFamily.Monospace)
                    }
                }
            }
        }
        ActionButton("Open portfolio", { onAction(FrameAction.OpenPortfolio) }, Modifier.fillMaxWidth(), primary = false)
    }
}
