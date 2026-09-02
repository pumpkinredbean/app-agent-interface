package io.github.pumpkinredbean.appagent.example.weather

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.github.pumpkinredbean.appagent.ToolApprovalController
import io.github.pumpkinredbean.appagent.ui.compose.ToolApprovalHost

internal object ApprovalUi {
    @JvmStatic
    fun createHostView(
        context: Context,
        controller: ToolApprovalController,
    ): ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            MaterialTheme {
                ToolApprovalHost(controller = controller)
            }
        }
    }
}
