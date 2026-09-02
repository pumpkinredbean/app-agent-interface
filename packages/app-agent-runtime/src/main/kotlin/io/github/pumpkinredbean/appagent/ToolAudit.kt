package io.github.pumpkinredbean.appagent

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One runtime-generated event in the lifecycle of a tool call.
 *
 * Audit listeners run synchronously on the caller's execution thread. They must
 * return quickly and move persistence or network work onto their own executor.
 */
public sealed interface ToolAuditEvent {
    public val call: ToolCall
    public val occurredAtEpochMillis: Long
    public val elapsedMillis: Long

    public data class Received(
        override val call: ToolCall,
        val spec: ToolSpec?,
        val approval: ToolApproval?,
        val attributes: Map<String, String>,
        override val occurredAtEpochMillis: Long,
        override val elapsedMillis: Long = 0L,
    ) : ToolAuditEvent

    public data class ValidationCompleted(
        override val call: ToolCall,
        val violations: List<ToolInputViolation>,
        override val occurredAtEpochMillis: Long,
        override val elapsedMillis: Long,
    ) : ToolAuditEvent {
        public val valid: Boolean get() = violations.isEmpty()
    }

    public data class ApprovalRequested(
        override val call: ToolCall,
        override val occurredAtEpochMillis: Long,
        override val elapsedMillis: Long,
    ) : ToolAuditEvent

    public data class ApprovalResolved(
        override val call: ToolCall,
        val decision: ToolApprovalDecision,
        override val occurredAtEpochMillis: Long,
        override val elapsedMillis: Long,
    ) : ToolAuditEvent

    public data class ApprovalFailed(
        override val call: ToolCall,
        val code: String,
        override val occurredAtEpochMillis: Long,
        override val elapsedMillis: Long,
    ) : ToolAuditEvent

    public data class ExecutionStarted(
        override val call: ToolCall,
        override val occurredAtEpochMillis: Long,
        override val elapsedMillis: Long,
    ) : ToolAuditEvent

    public data class Cancelled(
        override val call: ToolCall,
        val stage: ToolAuditStage,
        override val occurredAtEpochMillis: Long,
        override val elapsedMillis: Long,
    ) : ToolAuditEvent

    public data class Completed(
        override val call: ToolCall,
        val result: ToolResult,
        override val occurredAtEpochMillis: Long,
        override val elapsedMillis: Long,
    ) : ToolAuditEvent
}

public enum class ToolAuditStage {
    APPROVAL,
    EXECUTION,
}

/** Receives immutable snapshots of actual runtime events. */
public fun interface ToolAuditListener {
    public fun onEvent(event: ToolAuditEvent)
}

/** Detaches one listener from a [ToolRuntime]. Closing more than once is safe. */
public class ToolAuditSubscription internal constructor(
    private val removeListener: () -> Unit,
) : AutoCloseable {
    private val closed: AtomicBoolean = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            removeListener()
        }
    }
}
