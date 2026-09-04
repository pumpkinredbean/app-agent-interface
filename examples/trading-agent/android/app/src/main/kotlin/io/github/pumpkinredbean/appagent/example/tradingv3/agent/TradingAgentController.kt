package io.github.pumpkinredbean.appagent.example.tradingv3.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.pumpkinredbean.appagent.ToolApprovalController
import io.github.pumpkinredbean.appagent.ToolResult
import io.github.pumpkinredbean.appagent.example.tradingv3.data.MarketRepository
import io.github.pumpkinredbean.appagent.example.tradingv3.data.PortfolioRepository
import io.github.pumpkinredbean.appagent.example.tradingv3.model.AgentMessage
import io.github.pumpkinredbean.appagent.example.tradingv3.model.AgentSessionUiState
import io.github.pumpkinredbean.appagent.example.tradingv3.model.FrameAction
import io.github.pumpkinredbean.appagent.example.tradingv3.model.MessageRole
import io.github.pumpkinredbean.appagent.example.tradingv3.model.ToolProgress
import io.github.pumpkinredbean.appagent.example.tradingv3.tools.TradingFrameTags
import io.github.pumpkinredbean.appagent.example.tradingv3.tools.TradingFrames
import io.github.pumpkinredbean.appagent.example.tradingv3.tools.TradingToolRuntime
import io.github.pumpkinredbean.appagent.ui.UiFrame
import io.github.pumpkinredbean.appagent.ui.UiFrameNavigationMode
import io.github.pumpkinredbean.appagent.ui.UiFrameNavigator
import io.github.pumpkinredbean.appagent.ui.UiToolOutputCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class TradingAgentController : ViewModel() {
    val markets = MarketRepository()
    private val portfolio = PortfolioRepository()
    val approval = ToolApprovalController()
    val navigator = UiFrameNavigator()

    private val tools = TradingToolRuntime(markets, portfolio, approval)
    private val planner = DemoAgentPlanner()
    private var nextMessageId = 1L

    var state by mutableStateOf(
        AgentSessionUiState(
            messages = listOf(
                AgentMessage(
                    id = nextMessageId++,
                    role = MessageRole.ASSISTANT,
                    label = "Market agent",
                    text = "앱의 시장 정보와 로컬 포트폴리오가 연결됐어요. 질문하거나 현재 UI에서 바로 탐색해보세요.",
                ),
            ),
        ),
    )
        private set

    init {
        navigator.navigate(TradingFrames.overview(markets.supported(), portfolio.snapshot()))
    }

    fun send(prompt: String) {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty() || state.executing) return

        navigator.onTurnStarted()
        state = state.copy(
            conversationOpen = true,
            executing = true,
            progress = null,
            error = null,
            messages = state.messages + message(MessageRole.USER, trimmed, "You"),
        )

        viewModelScope.launch {
            val currentSymbol = navigator.current?.params?.get("symbol")?.jsonPrimitive?.content
            val plan = planner.plan(trimmed, currentSymbol)
            if (plan.tool == null) {
                delay(280)
                state = state.copy(
                    executing = false,
                    messages = state.messages + message(
                        MessageRole.ASSISTANT,
                        plan.directResponse.orEmpty(),
                        "Market agent",
                    ),
                )
                return@launch
            }

            state = state.copy(progress = ToolProgress(plan.tool, plan.runningLabel))
            delay(360)
            val result = tools.invoke(plan.tool, plan.input)
            acceptToolResult(result, plan)
        }
    }

    fun onFrameAction(action: FrameAction) {
        when (action) {
            FrameAction.ResumeLive -> navigator.resumeCurrent()
            is FrameAction.OpenAsset -> runUiTool(
                "visualize_market_asset",
                buildJsonObject { put("symbol", action.symbol) },
            )
            FrameAction.ShowReturn -> runUiTool("visualize_portfolio_return")
            FrameAction.OpenPortfolio -> runUiTool("visualize_portfolio")
            FrameAction.OpenOrders -> runUiTool("visualize_order_activity")
            is FrameAction.CompareAllocation -> runUiTool(
                "visualize_allocation",
                orderInput(action.symbol, action.budget),
                UiFrameNavigationMode.PUSH,
            )
            is FrameAction.ChangeCandidate -> runUiTool(
                "visualize_allocation",
                orderInput(action.symbol, action.budget),
                UiFrameNavigationMode.REPLACE,
            )
            is FrameAction.PrepareOrder -> runUiTool(
                "prepare_order",
                orderInput(action.symbol, action.budget),
            )
            is FrameAction.SubmitOrder -> runUiTool(
                "submit_order",
                orderInput(action.symbol, action.budget),
                UiFrameNavigationMode.REPLACE,
            )
        }
    }

    fun openConversation() {
        state = state.copy(conversationOpen = true)
    }

    fun showContext() {
        state = state.copy(conversationOpen = false)
    }

    fun toggleTheme() {
        state = state.copy(darkTheme = !state.darkTheme)
    }

    fun goBack(): Boolean = navigator.back()

    fun clearError() {
        state = state.copy(error = null)
    }

    private fun runUiTool(
        name: String,
        input: JsonObject = buildJsonObject {},
        mode: UiFrameNavigationMode = UiFrameNavigationMode.PUSH,
    ) {
        if (state.executing) return
        state = state.copy(executing = true, error = null)
        viewModelScope.launch {
            val result = tools.invoke(name, input)
            val frame = result.output?.let(UiToolOutputCodec::frame)
            if (result.ok && frame != null) {
                navigator.navigate(frame, mode)
                state = state.copy(
                    executing = false,
                    latestFrameTitle = frameTitle(frame),
                )
            } else {
                state = state.copy(
                    executing = false,
                    error = result.error?.message ?: "The UI action did not complete",
                )
            }
        }
    }

    private fun acceptToolResult(result: ToolResult, plan: AgentPlan) {
        if (!result.ok) {
            val text = result.error?.message ?: "요청을 완료하지 못했어요."
            state = state.copy(
                executing = false,
                progress = null,
                messages = state.messages + message(MessageRole.ASSISTANT, text, "Market agent"),
            )
            return
        }

        val output = checkNotNull(result.output)
        val frame = UiToolOutputCodec.frame(output)
        if (frame != null) navigator.navigate(frame, UiFrameNavigationMode.PUSH)
        val response = responseFor(plan.tool.orEmpty(), UiToolOutputCodec.modelOutput(output), plan.completedLabel)
        state = state.copy(
            executing = false,
            progress = null,
            latestFrameTitle = frame?.let(::frameTitle) ?: state.latestFrameTitle,
            messages = state.messages + listOf(
                message(MessageRole.TOOL, plan.completedLabel, "${plan.tool} · complete"),
                message(MessageRole.ASSISTANT, response, "Market agent"),
            ),
        )
    }

    private fun responseFor(tool: String, output: kotlinx.serialization.json.JsonElement, fallback: String): String {
        val value = output.jsonObject
        return when (tool) {
            "get_portfolio_return" -> {
                val amount = value.getValue("amount").jsonPrimitive.double
                val percent = value.getValue("percent").jsonPrimitive.double
                "현재 로컬 포트폴리오의 오늘 수익은 ${signedMoney(amount)} USDT, ${signedPercent(percent)}예요. 이건 사실 조회라 새 UI는 열지 않았어요."
            }
            "get_market_quote" -> {
                val symbol = value.getValue("symbol").jsonPrimitive.content
                val price = value.getValue("price").jsonPrimitive.double
                val change = value.getValue("changePercent").jsonPrimitive.double
                "$symbol 현재가는 ${money(price)} USDT이고 24시간 변동률은 ${signedPercent(change)}예요."
            }
            "get_market_symbols" -> "현재 BTC, ETH, SOL, BNB를 탐색할 수 있어요. 보고 싶은 종목 이름을 누르거나 말해보세요."
            else -> fallback
        }
    }

    private fun frameTitle(frame: UiFrame): String = when (frame.tag) {
        TradingFrameTags.OVERVIEW -> "For you now"
        TradingFrameTags.ASSET -> "${frame.params["symbol"]?.jsonPrimitive?.content ?: "Market"} detail"
        TradingFrameTags.RETURNS -> "Today’s return"
        TradingFrameTags.PORTFOLIO -> "Portfolio positions"
        TradingFrameTags.ALLOCATION -> "Allocation comparison"
        TradingFrameTags.ORDER_REVIEW -> if (
            frame.payload.jsonObject["status"]?.jsonPrimitive?.content == "filled"
        ) "Order complete" else "Order review"
        TradingFrameTags.ORDERS -> "Recent activity"
        else -> frame.tag
    }

    private fun message(role: MessageRole, text: String, label: String) =
        AgentMessage(nextMessageId++, role, text, label)

    private fun orderInput(symbol: String, budget: Double) = buildJsonObject {
        put("symbol", symbol)
        put("budget", budget)
    }

    private fun money(value: Double): String = String.format("%,.2f", value)
    private fun signedMoney(value: Double): String = (if (value >= 0) "+" else "−") + money(kotlin.math.abs(value))
    private fun signedPercent(value: Double): String = String.format("%+.2f%%", value)

    override fun onCleared() {
        approval.close()
        navigator.close()
        super.onCleared()
    }
}
