package io.github.pumpkinredbean.appagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

public class ToolRuntime @JvmOverloads constructor(
    public val registry: ToolRegistry = ToolRegistry(),
    private val inputValidator: ToolInputValidator = PortableToolInputValidator(),
    private val approvalHandler: ToolApprovalHandler? = null,
    private val approvalDecisionHandler: ToolApprovalDecisionHandler? = null,
) {
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
        val registered = registry.find(call.name)
            ?: return ToolResult.failure(call.id, "TOOL_NOT_FOUND", "Unknown tool: ${call.name}")

        val violations = inputValidator.validate(registered.spec.inputSchema, call.input)
        if (violations.isNotEmpty()) {
            return ToolResult.failure(
                call.id,
                "INVALID_INPUT",
                violations.take(8).joinToString(separator = "; "),
            )
        }

        if (registered.approval == ToolApproval.MOBILE) {
            if (approvalHandler == null && approvalDecisionHandler == null) {
                return ToolResult.failure(
                    call.id,
                    "APPROVAL_UNAVAILABLE",
                    "This tool requires mobile approval, but no approval handler is installed",
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
                throw cancelled
            } catch (_: Exception) {
                return ToolResult.failure(call.id, "APPROVAL_FAILED", "The approval flow failed")
            }

            when (decision) {
                ToolApprovalDecision.APPROVED -> Unit
                ToolApprovalDecision.DENIED -> return ToolResult.failure(
                    call.id,
                    "APPROVAL_DENIED",
                    "The user declined this tool call",
                )
                ToolApprovalDecision.TIMED_OUT -> return ToolResult.failure(
                    call.id,
                    "APPROVAL_TIMEOUT",
                    "The approval request timed out",
                    retryable = true,
                )
                ToolApprovalDecision.UNAVAILABLE -> return ToolResult.failure(
                    call.id,
                    "APPROVAL_UNAVAILABLE",
                    "The application could not present this approval request",
                )
            }
        }

        return try {
            val context = ToolExecutionContext(callId = call.id, attributes = attributes)
            ToolResult.success(call.id, registered.handler.execute(call.input, context))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (controlled: ToolExecutionException) {
            ToolResult.failure(call.id, controlled.code, controlled.message, controlled.retryable)
        } catch (_: Exception) {
            ToolResult.failure(call.id, "TOOL_EXECUTION_FAILED", "Tool execution failed", retryable = false)
        }
    }

    @JvmOverloads
    public fun executeBlocking(
        call: ToolCall,
        attributes: Map<String, String> = emptyMap(),
    ): ToolResult = runBlocking { execute(call, attributes) }
}
