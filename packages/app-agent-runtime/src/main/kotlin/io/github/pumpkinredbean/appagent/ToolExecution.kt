package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Execution policy; this is never serialized into the model-visible ToolSpec. */
public enum class ToolApproval {
    NONE,
    MOBILE,
}

public data class ToolExecutionContext @JvmOverloads constructor(
    val callId: String,
    val attributes: Map<String, String> = emptyMap(),
)

public fun interface ToolHandler {
    public suspend fun execute(input: JsonObject, context: ToolExecutionContext): JsonElement
}

/** Java-friendly registration path for handlers that already block on their own I/O. */
public fun interface BlockingToolHandler {
    public fun execute(input: JsonObject, context: ToolExecutionContext): JsonElement
}

public data class ToolApprovalRequest(
    val call: ToolCall,
    val spec: ToolSpec,
)

public fun interface ToolApprovalHandler {
    public suspend fun requestApproval(request: ToolApprovalRequest): Boolean
}

/** Java-friendly approval adapter for UI coordinators that block until the user decides. */
public fun interface BlockingToolApprovalHandler {
    public fun requestApproval(request: ToolApprovalRequest): Boolean
}

/** Throw from an app handler to return a controlled, non-sensitive ToolResult error. */
public class ToolExecutionException @JvmOverloads constructor(
    public val code: String,
    override val message: String,
    public val retryable: Boolean = false,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    init {
        require(code.isNotBlank()) { "ToolExecutionException code must not be blank" }
        require(message.isNotBlank()) { "ToolExecutionException message must not be blank" }
    }
}
