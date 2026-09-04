package io.github.pumpkinredbean.appagent.example.tradingv3.tools

import io.github.pumpkinredbean.appagent.ToolApproval
import io.github.pumpkinredbean.appagent.ToolApprovalController
import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolExecutionException
import io.github.pumpkinredbean.appagent.ToolJson
import io.github.pumpkinredbean.appagent.ToolResult
import io.github.pumpkinredbean.appagent.ToolRuntime
import io.github.pumpkinredbean.appagent.ToolSpec
import io.github.pumpkinredbean.appagent.example.tradingv3.data.MarketRepository
import io.github.pumpkinredbean.appagent.example.tradingv3.data.PortfolioRepository
import io.github.pumpkinredbean.appagent.ui.UiToolOutput
import io.github.pumpkinredbean.appagent.ui.registerUi
import java.util.UUID
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class TradingToolRuntime(
    private val markets: MarketRepository,
    private val portfolio: PortfolioRepository,
    approvalController: ToolApprovalController,
) {
    val runtime = ToolRuntime(
        approvalDecisionHandler = approvalController,
    )

    init {
        registerTools()
    }

    suspend fun invoke(name: String, input: kotlinx.serialization.json.JsonObject = buildJsonObject {}): ToolResult =
        runtime.execute(
            ToolCall(UUID.randomUUID().toString(), name, input),
            attributes = mapOf("caller" to "in-app-agent"),
        )

    private fun registerTools() {
        runtime.register(
            spec("get_market_symbols", "Searches the market symbols supported by this service. Use it before a quote when the requested symbol is unknown.", EMPTY_SCHEMA),
        ) { _, _ ->
            buildJsonObject {
                put("symbols", buildJsonArray { markets.supported().forEach { add(it.symbol) } })
            }
        }

        runtime.register(
            spec("get_market_quote", "Gets the latest public Spot quote for one symbol. Use for factual price questions that do not need an interactive interface.", SYMBOL_SCHEMA),
        ) { input, _ ->
            val market = markets.quote(input.string("symbol"))
            buildJsonObject {
                put("symbol", market.symbol)
                put("price", market.price)
                put("changePercent", market.changePercent)
            }
        }

        runtime.register(
            spec("get_portfolio_return", "Gets the signed-in local sample portfolio return. Use for a factual return question; this tool does not open UI.", EMPTY_SCHEMA),
        ) { _, _ ->
            val summary = TradingFrames.returnSummary(markets.supported(), portfolio.snapshot())
            buildJsonObject {
                put("amount", summary.amount)
                put("percent", summary.percent)
                put("currency", "USDT")
            }
        }

        runtime.registerUi(
            spec("visualize_market_overview", "Opens the personalized market overview. Use when the user asks to browse or explore the service home.", EMPTY_SCHEMA),
        ) { _, _ ->
            UiToolOutput(
                modelOutput = buildJsonObject { put("summary", "Personalized overview opened") },
                frame = TradingFrames.overview(markets.supported(), portfolio.snapshot()),
            )
        }

        runtime.registerUi(
            spec("visualize_market_asset", "Opens an interactive live market view for one supported symbol. Use when the user explicitly asks to show or inspect an asset.", SYMBOL_SCHEMA),
        ) { input, _ ->
            val symbol = input.string("symbol")
            val market = markets.quote(symbol)
            val snapshot = portfolio.snapshot()
            UiToolOutput(
                modelOutput = buildJsonObject {
                    put("summary", "${market.symbol} market view opened")
                    put("price", market.price)
                },
                frame = TradingFrames.asset(
                    market,
                    TradingFrames.exposure(market.symbol, markets.supported(), snapshot),
                ),
            )
        }

        runtime.registerUi(
            spec("visualize_portfolio", "Opens the local sample portfolio with positions and cash. Use when the user asks to view or explore the portfolio.", EMPTY_SCHEMA),
        ) { _, _ ->
            val snapshot = portfolio.snapshot()
            UiToolOutput(
                modelOutput = buildJsonObject { put("positionCount", snapshot.positions.size) },
                frame = TradingFrames.portfolio(markets.supported(), snapshot),
            )
        }

        runtime.registerUi(
            spec("visualize_portfolio_return", "Opens an interactive explanation of return drivers. Use only when the user asks to show, compare, or explore return details.", EMPTY_SCHEMA),
        ) { _, _ ->
            val snapshot = portfolio.snapshot()
            UiToolOutput(
                modelOutput = buildJsonObject { put("summary", "Return drivers opened") },
                frame = TradingFrames.returns(markets.supported(), snapshot),
            )
        }

        runtime.registerUi(
            spec("visualize_allocation", "Opens a reviewable allocation comparison using the current local portfolio. Use when the user wants to compare a possible allocation.", ALLOCATION_SCHEMA),
        ) { input, _ ->
            val symbol = input.string("symbol")
            val budget = input.number("budget")
            UiToolOutput(
                modelOutput = buildJsonObject { put("summary", "$symbol allocation comparison opened") },
                frame = TradingFrames.allocation(markets.supported(), portfolio.snapshot(), symbol, budget),
            )
        }

        runtime.registerUi(
            spec("prepare_order", "Prepares a simulated local order for review without executing it. Use when the user asks to prepare or review a trade.", ORDER_SCHEMA),
        ) { input, _ ->
            val symbol = input.string("symbol")
            val budget = input.number("budget")
            val market = markets.quote(symbol)
            UiToolOutput(
                modelOutput = buildJsonObject { put("summary", "Local order draft prepared") },
                frame = TradingFrames.orderReview(market, portfolio.snapshot(), budget, "draft"),
            )
        }

        runtime.registerUi(
            spec("submit_order", "Executes a previously reviewed simulated local order. Use only after the user explicitly asks to continue with the reviewed order.", ORDER_SCHEMA),
            approval = ToolApproval.MOBILE,
        ) { input, _ ->
            val symbol = input.string("symbol")
            val budget = input.number("budget")
            val market = markets.quote(symbol)
            val order = portfolio.fill(symbol, budget, market.price)
            UiToolOutput(
                modelOutput = buildJsonObject {
                    put("status", "filled")
                    put("orderId", order.id)
                },
                frame = TradingFrames.orderReview(market, portfolio.snapshot(), budget, "filled", order),
            )
        }

        runtime.registerUi(
            spec("visualize_order_activity", "Opens the process-local simulated order history. Use when the user asks to view recent activity.", EMPTY_SCHEMA),
        ) { _, _ ->
            val snapshot = portfolio.snapshot()
            UiToolOutput(
                modelOutput = buildJsonObject { put("orderCount", snapshot.orders.size) },
                frame = TradingFrames.orders(snapshot),
            )
        }
    }

    private fun spec(name: String, description: String, schema: String): ToolSpec =
        ToolSpec(name, description, ToolJson.parseObject(schema))

    private fun kotlinx.serialization.json.JsonObject.string(name: String): String {
        val symbol = getValue(name).jsonPrimitive.content.uppercase().removeSuffix("USDT")
        if (!markets.supports(symbol)) {
            throw ToolExecutionException(
                code = "UNSUPPORTED_SYMBOL",
                message = "Unsupported symbol: $symbol. Call get_market_symbols before retrying.",
            )
        }
        return symbol
    }

    private fun kotlinx.serialization.json.JsonObject.number(name: String): Double =
        getValue(name).jsonPrimitive.double

    private companion object {
        const val EMPTY_SCHEMA = """{"type":"object","properties":{},"additionalProperties":false}"""
        const val SYMBOL_SCHEMA = """{"type":"object","properties":{"symbol":{"type":"string","description":"Supported base asset symbol","enum":["BTC","ETH","SOL","BNB"]}},"required":["symbol"],"additionalProperties":false}"""
        const val ALLOCATION_SCHEMA = """{"type":"object","properties":{"symbol":{"type":"string","enum":["BTC","ETH","SOL","BNB"]},"budget":{"type":"number","minimum":1}},"required":["symbol","budget"],"additionalProperties":false}"""
        const val ORDER_SCHEMA = """{"type":"object","properties":{"symbol":{"type":"string","enum":["BTC","ETH","SOL","BNB"]},"budget":{"type":"number","minimum":1}},"required":["symbol","budget"],"additionalProperties":false}"""
    }
}
