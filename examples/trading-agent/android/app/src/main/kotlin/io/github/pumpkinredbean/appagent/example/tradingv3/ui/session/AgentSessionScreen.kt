package io.github.pumpkinredbean.appagent.example.tradingv3.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pumpkinredbean.appagent.example.tradingv3.agent.TradingAgentController
import io.github.pumpkinredbean.appagent.example.tradingv3.model.AgentMessage
import io.github.pumpkinredbean.appagent.example.tradingv3.model.MessageRole
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.approval.ApprovalOverlay
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.ActionButton
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.LocalMarketColors
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.MarketText
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.design.StatusPill
import io.github.pumpkinredbean.appagent.example.tradingv3.ui.frames.tradingFrameRenderers
import io.github.pumpkinredbean.appagent.ui.UiFrameLifecycleState
import io.github.pumpkinredbean.appagent.ui.compose.ToolApprovalHost
import io.github.pumpkinredbean.appagent.ui.compose.UiFrameHost
import io.github.pumpkinredbean.appagent.ui.compose.navigationState

@Composable
fun AgentSessionScreen(
    controller: TradingAgentController,
    modifier: Modifier = Modifier,
) {
    val state = controller.state
    val colors = LocalMarketColors.current
    val navigation by controller.navigator.navigationState()
    val renderers = remember(controller) {
        tradingFrameRenderers(controller.markets, controller::onFrameAction)
    }
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = state.conversationOpen || navigation.depth > 1) {
        if (state.conversationOpen) {
            focusManager.clearFocus(force = true)
            controller.showContext()
        } else {
            controller.goBack()
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.page)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AppHeader(
            darkTheme = state.darkTheme,
            contextDepth = navigation.depth,
            onTheme = controller::toggleTheme,
            onConversation = controller::openConversation,
        )

        Box(Modifier.fillMaxWidth().weight(1f)) {
            ConversationTimeline(
                messages = state.messages,
                progress = state.progress,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 92.dp)
                    .then(
                        if (state.conversationOpen) Modifier
                        else Modifier.clearAndSetSemantics { },
                    ),
            )

            ContextVisibility(visible = !state.conversationOpen) {
                ContextCanvas(
                    title = state.latestFrameTitle,
                    lifecycle = if (navigation.currentResumed) UiFrameLifecycleState.RESUMED else UiFrameLifecycleState.PAUSED,
                    canGoBack = navigation.depth > 1,
                    onBack = controller::goBack,
                    onAsk = controller::openConversation,
                ) {
                    UiFrameHost(
                        navigator = controller.navigator,
                        renderers = renderers,
                        modifier = Modifier.fillMaxSize(),
                        handleSystemBack = false,
                        missingRenderer = { frame ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                MarketText("No renderer registered for ${frame.tag}", color = colors.red)
                            }
                        },
                    )
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(visible = state.conversationOpen) {
                    ActiveContextButton(
                        title = state.latestFrameTitle,
                        resumed = navigation.currentResumed,
                        onClick = {
                            focusManager.clearFocus(force = true)
                            controller.showContext()
                        },
                    )
                }
                Composer(
                    enabled = !state.executing,
                    onFocus = controller::openConversation,
                    onSend = controller::send,
                )
            }

            state.error?.let { message ->
                ErrorToast(message, controller::clearError, Modifier.align(Alignment.TopCenter))
            }
        }
    }

    ToolApprovalHost(controller = controller.approval) { approval ->
        ApprovalOverlay(
            request = approval.request,
            queuedRequestCount = approval.queuedRequestCount,
            remainingDecisionMillis = approval.remainingDecisionMillis,
            onApprove = approval::approve,
            onDeny = approval::deny,
        )
    }
}

@Composable
private fun ContextVisibility(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(220)) + slideInVertically(tween(300)) { -it / 18 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(220)) { -it / 22 },
        content = { content() },
    )
}

@Composable
private fun AppHeader(
    darkTheme: Boolean,
    contextDepth: Int,
    onTheme: () -> Unit,
    onConversation: () -> Unit,
) {
    val colors = LocalMarketColors.current
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(colors.page).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(colors.text), contentAlignment = Alignment.Center) {
            MarketText("M", color = colors.page, size = 15, weight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f)) {
            MarketText("Market", size = 16, weight = FontWeight.Bold)
            MarketText("IN-APP AGENT", color = colors.textDim, size = 10, family = FontFamily.Monospace)
        }
        StatusPill("Live")
        HeaderButton(if (darkTheme) "☼" else "◐", onTheme)
        HeaderButton("${contextDepth.coerceAtLeast(1)}", onConversation)
    }
}

@Composable
private fun HeaderButton(label: String, onClick: () -> Unit) {
    val colors = LocalMarketColors.current
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.raised).border(1.dp, colors.line, RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { MarketText(label, size = 14, weight = FontWeight.Bold, family = FontFamily.Monospace) }
}

