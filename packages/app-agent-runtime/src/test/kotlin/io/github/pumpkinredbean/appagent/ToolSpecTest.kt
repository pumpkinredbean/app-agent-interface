package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolSpecTest {
    @Test
    fun `tool spec contains only the portable model contract`() {
        val spec = ToolSpec(
            name = "get_profile",
            description = "Returns the signed-in user's profile when the user asks about their account.",
            inputSchema = objectSchema(),
        )

        val payload = ToolJson.parseObject(ToolProtocol.encodeManifest(ToolManifest(listOf(spec))))
        val serializedSpec = payload["tools"]
            .let { it as kotlinx.serialization.json.JsonArray }
            .single()
            .let { it as JsonObject }

        assertEquals(setOf("name", "description", "inputSchema"), serializedSpec.keys)
    }

    @Test
    fun `description is required`() {
        assertFailsWith<IllegalArgumentException> {
            ToolSpec("get_profile", "   ", objectSchema())
        }
    }

    @Test
    fun `name must be portable across providers`() {
        assertFailsWith<IllegalArgumentException> {
            ToolSpec("profile.get", "Gets a profile when requested.", objectSchema())
        }
    }

    private fun objectSchema(): JsonObject = JsonObject(mapOf("type" to JsonPrimitive("object")))
}
