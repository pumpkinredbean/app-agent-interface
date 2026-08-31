package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

public object ToolProtocol {
    public const val VERSION: String = "app-agent/1"

    @JvmStatic
    public fun encodeManifest(manifest: ToolManifest): String = buildJsonObject {
        put("protocol", VERSION)
        put("type", "tools.list")
        put("tools", buildJsonArray {
            manifest.tools.forEach { spec -> add(encodeSpec(spec)) }
        })
    }.toString()

    @JvmStatic
    public fun decodeManifest(payload: String): ToolManifest {
        val root = ToolJson.parseObject(payload)
        requireProtocol(root, "tools.list")
        root.requireOnlyKeys("protocol", "type", "tools")
        val tools = (root["tools"] as? JsonArray)
            ?.map { decodeSpec(it as? JsonObject ?: throw ToolProtocolException("tools must contain objects")) }
            ?: throw ToolProtocolException("tools is required")
        return ToolManifest(tools)
    }

    @JvmStatic
    public fun encodeCall(call: ToolCall): String = buildJsonObject {
        put("protocol", VERSION)
        put("type", "tool.call")
        put("id", call.id)
        put("name", call.name)
        put("input", call.input)
    }.toString()

    @JvmStatic
    public fun decodeCall(payload: String): ToolCall {
        val root = ToolJson.parseObject(payload)
        requireProtocol(root, "tool.call")
        root.requireOnlyKeys("protocol", "type", "id", "name", "input")
        return ToolCall(
            id = root.requiredString("id"),
            name = root.requiredString("name"),
            input = root["input"] as? JsonObject ?: throw ToolProtocolException("input must be an object"),
        )
    }

    @JvmStatic
    public fun encodeResult(result: ToolResult): String = buildJsonObject {
        put("protocol", VERSION)
        put("type", "tool.result")
        put("id", result.id)
        put("ok", result.ok)
        if (result.ok) {
            put("output", result.output ?: JsonNull)
        } else {
            val error = requireNotNull(result.error)
            put("error", buildJsonObject {
                put("code", error.code)
                put("message", error.message)
                put("retryable", error.retryable)
            })
        }
    }.toString()

    @JvmStatic
    public fun decodeResult(payload: String): ToolResult {
        val root = ToolJson.parseObject(payload)
        requireProtocol(root, "tool.result")
        root.requireOnlyKeys("protocol", "type", "id", "ok", "output", "error")
        val id = root.requiredString("id")
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull
            ?: throw ToolProtocolException("ok must be a boolean")
        return if (ok) {
            ToolResult.success(id, root["output"] ?: throw ToolProtocolException("output is required"))
        } else {
            val error = root["error"] as? JsonObject ?: throw ToolProtocolException("error is required")
            ToolResult.failure(
                id = id,
                code = error.requiredString("code"),
                message = error.requiredString("message"),
                retryable = (error["retryable"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
    }

    @JvmStatic
    @JvmOverloads
    public fun encodeProtocolError(
        code: String,
        message: String,
        retryable: Boolean = false,
    ): String = buildJsonObject {
        put("protocol", VERSION)
        put("type", "protocol.error")
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
            put("retryable", retryable)
        })
    }.toString()

    private fun encodeSpec(spec: ToolSpec): JsonObject = buildJsonObject {
        put("name", spec.name)
        put("description", spec.description)
        put("inputSchema", spec.inputSchema)
    }

    private fun decodeSpec(value: JsonObject): ToolSpec {
        value.requireOnlyKeys("name", "description", "inputSchema")
        return ToolSpec(
            name = value.requiredString("name"),
            description = value.requiredString("description"),
            inputSchema = value["inputSchema"] as? JsonObject
                ?: throw ToolProtocolException("inputSchema must be an object"),
        )
    }

    private fun requireProtocol(root: JsonObject, expectedType: String) {
        if (root.requiredString("protocol") != VERSION) {
            throw ToolProtocolException("Unsupported protocol; expected $VERSION")
        }
        if (root.requiredString("type") != expectedType) {
            throw ToolProtocolException("Expected message type $expectedType")
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        (this[name] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw ToolProtocolException("$name must be a non-empty string")

    private fun JsonObject.requireOnlyKeys(vararg allowed: String) {
        val unexpected = keys - allowed.toSet()
        if (unexpected.isNotEmpty()) {
            throw ToolProtocolException("Unexpected fields: ${unexpected.sorted().joinToString()}")
        }
    }
}

public class ToolProtocolException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
