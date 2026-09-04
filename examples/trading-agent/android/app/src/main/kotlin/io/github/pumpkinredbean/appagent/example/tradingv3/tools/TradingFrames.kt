package io.github.pumpkinredbean.appagent.example.tradingv3.tools

import io.github.pumpkinredbean.appagent.example.tradingv3.model.LocalOrder
import io.github.pumpkinredbean.appagent.example.tradingv3.model.MarketAsset
import io.github.pumpkinredbean.appagent.example.tradingv3.model.PortfolioSnapshot
import io.github.pumpkinredbean.appagent.ui.UiFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object TradingFrameTags {
    const val OVERVIEW = "market.overview.v1"
    const val ASSET = "market.asset.v1"
    const val RETURNS = "portfolio.return.v1"
    const val PORTFOLIO = "portfolio.positions.v1"
    const val ALLOCATION = "portfolio.allocation.v1"
    const val ORDER_REVIEW = "order.review.v1"
    const val ORDERS = "orders.activity.v1"
}

object TradingFrames {
    fun overview(markets: List<MarketAsset>, portfolio: PortfolioSnapshot): UiFrame {
        val returns = returnSummary(markets, portfolio)
        return UiFrame(
            tag = TradingFrameTags.OVERVIEW,
            params = buildJsonObject {},
            payload = buildJsonObject {
                put("cash", portfolio.cash)
                put("returnAmount", returns.amount)
                put("returnPercent", returns.percent)
                put("markets", buildJsonArray { markets.forEach { add(it.marketJson()) } })
            },
        )
    }

    fun asset(market: MarketAsset, exposure: Double): UiFrame = UiFrame(
        tag = TradingFrameTags.ASSET,
        params = buildJsonObject { put("symbol", market.symbol) },
        payload = market.marketJson(exposure),
    )

    fun returns(markets: List<MarketAsset>, portfolio: PortfolioSnapshot): UiFrame {
        val summary = returnSummary(markets, portfolio)
        return UiFrame(
            tag = TradingFrameTags.RETURNS,
            params = buildJsonObject { put("period", "today") },
            payload = buildJsonObject {
                put("amount", summary.amount)
                put("percent", summary.percent)
                put("drivers", summary.drivers)
            },
        )
    }

    fun portfolio(markets: List<MarketAsset>, portfolio: PortfolioSnapshot): UiFrame = UiFrame(
        tag = TradingFrameTags.PORTFOLIO,
        params = buildJsonObject {},
        payload = buildJsonObject {
            put("cash", portfolio.cash)
            put("positions", positionJson(markets, portfolio))
            put("orderCount", portfolio.orders.size)
        },
    )

    fun allocation(
        markets: List<MarketAsset>,
        portfolio: PortfolioSnapshot,
        candidate: String,
        budget: Double,
    ): UiFrame {
        val values = positionValues(markets, portfolio)
        val total = values.values.sum().coerceAtLeast(1.0)
        return UiFrame(
            tag = TradingFrameTags.ALLOCATION,
            params = buildJsonObject {
                put("candidate", candidate)
                put("budget", budget)
            },
            payload = buildJsonObject {
                put("candidate", candidate)
                put("budget", budget)
                put("cash", portfolio.cash)
                put("candidates", buildJsonArray {
                    markets.filter { it.symbol in setOf("BTC", "ETH") }.forEach { market ->
                        val current = (values[market.symbol] ?: 0.0) / total * 100.0
                        add(buildJsonObject {
                            put("symbol", market.symbol)
                            put("name", market.name)
                            put("changePercent", market.changePercent)
                            put("currentExposure", current)
                            put("afterExposure", ((values[market.symbol] ?: 0.0) + budget) / (total + budget) * 100.0)
                        })
                    }
                })
            },
        )
    }

    fun orderReview(
        market: MarketAsset,
        portfolio: PortfolioSnapshot,
        budget: Double,
        status: String,
        order: LocalOrder? = null,
    ): UiFrame = UiFrame(
        tag = TradingFrameTags.ORDER_REVIEW,
        params = buildJsonObject {
            put("symbol", market.symbol)
            put("budget", budget)
        },
        payload = buildJsonObject {
            put("symbol", market.symbol)
            put("name", market.name)
            put("budget", budget)
            put("price", market.price)
            put("estimatedQuantity", budget / market.price)
            put("availableCash", portfolio.cash)
            put("status", status)
            order?.let {
                put("orderId", it.id)
                put("filledQuantity", it.quantity)
                put("filledPrice", it.price)
            }
        },
    )

