package io.github.pumpkinredbean.appagent.ui

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A model-facing value paired with an application-facing UI frame. */
public data class UiToolOutput(
    val modelOutput: JsonElement,
    val frame: UiFrame,
)

/**
 * Encodes optional UI inside the unrestricted ToolResult output value.
 *
 * This keeps app-agent/1 and plain JSON tool outputs unchanged. Harnesses
 * that understand UI frames pass only [UiToolOutput.modelOutput] back to the
 * model and send [UiToolOutput.frame] to their renderer.
 */
public object UiToolOutputCodec {
    public const val TYPE: String = "app-agent.ui-frame/1"

    @JvmStatic
    public fun encode(output: UiToolOutput): JsonObject = buildJsonObject {
        put("type", TYPE)
        put("modelOutput", output.modelOutput)
        put("frame", encodeFrame(output.frame))
    }

    /** Returns null for an ordinary tool output and throws for a malformed UI envelope. */
    @JvmStatic
    public fun decodeOrNull(value: JsonElement): UiToolOutput? {
        val root = value as? JsonObject ?: return null
        val type = (root["type"] as? JsonPrimitive)?.contentOrNull
        if (type != TYPE) return null
        root.requireOnlyKeys("type", "modelOutput", "frame")
        val modelOutput = root["modelOutput"]
            ?: throw UiToolOutputException("modelOutput is required")
        val frame = decodeFrame(
            root["frame"] as? JsonObject ?: throw UiToolOutputException("frame must be an object"),
        )
        return UiToolOutput(modelOutput = modelOutput, frame = frame)
    }

    /** Extracts the value that should be returned to the model. */
    @JvmStatic
    public fun modelOutput(value: JsonElement): JsonElement = decodeOrNull(value)?.modelOutput ?: value

    /** Extracts an optional frame without changing ordinary tool outputs. */
    @JvmStatic
    public fun frame(value: JsonElement): UiFrame? = decodeOrNull(value)?.frame

    private fun encodeFrame(frame: UiFrame): JsonObject = buildJsonObject {
        put("tag", frame.tag)
        put("params", frame.params)
        put("payload", frame.payload)
    }

    private fun decodeFrame(value: JsonObject): UiFrame {
        value.requireOnlyKeys("tag", "params", "payload")
        val tag = (value["tag"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw UiToolOutputException("frame.tag must be a non-empty string")
        val params = value["params"] as? JsonObject
            ?: throw UiToolOutputException("frame.params must be an object")
        val payload = value["payload"] ?: throw UiToolOutputException("frame.payload is required")
        return UiFrame(tag = tag, params = params, payload = payload)
    }

    private fun JsonObject.requireOnlyKeys(vararg allowed: String) {
        val unexpected = keys - allowed.toSet()
        if (unexpected.isNotEmpty()) {
            throw UiToolOutputException("Unexpected fields: ${unexpected.sorted().joinToString()}")
        }
    }
}

public class UiToolOutputException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
