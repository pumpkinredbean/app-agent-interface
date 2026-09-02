package io.github.pumpkinredbean.appagent.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pumpkinredbean.appagent.ToolApprovalController
import io.github.pumpkinredbean.appagent.ToolApprovalPresentationStatus

/**
 * Renders the controller's FIFO queue through one lifecycle-aware dialog host.
 *
 * Install exactly one host in the foreground approval destination. Requests
 * remain queued while the host is not resumed, and only a visibly presented
 * request begins its decision timeout.
 */
@Composable
public fun ToolApprovalHost(
    controller: ToolApprovalController,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycle = lifecycleOwner.lifecycle
    val registration = remember(controller, lifecycleOwner) { controller.registerHost() }
    var hostVisible by remember(controller, lifecycleOwner) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(registration, lifecycle) {
        fun updateVisibility() {
            hostVisible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            registration.setVisible(hostVisible)
        }

        val observer = LifecycleEventObserver { _, _ -> updateVisibility() }
        lifecycle.addObserver(observer)
        updateVisibility()
        onDispose {
            lifecycle.removeObserver(observer)
            registration.setVisible(false)
            registration.close()
        }
    }

    val state by controller.state.collectAsStateWithLifecycle()
    val current = state.current
    if (hostVisible && current != null) {
        val callId = current.call.id
        LaunchedEffect(callId, state.status, hostVisible) {
            if (state.status == ToolApprovalPresentationStatus.WAITING_FOR_UI) {
                withFrameNanos { }
                controller.markPresented(callId)
            }
        }

        ToolApprovalDialog(
            request = current,
            onApprove = { controller.approve(callId) },
            onDeny = { controller.deny(callId) },
            modifier = modifier,
            queuedRequestCount = state.queuedCount,
            remainingDecisionMillis = state.remainingDecisionMillis,
        )
    }
}
