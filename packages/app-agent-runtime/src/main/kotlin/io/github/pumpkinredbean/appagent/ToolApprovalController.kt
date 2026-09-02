package io.github.pumpkinredbean.appagent

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

public data class ToolApprovalConfiguration @JvmOverloads constructor(
    val decisionTimeoutMillis: Long = DEFAULT_DECISION_TIMEOUT_MILLIS,
    val maxPendingRequests: Int = DEFAULT_MAX_PENDING_REQUESTS,
) {
    init {
        require(decisionTimeoutMillis > 0) { "decisionTimeoutMillis must be positive" }
        require(maxPendingRequests > 0) { "maxPendingRequests must be positive" }
    }

    public companion object {
        public const val DEFAULT_DECISION_TIMEOUT_MILLIS: Long = 60_000L
        public const val DEFAULT_MAX_PENDING_REQUESTS: Int = 16
    }
}

public enum class ToolApprovalPresentationStatus {
    WAITING_FOR_UI,
    PRESENTED,
}

/** Immutable snapshot consumed by Compose UI and optional notification integrations. */
public data class ToolApprovalQueueState @JvmOverloads constructor(
    val current: ToolApprovalRequest? = null,
    val status: ToolApprovalPresentationStatus? = null,
    val queuedCount: Int = 0,
    val remainingDecisionMillis: Long? = null,
    val hostVisible: Boolean = false,
) {
    public val totalCount: Int
        get() = (if (current == null) 0 else 1) + queuedCount

    public val requiresUserAttention: Boolean
        get() = current != null && !hostVisible
}

/**
 * Application-scoped, FIFO approval coordinator.
 *
 * Requests wait without consuming their decision timeout. The timeout starts
 * only after [markPresented] confirms that the current request reached visible
 * UI. All mutations are serialized through one internal command loop.
 */
