package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The complete model-visible definition of an application tool.
 *
 * Runtime concerns such as handlers, approval and transport deliberately do not
 * belong to this type.
 */
public data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
) {
    init {
        require(PORTABLE_NAME.matches(name)) {
            "Tool name must match ${PORTABLE_NAME.pattern}: $name"
        }
        require(description.isNotBlank()) {
            "Tool description must explain what the tool does and when to use it"
        }
        val rootType = (inputSchema["type"] as? JsonPrimitive)?.contentOrNull
        require(rootType == "object") {
            "Tool inputSchema must be a JSON Schema object with type=object"
        }
    }

    public companion object {
        /** Portable intersection of the current OpenAI and Anthropic tool-name rules. */
        @JvmField
        public val PORTABLE_NAME: Regex = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
