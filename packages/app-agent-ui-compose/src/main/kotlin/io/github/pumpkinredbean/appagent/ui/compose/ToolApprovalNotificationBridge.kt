package io.github.pumpkinredbean.appagent.ui.compose

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import io.github.pumpkinredbean.appagent.ToolApprovalController
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Configuration for the single, aggregate approval notification. */
public data class ToolApprovalNotificationConfiguration @JvmOverloads constructor(
    val smallIconResourceId: Int,
    val contentIntent: PendingIntent,
    val channelId: String = DEFAULT_CHANNEL_ID,
    val notificationId: Int = DEFAULT_NOTIFICATION_ID,
    val presentationDelayMillis: Long = DEFAULT_PRESENTATION_DELAY_MILLIS,
) {
    init {
        require(smallIconResourceId != 0) { "smallIconResourceId must reference a drawable" }
        require(channelId.isNotBlank()) { "channelId must not be blank" }
        require(presentationDelayMillis >= 0L) { "presentationDelayMillis must not be negative" }
    }

    public companion object {
        public const val DEFAULT_CHANNEL_ID: String = "app_agent_approvals"
        public const val DEFAULT_NOTIFICATION_ID: Int = 0x41505052
        public const val DEFAULT_PRESENTATION_DELAY_MILLIS: Long = 350L
    }
}

/**
 * Shows one privacy-safe notification while approvals are waiting off-screen.
 *
 * The app owns [ToolApprovalNotificationConfiguration.contentIntent], so tapping
 * the notification can navigate to the Activity that installs [ToolApprovalHost].
 * The notification never exposes tool names, descriptions, or inputs and never
 * provides approve/deny actions outside the app-owned UI.
 */
public class ToolApprovalNotificationBridge(
    context: Context,
    controller: ToolApprovalController,
    public val configuration: ToolApprovalNotificationConfiguration,
) : Closeable {
    private val applicationContext = context.applicationContext
    private val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("app-agent-approval-notification"),
    )
    private val closed = AtomicBoolean(false)
    private val notificationLock = Any()
    private val collectionJob: Job

    init {
        createNotificationChannel()
        collectionJob = scope.launch {
            controller.state
                .map { state ->
                    ApprovalNotificationState(
                        visible = state.requiresUserAttention,
                        requestCount = state.totalCount,
                    )
                }
                .distinctUntilChanged()
                .collectLatest { state ->
                    if (!state.visible) {
                        cancelNotification()
                        return@collectLatest
                    }

                    if (configuration.presentationDelayMillis > 0L) {
                        delay(configuration.presentationDelayMillis)
                    }
                    postNotification(state.requestCount)
                }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            collectionJob.cancel()
            scope.cancel()
            cancelNotification()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            configuration.channelId,
            applicationContext.getString(R.string.app_agent_approval_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = applicationContext.getString(
                R.string.app_agent_approval_notification_channel_description,
            )
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun postNotification(requestCount: Int) {
        synchronized(notificationLock) {
            if (closed.get() || !notificationManager.areNotificationsEnabled()) return

            val content = applicationContext.resources.getQuantityString(
                R.plurals.app_agent_approval_notification_content,
                requestCount,
                requestCount,
            )
            val notification = Notification.Builder(applicationContext, configuration.channelId)
                .setSmallIcon(configuration.smallIconResourceId)
                .setContentTitle(applicationContext.getString(R.string.app_agent_approval_notification_title))
                .setContentText(content)
                .setContentIntent(configuration.contentIntent)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setNumber(requestCount)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build()

            try {
                notificationManager.notify(configuration.notificationId, notification)
            } catch (_: SecurityException) {
                // Android 13+ notification permission is deliberately owned by the host app.
            }
        }
    }

    private fun cancelNotification() {
        synchronized(notificationLock) {
            notificationManager.cancel(configuration.notificationId)
        }
    }
}

private data class ApprovalNotificationState(
    val visible: Boolean,
    val requestCount: Int,
)
