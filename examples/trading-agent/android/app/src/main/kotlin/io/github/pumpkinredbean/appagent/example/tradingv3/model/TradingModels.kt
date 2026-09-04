package io.github.pumpkinredbean.appagent.example.tradingv3.model

data class MarketAsset(
    val symbol: String,
    val name: String,
    val price: Double,
    val changePercent: Double,
    val high: Double,
    val low: Double,
    val quoteVolume: Double,
    val series: List<Double>,
    val bids: List<DepthLevel>,
    val asks: List<DepthLevel>,
) {
    val pair: String get() = "${symbol}/USDT"
}

data class DepthLevel(val price: Double, val quantity: Double)

data class Position(
    val symbol: String,
    val name: String,
    val quantity: Double,
    val averageCost: Double,
)

data class LocalOrder(
    val id: String,
    val symbol: String,
    val quantity: Double,
    val budget: Double,
    val price: Double,
)

data class PortfolioSnapshot(
    val cash: Double,
    val positions: List<Position>,
    val orders: List<LocalOrder>,
)

enum class MessageRole { USER, ASSISTANT, TOOL }

data class AgentMessage(
    val id: Long,
    val role: MessageRole,
    val text: String,
    val label: String,
)

data class ToolProgress(
    val name: String,
    val label: String,
)

data class AgentSessionUiState(
    val messages: List<AgentMessage> = emptyList(),
    val conversationOpen: Boolean = false,
    val executing: Boolean = false,
    val progress: ToolProgress? = null,
    val darkTheme: Boolean = true,
    val latestFrameTitle: String = "For you now",
    val error: String? = null,
)

sealed interface FrameAction {
    data class OpenAsset(val symbol: String) : FrameAction
    data object ShowReturn : FrameAction
    data object OpenPortfolio : FrameAction
    data object OpenOrders : FrameAction
    data class CompareAllocation(val symbol: String = "BTC", val budget: Double = 1_000.0) : FrameAction
    data class PrepareOrder(val symbol: String, val budget: Double) : FrameAction
    data class SubmitOrder(val symbol: String, val budget: Double) : FrameAction
    data class ChangeCandidate(val symbol: String, val budget: Double) : FrameAction
    data object ResumeLive : FrameAction
}
