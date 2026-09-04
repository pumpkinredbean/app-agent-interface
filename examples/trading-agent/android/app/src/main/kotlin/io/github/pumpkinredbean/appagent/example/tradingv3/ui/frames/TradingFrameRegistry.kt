package io.github.pumpkinredbean.appagent.example.tradingv3.ui.frames

import io.github.pumpkinredbean.appagent.example.tradingv3.data.MarketRepository
import io.github.pumpkinredbean.appagent.example.tradingv3.model.FrameAction
import io.github.pumpkinredbean.appagent.example.tradingv3.tools.TradingFrameTags
import io.github.pumpkinredbean.appagent.ui.UiFrameRendererRegistry
import io.github.pumpkinredbean.appagent.ui.compose.ComposeUiFrameRenderer

fun tradingFrameRenderers(
    markets: MarketRepository,
    onAction: (FrameAction) -> Unit,
): UiFrameRendererRegistry<ComposeUiFrameRenderer> =
    UiFrameRendererRegistry<ComposeUiFrameRenderer>().apply {
        register(TradingFrameTags.OVERVIEW, ComposeUiFrameRenderer { frame, lifecycle, modifier ->
            OverviewFrame(frame, lifecycle, onAction, modifier)
        })
        register(TradingFrameTags.RETURNS, ComposeUiFrameRenderer { frame, lifecycle, modifier ->
            ReturnsFrame(frame, lifecycle, onAction, modifier)
        })
        register(TradingFrameTags.ASSET, ComposeUiFrameRenderer { frame, lifecycle, modifier ->
            AssetFrame(frame, lifecycle, markets, onAction, modifier)
        })
        register(TradingFrameTags.ALLOCATION, ComposeUiFrameRenderer { frame, lifecycle, modifier ->
            AllocationFrame(frame, lifecycle, onAction, modifier)
        })
        register(TradingFrameTags.ORDER_REVIEW, ComposeUiFrameRenderer { frame, lifecycle, modifier ->
            OrderReviewFrame(frame, lifecycle, onAction, modifier)
        })
        register(TradingFrameTags.PORTFOLIO, ComposeUiFrameRenderer { frame, lifecycle, modifier ->
            PortfolioFrame(frame, lifecycle, onAction, modifier)
        })
        register(TradingFrameTags.ORDERS, ComposeUiFrameRenderer { frame, lifecycle, modifier ->
            OrdersFrame(frame, lifecycle, onAction, modifier)
        })
    }
