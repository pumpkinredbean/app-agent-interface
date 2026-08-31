package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

public data class ToolInputViolation(
    val path: String,
    val message: String,
) {
    override fun toString(): String = "$path: $message"
}

public fun interface ToolInputValidator {
    public fun validate(schema: JsonObject, input: JsonObject): List<ToolInputViolation>
}

/**
 * Validator for the provider-portable JSON Schema subset used by function tools.
 * Unknown keywords are intentionally ignored so provider adapters can retain
 * descriptive annotations without changing runtime behavior.
 */
public class PortableToolInputValidator : ToolInputValidator {
    override fun validate(schema: JsonObject, input: JsonObject): List<ToolInputViolation> =
        buildList { validateValue(schema, input, "$", this) }

    private fun validateValue(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        violations: MutableList<ToolInputViolation>,
    ) {
        validateEnumAndConst(schema, value, path, violations)

        when (stringKeyword(schema, "type")) {
            null -> Unit
            "object" -> validateObject(schema, value, path, violations)
            "array" -> validateArray(schema, value, path, violations)
            "string" -> validateString(schema, value, path, violations)
            "integer" -> validateInteger(schema, value, path, violations)
            "number" -> validateNumber(schema, value, path, violations)
            "boolean" -> if ((value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull == null) {
                violations += ToolInputViolation(path, "must be a boolean")
            }
            "null" -> if (value !is JsonNull) {
                violations += ToolInputViolation(path, "must be null")
            }
            else -> violations += ToolInputViolation(path, "uses an unsupported schema type")
        }
    }

    private fun validateObject(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        violations: MutableList<ToolInputViolation>,
    ) {
        val objectValue = value as? JsonObject
        if (objectValue == null) {
            violations += ToolInputViolation(path, "must be an object")
            return
        }

        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

        required.filterNot(objectValue::containsKey).forEach { key ->
            violations += ToolInputViolation(childPath(path, key), "is required")
        }

        objectValue.forEach { (key, child) ->
            val childSchema = properties[key] as? JsonObject
            if (childSchema != null) {
                validateValue(childSchema, child, childPath(path, key), violations)
            } else if (booleanKeyword(schema, "additionalProperties") == false) {
                violations += ToolInputViolation(childPath(path, key), "is not allowed")
            }
        }
    }

    private fun validateArray(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        violations: MutableList<ToolInputViolation>,
    ) {
        val arrayValue = value as? JsonArray
        if (arrayValue == null) {
            violations += ToolInputViolation(path, "must be an array")
            return
        }

        intKeyword(schema, "minItems")?.let { minimum ->
            if (arrayValue.size < minimum) violations += ToolInputViolation(path, "must contain at least $minimum items")
        }
        intKeyword(schema, "maxItems")?.let { maximum ->
            if (arrayValue.size > maximum) violations += ToolInputViolation(path, "must contain at most $maximum items")
        }
        if (booleanKeyword(schema, "uniqueItems") == true && arrayValue.distinct().size != arrayValue.size) {
            violations += ToolInputViolation(path, "must contain unique items")
        }

        val itemSchema = schema["items"] as? JsonObject ?: return
        arrayValue.forEachIndexed { index, child ->
            validateValue(itemSchema, child, "$path[$index]", violations)
        }
    }

    private fun validateString(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        violations: MutableList<ToolInputViolation>,
    ) {
        val primitive = value as? JsonPrimitive
        val text = primitive?.takeIf(JsonPrimitive::isString)?.contentOrNull
        if (text == null) {
            violations += ToolInputViolation(path, "must be a string")
            return
        }

        intKeyword(schema, "minLength")?.let { minimum ->
            if (text.length < minimum) violations += ToolInputViolation(path, "must be at least $minimum characters")
        }
        intKeyword(schema, "maxLength")?.let { maximum ->
            if (text.length > maximum) violations += ToolInputViolation(path, "must be at most $maximum characters")
        }
        stringKeyword(schema, "pattern")?.let { pattern ->
            val matches = runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
            if (!matches) violations += ToolInputViolation(path, "must match pattern $pattern")
        }
    }

    private fun validateInteger(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        violations: MutableList<ToolInputViolation>,
    ) {
        val number = (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
        if (number == null) {
            violations += ToolInputViolation(path, "must be an integer")
            return
        }
        validateBounds(schema, number.toDouble(), path, violations)
    }

    private fun validateNumber(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        violations: MutableList<ToolInputViolation>,
    ) {
        val number = (value as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull
        if (number == null || !number.isFinite()) {
            violations += ToolInputViolation(path, "must be a finite number")
            return
        }
        validateBounds(schema, number, path, violations)
    }

    private fun validateBounds(
        schema: JsonObject,
        number: Double,
        path: String,
        violations: MutableList<ToolInputViolation>,
    ) {
        numberKeyword(schema, "minimum")?.let { minimum ->
            if (number < minimum) violations += ToolInputViolation(path, "must be at least $minimum")
        }
        numberKeyword(schema, "maximum")?.let { maximum ->
            if (number > maximum) violations += ToolInputViolation(path, "must be at most $maximum")
        }
    }

    private fun validateEnumAndConst(
        schema: JsonObject,
        value: JsonElement,
        path: String,
        violations: MutableList<ToolInputViolation>,
    ) {
        (schema["enum"] as? JsonArray)?.let { allowed ->
            if (value !in allowed) violations += ToolInputViolation(path, "must be one of the declared enum values")
        }
        schema["const"]?.let { expected ->
            if (value != expected) violations += ToolInputViolation(path, "must equal the declared const value")
        }
    }

    private fun stringKeyword(schema: JsonObject, name: String): String? =
        (schema[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull

    private fun booleanKeyword(schema: JsonObject, name: String): Boolean? =
        (schema[name] as? JsonPrimitive)?.booleanOrNull

    private fun intKeyword(schema: JsonObject, name: String): Int? =
        (schema[name] as? JsonPrimitive)?.intOrNull

    private fun numberKeyword(schema: JsonObject, name: String): Double? =
        (schema[name] as? JsonPrimitive)?.doubleOrNull

    private fun childPath(path: String, key: String): String =
        if (key.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))) "$path.$key" else "$path['$key']"
}
