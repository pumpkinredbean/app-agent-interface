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
        val wantsVisual = Regex("보여|열어|화면|상세|차트|explore|show|open|visual").containsMatchIn(lower)

        return when {
            Regex("할 수|도구|기능|what can|capabilit").containsMatchIn(lower) -> AgentPlan(
                directResponse = "시장 조회, 종목 탐색, 포트폴리오 분석, 배분 비교와 로컬 주문 검토를 할 수 있어요. 사실 질문은 텍스트로 답하고, 탐색을 요청하면 앱 UI를 열어요.",
            )
            Regex("주문.?내역|최근.?주문|활동|activity|orders").containsMatchIn(lower) -> visual(
                "visualize_order_activity",
                buildJsonObject {},
                "최근 로컬 주문을 읽고 있어요",
                "주문 내역을 열었어요.",
            )
            Regex("매수|주문|buy|order|draft").containsMatchIn(lower) -> visual(
                "prepare_order",
                orderInput(symbol, budget),
                "$symbol 주문안을 계산하고 있어요",
                "실행 전 주문안을 열었어요. 최종 실행은 이 기기에서만 승인할 수 있어요.",
            )
            Regex("추가|배분|비교|allocate|compare").containsMatchIn(lower) &&
                Regex("usdt|달러|[0-9]").containsMatchIn(lower) -> visual(
                "visualize_allocation",
                orderInput(symbol, budget),
                "현재 포트폴리오 안에서 배분을 비교하고 있어요",
                "같은 예산이 현재 노출도에 미치는 영향을 비교 화면에 정리했어요.",
            )
            Regex("수익|손익|p&l|pnl|return|profit").containsMatchIn(lower) && wantsVisual -> visual(
                "visualize_portfolio_return",
                buildJsonObject {},
                "수익 기여도를 구성하고 있어요",
                "수익 기여도 화면을 열었어요.",
            )
            Regex("수익|손익|p&l|pnl|return|profit").containsMatchIn(lower) -> AgentPlan(
                tool = "get_portfolio_return",
                runningLabel = "현재 포지션과 공개 시세를 계산하고 있어요",
                completedLabel = "수익률을 계산했어요",
            )
            Regex("포트폴리오|보유|잔고|portfolio|position").containsMatchIn(lower) -> visual(
                "visualize_portfolio",
                buildJsonObject {},
                "앱의 로컬 포트폴리오를 읽고 있어요",
                "현재 포트폴리오를 열었어요.",
            )
            Regex("종목|심볼|symbol|market list|검색").containsMatchIn(lower) -> AgentPlan(
                tool = "get_market_symbols",
                runningLabel = "지원하는 시장을 찾고 있어요",
                completedLabel = "지원 종목을 확인했어요",
            )
            symbolIn(lower) != null && wantsVisual -> visual(
                "visualize_market_asset",
                buildJsonObject { put("symbol", symbol) },
                "$symbol 공개 시세와 호가를 불러오고 있어요",
                "$symbol 상세 화면을 열었어요. 실시간 구독은 이 턴에만 활성화돼요.",
            )
            symbolIn(lower) != null -> AgentPlan(
                tool = "get_market_quote",
                input = buildJsonObject { put("symbol", symbol) },
                runningLabel = "$symbol 현재가를 확인하고 있어요",
                completedLabel = "$symbol 시세를 확인했어요",
            )
            else -> AgentPlan(
                directResponse = "현재 화면의 컨텍스트를 이어서 물어볼 수 있어요. 예를 들어 ‘지금 수익률 확인해줘’, ‘비트코인 상세 보여줘’, ‘1,000 USDT 추가를 비교해줘’라고 해보세요.",
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
        Regex("\\bbtc\\b|비트").containsMatchIn(value) -> "BTC"
        Regex("\\beth\\b|이더").containsMatchIn(value) -> "ETH"
        Regex("\\bsol\\b|솔라나").containsMatchIn(value) -> "SOL"
        Regex("\\bbnb\\b").containsMatchIn(value) -> "BNB"
        else -> null
    }

    private fun amountIn(value: String): Double? {
        val match = Regex("([0-9][0-9,.]*)").find(value) ?: return null
        return match.groupValues[1].replace(",", "").toDoubleOrNull()
    }
}
