package io.github.pumpkinredbean.appagent.inspector

import io.github.pumpkinredbean.appagent.ToolApproval
import io.github.pumpkinredbean.appagent.ToolApprovalDecision
import io.github.pumpkinredbean.appagent.ToolAuditEvent
import io.github.pumpkinredbean.appagent.ToolAuditListener
import io.github.pumpkinredbean.appagent.ToolAuditStage
import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolInputViolation
import io.github.pumpkinredbean.appagent.ToolProtocol
import io.github.pumpkinredbean.appagent.ToolResult
import io.github.pumpkinredbean.appagent.ToolSpec
import java.util.LinkedHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class InspectorAuditStore(
    appName: String,
    private val maxEntries: Int,
) : ToolAuditListener {
    private val lock = Any()
    private val records: LinkedHashMap<String, MutableCallRecord> = LinkedHashMap()
    private val mutableSnapshot = MutableStateFlow(
        InspectorSnapshot(appName = appName, attached = true),
    )

    val snapshot: StateFlow<InspectorSnapshot> = mutableSnapshot.asStateFlow()

    override fun onEvent(event: ToolAuditEvent) {
        synchronized(lock) {
            val record = if (event is ToolAuditEvent.Received) {
                records.remove(event.call.id)
                MutableCallRecord(
                    call = event.call,
                    spec = event.spec,
                    approvalPolicy = event.approval,
                    attributes = event.attributes,
                    receivedAtEpochMillis = event.occurredAtEpochMillis,
                ).also { records[event.call.id] = it }
            } else {
                records.getOrPut(event.call.id) {
                    MutableCallRecord(
                        call = event.call,
                        receivedAtEpochMillis = event.occurredAtEpochMillis - event.elapsedMillis,
                    )
                }
            }

            record.apply(event)
            trimToLimit()
            publish(attached = mutableSnapshot.value.attached)
        }
    }

    fun clear() {
        synchronized(lock) {
            records.clear()
            publish(attached = mutableSnapshot.value.attached)
        }
    }

    fun markDetached() {
        synchronized(lock) {
            publish(attached = false)
        }
    }

    private fun trimToLimit() {
        while (records.size > maxEntries) {
            val removable = records.entries.firstOrNull { it.value.isTerminal }
                ?: records.entries.first()
            records.remove(removable.key)
        }
    }

    private fun publish(attached: Boolean) {
        mutableSnapshot.value = InspectorSnapshot(
            appName = mutableSnapshot.value.appName,
            attached = attached,
            calls = records.values
                .map(MutableCallRecord::snapshot)
                .asReversed(),
        )
    }

    private data class MutableCallRecord(
        var call: ToolCall,
        var spec: ToolSpec? = null,
        var approvalPolicy: ToolApproval? = null,
        var attributes: Map<String, String> = emptyMap(),
        var receivedAtEpochMillis: Long,
        val lifecycle: MutableList<InspectorLifecycleEvent> = mutableListOf(),
        var validationViolations: List<ToolInputViolation> = emptyList(),
        var approvalRequestedElapsedMillis: Long? = null,
        var approvalResolvedElapsedMillis: Long? = null,
        var approvalDecision: ToolApprovalDecision? = null,
        var approvalFailureCode: String? = null,
        var result: ToolResult? = null,
        var durationMillis: Long? = null,
        var cancelledStage: ToolAuditStage? = null,
    ) {
        val isTerminal: Boolean get() = result != null || cancelledStage != null

        fun apply(event: ToolAuditEvent) {
            call = event.call
            when (event) {
                is ToolAuditEvent.Received -> {
                    spec = event.spec
                    approvalPolicy = event.approval
                    attributes = event.attributes
                    receivedAtEpochMillis = event.occurredAtEpochMillis
                    lifecycle += event.lifecycle("Received", InspectorEventTone.NEUTRAL)
                }
                is ToolAuditEvent.ValidationCompleted -> {
                    validationViolations = event.violations
                    lifecycle += if (event.valid) {
                        event.lifecycle("Validated", InspectorEventTone.SUCCESS)
                    } else {
                        event.lifecycle("Invalid input", InspectorEventTone.ERROR)
                    }
                }
                is ToolAuditEvent.ApprovalRequested -> {
                    approvalRequestedElapsedMillis = event.elapsedMillis
                    lifecycle += event.lifecycle("Approval requested", InspectorEventTone.WARNING)
                }
                is ToolAuditEvent.ApprovalResolved -> {
                    approvalDecision = event.decision
                    approvalResolvedElapsedMillis = event.elapsedMillis
                    lifecycle += event.lifecycle(
                        label = event.decision.displayLabel,
                        tone = when (event.decision) {
                            ToolApprovalDecision.APPROVED -> InspectorEventTone.SUCCESS
                            ToolApprovalDecision.DENIED,
                            ToolApprovalDecision.TIMED_OUT,
                            ToolApprovalDecision.UNAVAILABLE,
                            -> InspectorEventTone.ERROR
                        },
                    )
                }
                is ToolAuditEvent.ApprovalFailed -> {
                    approvalFailureCode = event.code
                    approvalResolvedElapsedMillis = event.elapsedMillis
                    lifecycle += event.lifecycle("Approval failed", InspectorEventTone.ERROR)
                }
                is ToolAuditEvent.ExecutionStarted -> {
                    lifecycle += event.lifecycle("Execution started", InspectorEventTone.NEUTRAL)
                }
                is ToolAuditEvent.Cancelled -> {
                    cancelledStage = event.stage
                    durationMillis = event.elapsedMillis
                    lifecycle += event.lifecycle("Cancelled", InspectorEventTone.ERROR)
                }
                is ToolAuditEvent.Completed -> {
                    result = event.result
                    durationMillis = event.elapsedMillis
                    lifecycle += event.lifecycle(
                        label = if (event.result.ok) "Completed" else "Returned error",
                        tone = if (event.result.ok) InspectorEventTone.SUCCESS else InspectorEventTone.ERROR,
                    )
                }
            }
        }

        fun snapshot(): InspectorCallRecord = InspectorCallRecord(
            call = call,
            description = spec?.description,
            approvalPolicy = approvalPolicy,
            attributes = attributes,
            receivedAtEpochMillis = receivedAtEpochMillis,
            lifecycle = lifecycle.toList(),
            validationViolations = validationViolations,
            approvalRequestedElapsedMillis = approvalRequestedElapsedMillis,
            approvalResolvedElapsedMillis = approvalResolvedElapsedMillis,
            approvalDecision = approvalDecision,
            approvalFailureCode = approvalFailureCode,
            result = result,
            durationMillis = durationMillis,
            cancelledStage = cancelledStage,
        )
    }
}