    fun orders(portfolio: PortfolioSnapshot): UiFrame = UiFrame(
        tag = TradingFrameTags.ORDERS,
        params = buildJsonObject {},
        payload = buildJsonObject {
            put("orders", buildJsonArray {
                portfolio.orders.forEach { order ->
                    add(buildJsonObject {
                        put("id", order.id)
                        put("symbol", order.symbol)
                        put("quantity", order.quantity)
                        put("budget", order.budget)
                        put("price", order.price)
                    })
                }
            })
        },
    )

    fun exposure(symbol: String, markets: List<MarketAsset>, portfolio: PortfolioSnapshot): Double {
        val values = positionValues(markets, portfolio)
        return (values[symbol] ?: 0.0) / values.values.sum().coerceAtLeast(1.0) * 100.0
    }

    fun returnSummary(markets: List<MarketAsset>, portfolio: PortfolioSnapshot): ReturnSummary {
        val marketBySymbol = markets.associateBy(MarketAsset::symbol)
        val drivers = buildJsonArray {
            portfolio.positions.forEach { position ->
                val market = marketBySymbol[position.symbol] ?: return@forEach
                val previous = market.price / (1.0 + market.changePercent / 100.0)
                val pnl = (market.price - previous) * position.quantity
                add(buildJsonObject {
                    put("symbol", position.symbol)
                    put("name", position.name)
                    put("pnl", pnl)
                    put("changePercent", market.changePercent)
                })
            }
        }
        val amount = drivers.sumOf { it.jsonObject.double("pnl") }
        val base = portfolio.positions.sumOf { position ->
            val market = marketBySymbol[position.symbol] ?: return@sumOf 0.0
            market.price / (1.0 + market.changePercent / 100.0) * position.quantity
        }.coerceAtLeast(1.0)
        return ReturnSummary(amount, amount / base * 100.0, drivers)
    }

    private fun positionValues(
        markets: List<MarketAsset>,
        portfolio: PortfolioSnapshot,
    ): Map<String, Double> {
        val prices = markets.associate { it.symbol to it.price }
        return portfolio.positions.associate { it.symbol to it.quantity * (prices[it.symbol] ?: it.averageCost) }
    }

    private fun positionJson(markets: List<MarketAsset>, portfolio: PortfolioSnapshot): JsonArray {
        val values = positionValues(markets, portfolio)
        val total = values.values.sum().coerceAtLeast(1.0)
        return buildJsonArray {
            portfolio.positions.forEach { position ->
                val value = values[position.symbol] ?: 0.0
                add(buildJsonObject {
                    put("symbol", position.symbol)
                    put("name", position.name)
                    put("quantity", position.quantity)
                    put("averageCost", position.averageCost)
                    put("value", value)
                    put("exposure", value / total * 100.0)
                })
            }
        }
    }

    private fun MarketAsset.marketJson(exposure: Double? = null): JsonObject = buildJsonObject {
        put("symbol", symbol)
        put("name", name)
        put("pair", pair)
        put("price", price)
        put("changePercent", changePercent)
        put("high", high)
        put("low", low)
        put("quoteVolume", quoteVolume)
        exposure?.let { put("exposure", it) }
        put("series", buildJsonArray { series.forEach { add(it) } })
        put("bids", buildJsonArray { bids.forEach { add(it.depthJson()) } })
        put("asks", buildJsonArray { asks.forEach { add(it.depthJson()) } })
    }

    private fun io.github.pumpkinredbean.appagent.example.tradingv3.model.DepthLevel.depthJson(): JsonObject = buildJsonObject {
        put("price", price)
        put("quantity", quantity)
    }
}

data class ReturnSummary(val amount: Double, val percent: Double, val drivers: JsonArray)

internal fun JsonObject.double(name: String): Double = getValue(name).toString().toDouble()
