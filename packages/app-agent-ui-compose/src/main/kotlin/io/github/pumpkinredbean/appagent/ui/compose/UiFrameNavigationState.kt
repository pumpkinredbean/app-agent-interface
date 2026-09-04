package io.github.pumpkinredbean.appagent.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.github.pumpkinredbean.appagent.ui.UiFrameNavigationSnapshot
import io.github.pumpkinredbean.appagent.ui.UiFrameNavigator

/** Adapts the framework-neutral navigator to observable Compose state. */
@Composable
public fun UiFrameNavigator.navigationState(): State<UiFrameNavigationSnapshot> {
    val state = remember(this) { mutableStateOf(snapshot) }
    DisposableEffect(this) {
        val subscription = observe { state.value = it }
        onDispose(subscription::close)
    }
    return state
}
