package io.github.pumpkinredbean.appagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList

public class ToolRuntime @JvmOverloads constructor(
    public val registry: ToolRegistry = ToolRegistry(),
    private val inputValidator: ToolInputValidator = PortableToolInputValidator(),
    private val approvalHandler: ToolApprovalHandler? = null,
    private val approvalDecisionHandler: ToolApprovalDecisionHandler? = null,
) {
    private val auditListeners: CopyOnWriteArrayList<ToolAuditListener> = CopyOnWriteArrayList()

    init {
        require(approvalHandler == null || approvalDecisionHandler == null) {
            "Install either approvalHandler or approvalDecisionHandler, not both"
        }
    }

    public companion object {
        /** Creates a runtime with a Java-friendly blocking approval callback. */
        @JvmStatic
        public fun withBlockingApproval(approvalHandler: BlockingToolApprovalHandler): ToolRuntime =
            ToolRuntime(
                approvalHandler = ToolApprovalHandler { request ->
                    approvalHandler.requestApproval(request)
                },
            )

        /** Creates a runtime with a Java-friendly detailed blocking approval callback. */
        @JvmStatic
        public fun withBlockingDecisionApproval(
            approvalHandler: BlockingToolApprovalDecisionHandler,
        ): ToolRuntime = ToolRuntime(
            approvalDecisionHandler = ToolApprovalDecisionHandler { request ->
                approvalHandler.requestApproval(request)
            },
        )

        /** Creates a runtime with a detailed suspending handler from Java. */
        @JvmStatic
        public fun withDecisionApproval(
            approvalHandler: ToolApprovalDecisionHandler,
        ): ToolRuntime = ToolRuntime(approvalDecisionHandler = approvalHandler)
    }

    public fun manifest(): ToolManifest = ToolManifest(registry.specs())

    /**
     * Registers a process-local observer for actual tool lifecycle events.
     *
     * Listener failures are isolated from tool execution. Close the returned
     * subscription when the observer should stop receiving events.
     */
    public fun addAuditListener(listener: ToolAuditListener): ToolAuditSubscription {
        auditListeners += listener
        return ToolAuditSubscription { auditListeners -= listener }
    }

    public fun register(
        spec: ToolSpec,
        approval: ToolApproval = ToolApproval.NONE,
        handler: ToolHandler,
    ): ToolRegistration = registry.register(spec, approval, handler)

    @JvmOverloads
    public fun registerBlocking(
        spec: ToolSpec,
        approval: ToolApproval = ToolApproval.NONE,
        handler: BlockingToolHandler,
    ): ToolRegistration = registry.registerBlocking(spec, approval, handler)

    public suspend fun execute(
        call: ToolCall,
        attributes: Map<String, String> = emptyMap(),
    ): ToolResult {
        val startedAtNanos = System.nanoTime()
        val registered = registry.find(call.name)

        fun elapsedMillis(): Long =
            ((System.nanoTime() - startedAtNanos) / 1_000_000L).coerceAtLeast(0L)

        fun finish(result: ToolResult): ToolResult {
            emitAudit(
                ToolAuditEvent.Completed(
                    call = call,
                    result = result,
                    occurredAtEpochMillis = System.currentTimeMillis(),
                    elapsedMillis = elapsedMillis(),
                ),
            )
            return result
        }

        emitAudit(
            ToolAuditEvent.Received(
                call = call,
                spec = registered?.spec,
                approval = registered?.approval,
                attributes = attributes.toMap(),
                occurredAtEpochMillis = System.currentTimeMillis(),
            ),
        )

        if (registered == null) {
            return finish(
                ToolResult.failure(call.id, "TOOL_NOT_FOUND", "Unknown tool: ${call.name}"),
            )
        }

        val violations = inputValidator.validate(registered.spec.inputSchema, call.input)
        emitAudit(
            ToolAuditEvent.ValidationCompleted(
                call = call,
                violations = violations,
                occurredAtEpochMillis = System.currentTimeMillis(),
                elapsedMillis = elapsedMillis(),
            ),
        )
        if (violations.isNotEmpty()) {
            return finish(
                ToolResult.failure(
                    call.id,
                    "INVALID_INPUT",
                    violations.take(8).joinToString(separator = "; "),
                ),
            )
        }

        if (registered.approval == ToolApproval.MOBILE) {
            emitAudit(
                ToolAuditEvent.ApprovalRequested(
                    call = call,
                    occurredAtEpochMillis = System.currentTimeMillis(),
                    elapsedMillis = elapsedMillis(),
                ),
            )
            if (approvalHandler == null && approvalDecisionHandler == null) {
                emitAudit(
                    ToolAuditEvent.ApprovalResolved(
                        call = call,
                        decision = ToolApprovalDecision.UNAVAILABLE,
                        occurredAtEpochMillis = System.currentTimeMillis(),
                        elapsedMillis = elapsedMillis(),
                    ),
                )
                return finish(
                    ToolResult.failure(
                        call.id,
                        "APPROVAL_UNAVAILABLE",
                        "This tool requires mobile approval, but no approval handler is installed",
                    ),
                )
            }

            val request = ToolApprovalRequest(call, registered.spec)
            val decision = try {
                approvalDecisionHandler?.requestApproval(request)
                    ?: if (approvalHandler!!.requestApproval(request)) {
                        ToolApprovalDecision.APPROVED
                    } else {
                        ToolApprovalDecision.DENIED
                    }
            } catch (cancelled: CancellationException) {
                emitAudit(
                    ToolAuditEvent.Cancelled(
                        call = call,
                        stage = ToolAuditStage.APPROVAL,
                        occurredAtEpochMillis = System.currentTimeMillis(),
                        elapsedMillis = elapsedMillis(),
                    ),
                )
                throw cancelled
            } catch (_: Exception) {
                emitAudit(
                    ToolAuditEvent.ApprovalFailed(
                        call = call,
                        code = "APPROVAL_FAILED",
                        occurredAtEpochMillis = System.currentTimeMillis(),
                        elapsedMillis = elapsedMillis(),
                    ),
                )
                return finish(
                    ToolResult.failure(call.id, "APPROVAL_FAILED", "The approval flow failed"),
                )
            }

            emitAudit(
                ToolAuditEvent.ApprovalResolved(
                    call = call,
                    decision = decision,
                    occurredAtEpochMillis = System.currentTimeMillis(),
                    elapsedMillis = elapsedMillis(),
                ),
            )
            when (decision) {
                ToolApprovalDecision.APPROVED -> Unit
                ToolApprovalDecision.DENIED -> return finish(
                    ToolResult.failure(
                        call.id,
                        "APPROVAL_DENIED",
                        "The user declined this tool call",
                    ),
                )
                ToolApprovalDecision.TIMED_OUT -> return finish(
                    ToolResult.failure(
                        call.id,
                        "APPROVAL_TIMEOUT",
                        "The approval request timed out",
                        retryable = true,
                    ),
                )
                ToolApprovalDecision.UNAVAILABLE -> return finish(
                    ToolResult.failure(
                        call.id,
                        "APPROVAL_UNAVAILABLE",
                        "The application could not present this approval request",
                    ),
                )
            }
        }

        emitAudit(
            ToolAuditEvent.ExecutionStarted(
                call = call,
                occurredAtEpochMillis = System.currentTimeMillis(),
                elapsedMillis = elapsedMillis(),
            ),
        )
        return try {
            val context = ToolExecutionContext(callId = call.id, attributes = attributes)
            finish(ToolResult.success(call.id, registered.handler.execute(call.input, context)))
        } catch (cancelled: CancellationException) {
            emitAudit(
                ToolAuditEvent.Cancelled(
                    call = call,
                    stage = ToolAuditStage.EXECUTION,
                    occurredAtEpochMillis = System.currentTimeMillis(),
                    elapsedMillis = elapsedMillis(),
                ),
            )
            throw cancelled
        } catch (controlled: ToolExecutionException) {
            finish(ToolResult.failure(call.id, controlled.code, controlled.message, controlled.retryable))
        } catch (_: Exception) {
            finish(
                ToolResult.failure(
                    call.id,
                    "TOOL_EXECUTION_FAILED",
                    "Tool execution failed",
                    retryable = false,
                ),
            )
        }
    }

    @JvmOverloads
    public fun executeBlocking(
        call: ToolCall,
        attributes: Map<String, String> = emptyMap(),
    ): ToolResult = runBlocking { execute(call, attributes) }

    private fun emitAudit(event: ToolAuditEvent) {
        auditListeners.forEach { listener ->
            runCatching { listener.onEvent(event) }
        }
    }
}
