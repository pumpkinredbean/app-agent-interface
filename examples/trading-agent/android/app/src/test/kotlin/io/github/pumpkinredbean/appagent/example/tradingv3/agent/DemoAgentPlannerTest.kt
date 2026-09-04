package io.github.pumpkinredbean.appagent.example.tradingv3.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DemoAgentPlannerTest {
    private val planner = DemoAgentPlanner()

    @Test
    fun factualReturnUsesGetWithoutOpeningUi() {
        val plan = planner.plan("What is my return today?", currentSymbol = "BTC")

        assertEquals("get_portfolio_return", plan.tool)
    }

    @Test
    fun explicitAssetViewPreservesRequestedSymbol() {
        val plan = planner.plan("Show Bitcoin details", currentSymbol = "BNB")

        assertEquals("visualize_market_asset", plan.tool)
        assertEquals("BTC", plan.input["symbol"].toString().trim('"'))
    }

    @Test
    fun plainAssetQuestionUsesGet() {
        val plan = planner.plan("What is the ETH price?", currentSymbol = null)

        assertEquals("get_market_quote", plan.tool)
        assertEquals("ETH", plan.input["symbol"].toString().trim('"'))
    }

    @Test
    fun unknownPromptDoesNotInventAToolCall() {
        val plan = planner.plan("hello", currentSymbol = null)

        assertNull(plan.tool)
    }
}
