package io.github.pumpkinredbean.appagent.ui

import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolJson
import io.github.pumpkinredbean.appagent.ToolProtocol
import io.github.pumpkinredbean.appagent.ToolRuntime
import io.github.pumpkinredbean.appagent.ToolSpec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiToolOutputTest {
    @Test
    fun `UI output round trips through the existing tool result protocol`() {
        val expected = UiToolOutput(
            modelOutput = buildJsonObject { put("summary", "BTC is up 4.46%") },
            frame = UiFrame(
                tag = "market.asset",
                params = buildJsonObject { put("symbol", "BTC") },
                payload = buildJsonObject { put("price", 72_101.0) },
            ),
        )
        val runtime = ToolRuntime()
        runtime.registerUi(spec()) { _, _ -> expected }

        val result = runtime.executeBlocking(ToolCall("call-ui", "visualize_market", buildJsonObject {}))
        val decodedResult = ToolProtocol.decodeResult(ToolProtocol.encodeResult(result))
        val decodedOutput = UiToolOutputCodec.decodeOrNull(requireNotNull(decodedResult.output))

        assertTrue(decodedResult.ok)
        assertEquals(expected, decodedOutput)
        assertEquals(expected.modelOutput, UiToolOutputCodec.modelOutput(requireNotNull(decodedResult.output)))
        assertEquals(expected.frame, UiToolOutputCodec.frame(requireNotNull(decodedResult.output)))
    }

    @Test
    fun `ordinary tool output stays ordinary`() {
        val plain = buildJsonObject { put("price", 72_101.0) }

        assertNull(UiToolOutputCodec.decodeOrNull(plain))
        assertNull(UiToolOutputCodec.frame(plain))
        assertEquals(plain, UiToolOutputCodec.modelOutput(plain))
    }

    @Test
    fun `encoded frame contains only the minimal frame fields`() {
        val encoded = UiToolOutputCodec.encode(
            UiToolOutput(
                modelOutput = JsonPrimitive("Ready"),
                frame = UiFrame.create("portfolio.positions", buildJsonObject { put("count", 3) }),
            ),
        )
        val frame = encoded.getValue("frame").jsonObject

        assertEquals(setOf("type", "modelOutput", "frame"), encoded.keys)
        assertEquals(setOf("tag", "params", "payload"), frame.keys)
    }

    @Test
    fun `malformed marked output is rejected`() {
        val malformed = buildJsonObject { put("type", UiToolOutputCodec.TYPE) }

        assertFailsWith<UiToolOutputException> {
            UiToolOutputCodec.decodeOrNull(malformed)
        }
    }

    private fun spec(): ToolSpec = ToolSpec(
        name = "visualize_market",
        description = "Returns a market view when the user explicitly asks to see it.",
        inputSchema = ToolJson.parseObject(
            """{"type":"object","properties":{},"additionalProperties":false}""",
        ),
    )
}