public class ToolApprovalController private constructor(
    public val configuration: ToolApprovalConfiguration,
    private val scope: CoroutineScope,
    private val ownsScope: Boolean,
    private val nowMillis: () -> Long,
    private val tickIntervalMillis: Long,
) : ToolApprovalDecisionHandler, Closeable {
    @JvmOverloads
    public constructor(
        configuration: ToolApprovalConfiguration = ToolApprovalConfiguration(),
    ) : this(
        configuration = configuration,
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("app-agent-tool-approval"),
        ),
        ownsScope = true,
        nowMillis = { System.nanoTime() / NANOS_PER_MILLISECOND },
        tickIntervalMillis = DEFAULT_TICK_INTERVAL_MILLIS,
    )

    internal constructor(
        configuration: ToolApprovalConfiguration,
        scope: CoroutineScope,
        nowMillis: () -> Long,
        tickIntervalMillis: Long = DEFAULT_TICK_INTERVAL_MILLIS,
    ) : this(
        configuration = configuration,
        scope = scope,
        ownsScope = false,
        nowMillis = nowMillis,
        tickIntervalMillis = tickIntervalMillis,
    )

    private val commands: Channel<Command> = Channel(Channel.UNLIMITED)
    private val closed: AtomicBoolean = AtomicBoolean(false)
    private val nextHostId: AtomicLong = AtomicLong(1L)
    private val mutableState: MutableStateFlow<ToolApprovalQueueState> =
        MutableStateFlow(ToolApprovalQueueState())

    public val state: StateFlow<ToolApprovalQueueState> = mutableState.asStateFlow()

    init {
        require(tickIntervalMillis > 0) { "tickIntervalMillis must be positive" }
        scope.launch { commandLoop() }
    }

    override suspend fun requestApproval(request: ToolApprovalRequest): ToolApprovalDecision {
        if (closed.get()) return ToolApprovalDecision.UNAVAILABLE

        val decision = CompletableDeferred<ToolApprovalDecision>()
        val pending = PendingApproval(request, decision)
        if (commands.trySend(Command.Enqueue(pending)).isFailure) {
            return ToolApprovalDecision.UNAVAILABLE
        }
        try {
            return decision.await()
        } catch (cancelled: CancellationException) {
            commands.trySend(Command.Cancel(pending))
            throw cancelled
        }
    }

    /** Registers one UI host. The returned registration must be closed with the host lifecycle. */
    public fun registerHost(): ToolApprovalHostRegistration {
        check(!closed.get()) { "ToolApprovalController is closed" }
        return ToolApprovalHostRegistration(this, nextHostId.getAndIncrement())
    }

    /** Starts the decision timeout for [callId] after its UI has become visible. */
    public fun markPresented(callId: String) {
        commands.trySend(Command.MarkPresented(callId))
    }

    public fun approve(callId: String) {
        commands.trySend(Command.Resolve(callId, ToolApprovalDecision.APPROVED))
    }

    public fun deny(callId: String) {
        commands.trySend(Command.Resolve(callId, ToolApprovalDecision.DENIED))
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            commands.close()
        }
    }

    internal fun updateHostVisibility(hostId: Long, visible: Boolean) {
        commands.trySend(Command.UpdateHost(hostId, visible))
    }

    internal fun unregisterHost(hostId: Long) {
        commands.trySend(Command.RemoveHost(hostId))
    }

    private suspend fun commandLoop() {
        val queue = ArrayDeque<PendingApproval>()
        val visibleHosts = mutableSetOf<Long>()
        var current: PendingApproval? = null
        var status: ToolApprovalPresentationStatus? = null
        var deadlineMillis: Long? = null
        var timeoutJob: Job? = null

        fun publish() {
            val active = current
            val remaining = if (
                active != null &&
                status == ToolApprovalPresentationStatus.PRESENTED &&
                deadlineMillis != null
            ) {
                (deadlineMillis!! - nowMillis()).coerceAtLeast(0L)
            } else {
                null
            }
            mutableState.value = ToolApprovalQueueState(
                current = active?.request,
                status = status,
                queuedCount = queue.size,
                remainingDecisionMillis = remaining,
                hostVisible = visibleHosts.isNotEmpty(),
            )
        }

        fun activateNext() {
            if (current == null && queue.isNotEmpty()) {
                current = queue.removeFirst()
                status = ToolApprovalPresentationStatus.WAITING_FOR_UI
                deadlineMillis = null
            }
        }

        fun finishCurrent(decision: ToolApprovalDecision) {
            timeoutJob?.cancel()
            timeoutJob = null
            deadlineMillis = null
            val resolved = current
            current = null
            status = null
            resolved?.decision?.complete(decision)
            activateNext()
            publish()
        }

        fun pendingCount(): Int = queue.size + if (current == null) 0 else 1

        fun containsCallId(callId: String): Boolean =
            current?.request?.call?.id == callId || queue.any { it.request.call.id == callId }

        try {
            while (scope.isActive) {
                when (val command = commands.receiveCatching().getOrNull() ?: break) {
                    is Command.Enqueue -> {
                        when {
                            closed.get() -> command.pending.decision.complete(ToolApprovalDecision.UNAVAILABLE)
                            pendingCount() >= configuration.maxPendingRequests ->
                                command.pending.decision.complete(ToolApprovalDecision.UNAVAILABLE)
                            containsCallId(command.pending.request.call.id) ->
                                command.pending.decision.complete(ToolApprovalDecision.UNAVAILABLE)
                            else -> {
                                queue.addLast(command.pending)
                                activateNext()
                                publish()
                            }
                        }
                    }

                    is Command.MarkPresented -> {
                        if (
                            current?.request?.call?.id == command.callId &&
                            status == ToolApprovalPresentationStatus.WAITING_FOR_UI
                        ) {
                            status = ToolApprovalPresentationStatus.PRESENTED
                            deadlineMillis = nowMillis() + configuration.decisionTimeoutMillis
                            publish()
                            val expectedCallId = command.callId
                            val expectedDeadline = deadlineMillis!!
                            timeoutJob = scope.launch {
                                while (isActive) {
                                    val remaining = expectedDeadline - nowMillis()
                                    if (remaining <= 0L) {
                                        commands.trySend(Command.Timeout(expectedCallId))
                                        break
                                    }
                                    delay(minOf(tickIntervalMillis, remaining))
                                    if (commands.trySend(Command.Tick(expectedCallId)).isFailure) break
                                }
                            }
                        }
                    }

                    is Command.Tick -> {
                        if (
                            current?.request?.call?.id == command.callId &&
                            status == ToolApprovalPresentationStatus.PRESENTED
                        ) {
                            publish()
                        }
                    }

                    is Command.Timeout -> {
                        if (
                            current?.request?.call?.id == command.callId &&
                            status == ToolApprovalPresentationStatus.PRESENTED &&
                            deadlineMillis?.let { nowMillis() >= it } == true
                        ) {
                            finishCurrent(ToolApprovalDecision.TIMED_OUT)
                        }
                    }

                    is Command.Resolve -> {
                        if (
                            current?.request?.call?.id == command.callId &&
                            status == ToolApprovalPresentationStatus.PRESENTED
                        ) {
                            finishCurrent(command.decision)
                        }
                    }

                    is Command.Cancel -> {
                        if (current === command.pending) {
                            timeoutJob?.cancel()
                            timeoutJob = null
                            deadlineMillis = null
                            current = null
                            status = null
                            activateNext()
                            publish()
                        } else if (queue.remove(command.pending)) {
                            publish()
                        }
                    }

                    is Command.UpdateHost -> {
                        if (command.visible) visibleHosts.add(command.hostId) else visibleHosts.remove(command.hostId)
                        publish()
                    }

                    is Command.RemoveHost -> {
                        visibleHosts.remove(command.hostId)
                        publish()
                    }
                }
            }
        } finally {
            timeoutJob?.cancel()
            current?.decision?.complete(ToolApprovalDecision.UNAVAILABLE)
            queue.forEach { it.decision.complete(ToolApprovalDecision.UNAVAILABLE) }
            queue.clear()
            commands.close()
            while (true) {
                val remaining = commands.tryReceive().getOrNull() ?: break
                if (remaining is Command.Enqueue) {
                    remaining.pending.decision.complete(ToolApprovalDecision.UNAVAILABLE)
                }
            }
            mutableState.value = ToolApprovalQueueState()
            if (ownsScope) scope.cancel()
        }
    }

    private data class PendingApproval(
        val request: ToolApprovalRequest,
        val decision: CompletableDeferred<ToolApprovalDecision>,
    )

    private sealed interface Command {
        data class Enqueue(val pending: PendingApproval) : Command

        data class MarkPresented(val callId: String) : Command

        data class Tick(val callId: String) : Command

        data class Timeout(val callId: String) : Command

        data class Resolve(
            val callId: String,
            val decision: ToolApprovalDecision,
        ) : Command

        data class Cancel(val pending: PendingApproval) : Command

        data class UpdateHost(
            val hostId: Long,
            val visible: Boolean,
        ) : Command

        data class RemoveHost(val hostId: Long) : Command
    }

    private companion object {
        private const val NANOS_PER_MILLISECOND: Long = 1_000_000L
        private const val DEFAULT_TICK_INTERVAL_MILLIS: Long = 1_000L
    }
}

/** Lifecycle registration used by one app-owned approval host. */
public class ToolApprovalHostRegistration internal constructor(
    private val controller: ToolApprovalController,
    private val hostId: Long,
) : Closeable {
    private val closed: AtomicBoolean = AtomicBoolean(false)

    public fun setVisible(visible: Boolean) {
        if (!closed.get()) controller.updateHostVisibility(hostId, visible)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) controller.unregisterHost(hostId)
    }
}
