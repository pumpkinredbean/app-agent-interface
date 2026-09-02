package io.github.pumpkinredbean.appagent.ui.compose

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pumpkinredbean.appagent.ToolApprovalConfiguration
import io.github.pumpkinredbean.appagent.ToolApprovalController
import io.github.pumpkinredbean.appagent.ToolApprovalRequest
import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ToolApprovalNotificationBridgeTest {
    @Test
    fun notificationIsAggregateAndDoesNotExposeToolContent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val controller = ToolApprovalController(
            ToolApprovalConfiguration(decisionTimeoutMillis = 60_000L),
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, Activity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val bridge = ToolApprovalNotificationBridge(
            context = context,
            controller = controller,
            configuration = ToolApprovalNotificationConfiguration(
                smallIconResourceId = android.R.drawable.ic_dialog_alert,
                contentIntent = contentIntent,
                presentationDelayMillis = 0L,
            ),
        )

        val decision = async(Dispatchers.Default) {
            controller.requestApproval(sensitiveRequest)
        }
        try {
            val notification = withTimeout(3_000L) {
                withContext(Dispatchers.Default) {
                    while (manager.activeNotifications.isEmpty()) {
                        kotlinx.coroutines.delay(10L)
                    }
                    manager.activeNotifications.single().notification
                }
            }
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            val content = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

            assertEquals("Approval required", title)
            assertFalse(content.contains("transfer_money"))
            assertFalse(content.contains("1234"))
            assertFalse(content.contains("100000"))

            bridge.close()
            withTimeout(3_000L) {
                withContext(Dispatchers.Default) {
                    while (manager.activeNotifications.isNotEmpty()) {
                        kotlinx.coroutines.delay(10L)
                    }
                }
            }
        } finally {
            bridge.close()
            controller.close()
            decision.await()
        }
    }
}

private val sensitiveRequest = ToolApprovalRequest(
    call = ToolCall(
        id = "call-sensitive",
        name = "transfer_money",
        input = buildJsonObject {
            put("amount", 100000)
            put("pin", "1234")
        },
    ),
    spec = ToolSpec(
        name = "transfer_money",
        description = "Transfers money after user confirmation.",
        inputSchema = buildJsonObject { put("type", "object") },
    ),
)
