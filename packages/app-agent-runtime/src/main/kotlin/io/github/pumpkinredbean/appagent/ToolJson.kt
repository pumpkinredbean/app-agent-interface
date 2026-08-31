package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public object ToolJson {
    @JvmField
    public val codec: Json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

    @JvmStatic
    public fun parseElement(value: String): JsonElement = codec.parseToJsonElement(value)

    @JvmStatic
    public fun parseObject(value: String): JsonObject =
        parseElement(value) as? JsonObject ?: throw IllegalArgumentException("Expected a JSON object")

    @JvmStatic
    public fun stringify(value: JsonElement): String = value.toString()
}
