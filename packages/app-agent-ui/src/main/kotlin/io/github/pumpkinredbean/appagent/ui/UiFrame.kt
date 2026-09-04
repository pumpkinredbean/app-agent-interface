package io.github.pumpkinredbean.appagent.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Application-defined UI produced by a tool result.
 *
 * The frame is a runtime presentation contract. It is never added to the
 * model-visible ToolSpec.
 */
public data class UiFrame(
    val tag: String,
    val params: JsonObject,
    val payload: JsonElement,
) {
    init {
        require(tag.isNotBlank()) { "UiFrame tag must not be blank" }
    }

    /** Stable identity used by [UiFrameNavigator] for stack de-duplication. */
    public val key: String = keyOf(tag, params)

    public companion object {
        /** Creates a frame without navigation parameters. */
        @JvmStatic
        public fun create(tag: String, payload: JsonElement): UiFrame =
            UiFrame(tag = tag, params = buildJsonObject {}, payload = payload)

        /** Builds the canonical identity for a tag and parameter object. */
        @JvmStatic
        public fun keyOf(tag: String, params: JsonObject): String {
            require(tag.isNotBlank()) { "UiFrame tag must not be blank" }
            if (params.isEmpty()) return tag
            val query = params.entries
                .sortedBy(Map.Entry<String, JsonElement>::key)
                .joinToString("&") { (name, value) ->
                    "${encode(name)}=${encode(value.canonicalParamValue())}"
                }
            return "$tag?$query"
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%7E", "~")
                .replace("*", "%2A")
    }
}

private fun JsonElement.canonicalParamValue(): String = when (this) {
    is JsonObject -> entries
        .sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "${JsonPrimitive(key)}:${value.canonicalParamValue()}"
        }
    is JsonArray -> joinToString(prefix = "[", postfix = "]") { it.canonicalParamValue() }
    is JsonPrimitive -> if (isString) content else toString()
}
