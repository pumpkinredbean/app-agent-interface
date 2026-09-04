package io.github.pumpkinredbean.appagent.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import io.github.pumpkinredbean.appagent.ui.UiFrame
import io.github.pumpkinredbean.appagent.ui.UiFrameLifecycleState
import io.github.pumpkinredbean.appagent.ui.UiFrameNavigator
import io.github.pumpkinredbean.appagent.ui.UiFrameRendererRegistry

/**
 * Renders the current frame while the framework-neutral navigator remains the
 * sole owner of stack identity and turn lifecycle.
 */
@Composable
public fun UiFrameHost(
    navigator: UiFrameNavigator,
    renderers: UiFrameRendererRegistry<ComposeUiFrameRenderer>,
    modifier: Modifier = Modifier,
    handleSystemBack: Boolean = true,
    emptyContent: @Composable () -> Unit = {},
    missingRenderer: @Composable (UiFrame) -> Unit = {},
) {
    val snapshot by navigator.navigationState()

    if (handleSystemBack) {
        BackHandler(enabled = snapshot.depth > 1, onBack = navigator::back)
    }

    AnimatedContent(
        targetState = snapshot.current,
        modifier = modifier,
        contentKey = { it?.key },
        transitionSpec = {
            val initialIndex = snapshot.frames.indexOfFirst { it.key == initialState?.key }
            val targetIndex = snapshot.frames.indexOfFirst { it.key == targetState?.key }
            val forward = targetState != null &&
                initialState != null &&
                initialIndex >= 0 &&
                targetIndex > initialIndex
            val enter = fadeIn(tween(210)) + slideIntoContainer(
                towards = if (forward) {
                    AnimatedContentTransitionScope.SlideDirection.Left
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Right
                },
                animationSpec = tween(280),
                initialOffset = { it / 14 },
            )
            val exit = fadeOut(tween(150))
            (enter togetherWith exit).using(SizeTransform(clip = true))
        },
        label = "UiFrameHost",
    ) { frame ->
        if (frame == null) {
            emptyContent()
            return@AnimatedContent
        }

        val lifecycleState = when {
            snapshot.frames.none { it.key == frame.key } -> UiFrameLifecycleState.DISPOSED
            snapshot.current?.key == frame.key && snapshot.currentResumed -> UiFrameLifecycleState.RESUMED
            else -> UiFrameLifecycleState.PAUSED
        }
        val renderer = renderers.find(frame)
        key(frame.key) {
            Box(Modifier.fillMaxSize()) {
                if (renderer == null) {
                    missingRenderer(frame)
                } else {
                    renderer.Content(frame, lifecycleState, Modifier.fillMaxSize())
                }
            }
        }
    }
}
