package io.github.pumpkinredbean.appagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

public class ToolRuntime @JvmOverloads constructor(
    public val registry: ToolRegistry = ToolRegistry(),
    private val inputValidator: ToolInputValidator = PortableToolInputValidator(),
    private val approvalHandler: ToolApprovalHandler? = null,
) {
    public companion object {
        /** Creates a runtime with a Java-friendly blocking approval callback. */
        @JvmStatic
        public fun withBlockingApproval(approvalHandler: BlockingToolApprovalHandler): ToolRuntime =
            ToolRuntime(
                approvalHandler = ToolApprovalHandler { request ->
                    approvalHandler.requestApproval(request)
                },
            )
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
            val approver = approvalHandler
                ?: return ToolResult.failure(
                    call.id,
                    "APPROVAL_UNAVAILABLE",
                    "This tool requires mobile approval, but no approval handler is installed",
                )
            val approved = try {
                approver.requestApproval(ToolApprovalRequest(call, registered.spec))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ToolResult.failure(call.id, "APPROVAL_FAILED", "The approval flow failed")
            }
            if (!approved) {
                return ToolResult.failure(call.id, "APPROVAL_DENIED", "The user declined this tool call")
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
