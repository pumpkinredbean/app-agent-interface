package io.github.pumpkinredbean.appagent.example.tradingv3.tools

import io.github.pumpkinredbean.appagent.ToolApprovalController
import io.github.pumpkinredbean.appagent.example.tradingv3.data.MarketRepository
import io.github.pumpkinredbean.appagent.example.tradingv3.data.PortfolioRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TradingToolRuntimeTest {
    @Test
    fun unsupportedSymbolFailsValidationInsteadOfReturningAnotherAsset() = runBlocking {
        val approvals = ToolApprovalController()
        try {
            val tools = TradingToolRuntime(
                markets = MarketRepository(),
                portfolio = PortfolioRepository(),
                approvalController = approvals,
            )

            val result = tools.invoke(
                "get_market_quote",
                buildJsonObject { put("symbol", "DOGE") },
            )

            assertFalse(result.ok)
            assertEquals("INVALID_INPUT", result.error?.code)
        } finally {
            approvals.close()
        }
    }
}
