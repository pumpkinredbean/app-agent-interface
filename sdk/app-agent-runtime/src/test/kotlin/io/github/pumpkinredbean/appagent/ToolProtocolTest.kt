package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolProtocolTest {
    @Test
    fun `tool call round trips`() {
        val call = ToolCall(
            id = "call-123",
            name = "search_items",
            input = buildJsonObject { put("query", "sample") },
        )

        assertEquals(call, ToolProtocol.decodeCall(ToolProtocol.encodeCall(call)))
    }

    @Test
    fun `success and failure results round trip`() {
        val success = ToolResult.success("call-1", buildJsonObject { put("count", 3) })
        val failure = ToolResult.failure("call-2", "NOT_FOUND", "No item matched")

        assertEquals(success, ToolProtocol.decodeResult(ToolProtocol.encodeResult(success)))
        assertEquals(failure, ToolProtocol.decodeResult(ToolProtocol.encodeResult(failure)))
        assertTrue(ToolProtocol.decodeResult(ToolProtocol.encodeResult(success)).ok)
        assertFalse(ToolProtocol.decodeResult(ToolProtocol.encodeResult(failure)).ok)
    }

    @Test
    fun `v1 decoder rejects fields outside the protocol contract`() {
        assertFailsWith<ToolProtocolException> {
            ToolProtocol.decodeCall(
                """{"protocol":"app-agent/1","type":"tool.call","id":"1","name":"echo","input":{},"source":"model"}""",
            )
        }
    }
}