@Composable
private fun ContextCanvas(
    title: String,
    lifecycle: UiFrameLifecycleState,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onAsk: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = LocalMarketColors.current
    Column(Modifier.fillMaxSize().background(colors.surface).padding(bottom = 82.dp)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).background(colors.raised).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (canGoBack) HeaderButton("←", onBack)
            Column(Modifier.weight(1f)) {
                MarketText(if (lifecycle == UiFrameLifecycleState.RESUMED) "CURRENT CONTEXT" else "PAUSED CONTEXT", color = colors.accent, size = 10, weight = FontWeight.Bold, family = FontFamily.Monospace)
                MarketText(title, size = 15, weight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(colors.surface).border(1.dp, colors.line, RoundedCornerShape(12.dp)).clickable(onClick = onAsk).padding(horizontal = 13.dp, vertical = 10.dp),
            ) { MarketText("Ask  ↑", size = 13, weight = FontWeight.SemiBold) }
        }
        Box(Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun ConversationTimeline(
    messages: List<AgentMessage>,
    progress: io.github.pumpkinredbean.appagent.example.tradingv3.model.ToolProgress?,
    modifier: Modifier,
) {
    val colors = LocalMarketColors.current
    val listState = rememberLazyListState()
    val count = messages.size + if (progress != null) 1 else 0
    LaunchedEffect(count) {
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    LazyColumn(
        modifier.background(colors.page),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 20.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                MarketText("Market agent", size = 26, weight = FontWeight.Bold)
                MarketText("One session carrying service context forward", color = colors.textDim, size = 13)
            }
        }
        items(messages.size, key = { messages[it].id }) { index -> MessageBubble(messages[index]) }
        progress?.let { value ->
            item("progress") { ToolProgressCard(value.name, value.label) }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("지금 수익률 확인해줘", "비트코인 상세 보여줘", "1,000 USDT 추가 비교해줘").forEach { suggestion ->
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(colors.raised).border(1.dp, colors.line, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                        MarketText(suggestion, color = colors.textSoft, size = 12)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AgentMessage) {
    val colors = LocalMarketColors.current
    if (message.role == MessageRole.TOOL) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.raised).padding(13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                MarketText("✓", color = colors.page, size = 12, weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                MarketText(message.label, color = colors.accent, size = 10, family = FontFamily.Monospace)
                MarketText(message.text, color = colors.textSoft, size = 13)
            }
        }
        return
    }

    val user = message.role == MessageRole.USER
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MarketText(message.label, color = if (user) colors.amber else colors.accent, size = 11, weight = FontWeight.Bold)
        Box(
            Modifier
                .fillMaxWidth(.9f)
                .clip(RoundedCornerShape(17.dp))
                .background(if (user) colors.amberSoft else colors.surface)
                .border(1.dp, if (user) colors.amber.copy(alpha = .25f) else colors.line, RoundedCornerShape(17.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            MarketText(message.text, color = if (user) colors.text else colors.textSoft, size = 15)
        }
    }
}

@Composable
private fun ToolProgressCard(name: String, label: String) {
    val colors = LocalMarketColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.accentSoft).border(1.dp, colors.accent.copy(alpha = .25f), RoundedCornerShape(14.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(colors.accent))
        Column {
            MarketText(name, color = colors.accent, size = 10, family = FontFamily.Monospace)
            MarketText(label, size = 13, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ActiveContextButton(
    title: String,
    resumed: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalMarketColors.current
    Row(
        Modifier.padding(bottom = 8.dp).shadow(16.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(colors.raised).border(1.dp, colors.lineStrong, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 15.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (resumed) colors.accent else colors.textDim),
        )
        MarketText("View $title", size = 12, weight = FontWeight.SemiBold, maxLines = 1)
        MarketText("↑", color = colors.accent, size = 15)
    }
}

@Composable
private fun Composer(
    enabled: Boolean,
    onFocus: () -> Unit,
    onSend: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val colors = LocalMarketColors.current
    val focusRequester = remember { FocusRequester() }
    val send = {
        val message = value.trim()
        if (message.isNotEmpty() && enabled) {
            value = ""
            onSend(message)
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .shadow(22.dp, RoundedCornerShape(19.dp))
            .clip(RoundedCornerShape(19.dp))
            .background(colors.surface)
            .border(1.dp, colors.lineStrong, RoundedCornerShape(19.dp))
            .padding(start = 17.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocus() },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = colors.text, fontSize = 16.sp, lineHeight = 22.sp),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    if (value.isEmpty()) MarketText("Ask about your market context", color = colors.textDim, size = 16)
                    inner()
                }
            },
        )
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(if (enabled && value.isNotBlank()) colors.text else colors.soft).clickable(enabled = enabled && value.isNotBlank()) { send() },
            contentAlignment = Alignment.Center,
        ) {
            MarketText("↑", color = if (enabled && value.isNotBlank()) colors.page else colors.textDim, size = 21, weight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ErrorToast(message: String, onDismiss: () -> Unit, modifier: Modifier) {
    val colors = LocalMarketColors.current
    LaunchedEffect(message) {
        kotlinx.coroutines.delay(3_000)
        onDismiss()
    }
    Box(
        modifier.padding(14.dp).clip(RoundedCornerShape(14.dp)).background(colors.red).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 12.dp),
    ) { MarketText(message, color = colors.page, size = 13, weight = FontWeight.SemiBold) }
}
