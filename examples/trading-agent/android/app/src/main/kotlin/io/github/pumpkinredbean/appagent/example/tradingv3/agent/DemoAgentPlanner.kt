package io.github.pumpkinredbean.appagent.example.tradingv3.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentPlan(
    val tool: String? = null,
    val input: JsonObject = buildJsonObject {},
    val runningLabel: String = "",
    val completedLabel: String = "",
    val directResponse: String? = null,
)

/**
 * Replaceable offline planner used so the UX can be evaluated without model
 * credentials. The tool manifest and runtime path are the same ones a model
 * gateway consumes.
 */
class DemoAgentPlanner {
    fun plan(prompt: String, currentSymbol: String?): AgentPlan {
        val lower = prompt.lowercase()
        val symbol = symbolIn(lower) ?: currentSymbol ?: "BTC"
        val budget = amountIn(lower) ?: 1_000.0
        val wantsVisual = Regex("explore|show|open|view|detail|chart|visual").containsMatchIn(lower)

        return when {
            Regex("what can|capabilit|available tools|supported tools").containsMatchIn(lower) -> AgentPlan(
                directResponse = "I can look up markets, explore assets, analyze the portfolio, compare allocations, and review local orders. I answer factual questions in text and open app UI when you ask to explore.",
            )
            Regex("activity|orders|order history|recent orders").containsMatchIn(lower) -> visual(
                "visualize_order_activity",
                buildJsonObject {},
                "Reading recent local orders",
                "Opened order activity.",
            )
            Regex("buy|order|draft").containsMatchIn(lower) -> visual(
                "prepare_order",
                orderInput(symbol, budget),
                "Preparing a $symbol order draft",
                "Opened the order review. Final execution requires approval on this device.",
            )
            Regex("add|allocate|allocation|compare").containsMatchIn(lower) &&
                Regex("usdt|usd|dollar|[0-9]").containsMatchIn(lower) -> visual(
                "visualize_allocation",
                orderInput(symbol, budget),
                "Comparing an allocation with the current portfolio",
                "Opened a comparison of how the same budget changes current exposure.",
            )
            Regex("p&l|pnl|return|profit|performance").containsMatchIn(lower) && wantsVisual -> visual(
                "visualize_portfolio_return",
                buildJsonObject {},
                "Building the return contribution view",
                "Opened return contribution.",
            )
            Regex("p&l|pnl|return|profit|performance").containsMatchIn(lower) -> AgentPlan(
                tool = "get_portfolio_return",
                runningLabel = "Calculating current positions against public market prices",
                completedLabel = "Calculated portfolio return",
            )
            Regex("portfolio|position|holdings|balance").containsMatchIn(lower) -> visual(
                "visualize_portfolio",
                buildJsonObject {},
                "Reading the app's local portfolio",
                "Opened the current portfolio.",
            )
            Regex("symbol|market list|markets|search|find").containsMatchIn(lower) -> AgentPlan(
                tool = "get_market_symbols",
                runningLabel = "Finding supported markets",
                completedLabel = "Found supported symbols",
            )
            symbolIn(lower) != null && wantsVisual -> visual(
                "visualize_market_asset",
                buildJsonObject { put("symbol", symbol) },
                "Loading public $symbol prices and market depth",
                "Opened $symbol details. The live subscription remains active only for this turn.",
            )
            symbolIn(lower) != null -> AgentPlan(
                tool = "get_market_quote",
                input = buildJsonObject { put("symbol", symbol) },
                runningLabel = "Checking the current $symbol price",
                completedLabel = "Retrieved the $symbol quote",
            )
            else -> AgentPlan(
                directResponse = "You can continue from the context currently on screen. Try asking: ‘What is my return today?’, ‘Show Bitcoin details’, or ‘Compare adding 1,000 USDT.’",
            )
        }
    }

    private fun visual(
        tool: String,
        input: JsonObject,
        running: String,
        completed: String,
    ) = AgentPlan(tool, input, running, completed)

    private fun orderInput(symbol: String, budget: Double) = buildJsonObject {
        put("symbol", symbol)
        put("budget", budget)
    }

    private fun symbolIn(value: String): String? = when {
        Regex("\\bbtc\\b|bitcoin").containsMatchIn(value) -> "BTC"
        Regex("\\beth\\b|ethereum|ether").containsMatchIn(value) -> "ETH"
        Regex("\\bsol\\b|solana").containsMatchIn(value) -> "SOL"
        Regex("\\bbnb\\b|binance coin").containsMatchIn(value) -> "BNB"
        else -> null
    }

    private fun amountIn(value: String): Double? {
        val match = Regex("([0-9][0-9,.]*)").find(value) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }
}