internal data class InspectorSnapshot(
    val appName: String,
    val attached: Boolean,
    val calls: List<InspectorCallRecord> = emptyList(),
)

internal data class InspectorCallRecord(
    val call: ToolCall,
    val description: String?,
    val approvalPolicy: ToolApproval?,
    val attributes: Map<String, String>,
    val receivedAtEpochMillis: Long,
    val lifecycle: List<InspectorLifecycleEvent>,
    val validationViolations: List<ToolInputViolation>,
    val approvalRequestedElapsedMillis: Long?,
    val approvalResolvedElapsedMillis: Long?,
    val approvalDecision: ToolApprovalDecision?,
    val approvalFailureCode: String?,
    val result: ToolResult?,
    val durationMillis: Long?,
    val cancelledStage: ToolAuditStage?,
) {
    val callState: InspectorCallState
        get() = when {
            cancelledStage != null -> InspectorCallState.CANCELLED
            result == null -> InspectorCallState.RUNNING
            result.ok -> InspectorCallState.COMPLETED
            result.error?.code == "APPROVAL_DENIED" -> InspectorCallState.DENIED
            else -> InspectorCallState.FAILED
        }

    val approvalState: InspectorApprovalState?
        get() = when {
            approvalPolicy != ToolApproval.MOBILE -> null
            approvalFailureCode != null -> InspectorApprovalState.FAILED
            approvalDecision == ToolApprovalDecision.APPROVED -> InspectorApprovalState.APPROVED
            approvalDecision == ToolApprovalDecision.DENIED -> InspectorApprovalState.DENIED
            approvalDecision == ToolApprovalDecision.TIMED_OUT -> InspectorApprovalState.TIMED_OUT
            approvalDecision == ToolApprovalDecision.UNAVAILABLE -> InspectorApprovalState.UNAVAILABLE
            approvalRequestedElapsedMillis != null -> InspectorApprovalState.WAITING
            else -> null
        }

    val approvalWaitMillis: Long?
        get() {
            val started = approvalRequestedElapsedMillis ?: return null
            val finished = approvalResolvedElapsedMillis ?: durationMillis ?: return null
            return (finished - started).coerceAtLeast(0L)
        }

    val source: String
        get() = attributes["source"]
            ?: if ("transport" in attributes) "External agent" else "In-app agent"

    val transport: String
        get() = attributes["transport"] ?: "direct"

    val requestPayload: String
        get() = ToolProtocol.encodeCall(call)

    val responsePayload: String?
        get() = result?.let(ToolProtocol::encodeResult)
}

internal data class InspectorLifecycleEvent(
    val label: String,
    val occurredAtEpochMillis: Long,
    val elapsedMillis: Long,
    val tone: InspectorEventTone,
)

internal enum class InspectorEventTone {
    NEUTRAL,
    SUCCESS,
    WARNING,
    ERROR,
}

internal enum class InspectorCallState(val label: String) {
    RUNNING("Running"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    DENIED("Denied"),
    CANCELLED("Cancelled"),
}

internal enum class InspectorApprovalState(val label: String) {
    WAITING("Waiting"),
    APPROVED("Approved"),
    DENIED("Denied"),
    TIMED_OUT("Timed out"),
    UNAVAILABLE("Unavailable"),
    FAILED("Failed"),
}

private val ToolApprovalDecision.displayLabel: String
    get() = when (this) {
        ToolApprovalDecision.APPROVED -> "Approved"
        ToolApprovalDecision.DENIED -> "Denied"
        ToolApprovalDecision.TIMED_OUT -> "Timed out"
        ToolApprovalDecision.UNAVAILABLE -> "Unavailable"
    }

private fun ToolAuditEvent.lifecycle(
    label: String,
    tone: InspectorEventTone,
): InspectorLifecycleEvent = InspectorLifecycleEvent(
    label = label,
    occurredAtEpochMillis = occurredAtEpochMillis,
    elapsedMillis = elapsedMillis,
    tone = tone,
)
