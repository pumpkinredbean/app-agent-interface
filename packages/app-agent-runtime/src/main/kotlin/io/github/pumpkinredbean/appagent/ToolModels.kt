package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public data class ToolManifest(
    val tools: List<ToolSpec>,
)

public data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject,
) {
    init {
        require(id.isNotBlank()) { "ToolCall id must not be blank" }
        require(name.isNotBlank()) { "ToolCall name must not be blank" }
    }
}

public data class ToolError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
) {
    init {
        require(code.isNotBlank()) { "Tool error code must not be blank" }
        require(message.isNotBlank()) { "Tool error message must not be blank" }
    }
}

public data class ToolResult(
    val id: String,
    val ok: Boolean,
    val output: JsonElement? = null,
    val error: ToolError? = null,
) {
    init {
        require(id.isNotBlank()) { "ToolResult id must not be blank" }
        require((ok && output != null && error == null) || (!ok && output == null && error != null)) {
            "A successful ToolResult requires output; a failed ToolResult requires error"
        }
    }

    public companion object {
        @JvmStatic
        public fun success(id: String, output: JsonElement): ToolResult =
            ToolResult(id = id, ok = true, output = output)

        @JvmStatic
        @JvmOverloads
        public fun failure(
            id: String,
            code: String,
            message: String,
            retryable: Boolean = false,
        ): ToolResult = ToolResult(
            id = id,
            ok = false,
            error = ToolError(code = code, message = message, retryable = retryable),
        )
    }
}
