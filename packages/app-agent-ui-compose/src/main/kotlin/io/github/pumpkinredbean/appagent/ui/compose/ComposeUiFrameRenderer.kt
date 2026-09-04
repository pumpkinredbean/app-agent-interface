package io.github.pumpkinredbean.appagent.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.pumpkinredbean.appagent.ui.UiFrame
import io.github.pumpkinredbean.appagent.ui.UiFrameLifecycleState

/** Application-owned Compose renderer for one exact [UiFrame.tag]. */
public fun interface ComposeUiFrameRenderer {
    @Composable
    public fun Content(
        frame: UiFrame,
        lifecycleState: UiFrameLifecycleState,
        modifier: Modifier,
    )
}
