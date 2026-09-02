package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRuntimeTest {
    @Test
    fun `registered handler executes with validated input`() {
        val runtime = ToolRuntime()
        runtime.register(profileSpec()) { input, context ->
            buildJsonObject {
                put("userId", input.getValue("userId"))
                put("callId", context.callId)
            }
        }

        val result = runtime.executeBlocking(
            ToolCall("call-1", "get_profile", buildJsonObject { put("userId", "user-7") }),
        )

        assertTrue(result.ok)
        assertEquals("user-7", result.output!!.jsonObject["userId"]?.let { (it as JsonPrimitive).content })
        assertEquals("call-1", result.output.jsonObject["callId"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun `invalid model input never reaches the handler`() {
        var executed = false
        val runtime = ToolRuntime()
        runtime.register(profileSpec()) { _, _ ->
            executed = true
            buildJsonObject {}
        }

        val result = runtime.executeBlocking(
            ToolCall("call-2", "get_profile", buildJsonObject { put("unexpected", true) }),
        )

        assertFalse(result.ok)
        assertEquals("INVALID_INPUT", result.error?.code)
        assertFalse(executed)
    }

    @Test
    fun `mobile approval is runtime policy and is not exposed in manifest`() {
        var approvalRequested = false
        val runtime = ToolRuntime(
            approvalHandler = ToolApprovalHandler {
                approvalRequested = true
                false
            },
        )
        runtime.register(profileSpec(), ToolApproval.MOBILE) { _, _ -> buildJsonObject {} }

        val manifestPayload = ToolJson.parseObject(ToolProtocol.encodeManifest(runtime.manifest()))
        val result = runtime.executeBlocking(
            ToolCall("call-3", "get_profile", buildJsonObject { put("userId", "user-7") }),
        )

        assertFalse(manifestPayload.toString().contains("approval", ignoreCase = true))
        assertTrue(approvalRequested)
        assertFalse(result.ok)
        assertEquals("APPROVAL_DENIED", result.error?.code)
    }

    @Test
    fun `blocking approval adapter supports Java style coordinators`() {
        var handledCallId = ""
        val runtime = ToolRuntime.withBlockingApproval(
            BlockingToolApprovalHandler { request ->
                handledCallId = request.call.id
                true
            },
        )
        runtime.register(profileSpec(), ToolApproval.MOBILE) { _, _ ->
            buildJsonObject { put("approved", true) }
        }

        val result = runtime.executeBlocking(
            ToolCall("call-blocking", "get_profile", buildJsonObject { put("userId", "user-7") }),
        )

        assertTrue(result.ok)
        assertEquals("call-blocking", handledCallId)
        assertEquals(true, result.output!!.jsonObject["approved"]?.let { (it as JsonPrimitive).content.toBoolean() })
    }

    @Test
    fun `detailed approval outcomes preserve timeout and unavailable failures`() {
        val timeoutRuntime = ToolRuntime(
            approvalDecisionHandler = ToolApprovalDecisionHandler {
                ToolApprovalDecision.TIMED_OUT
            },
        )
        timeoutRuntime.register(profileSpec(), ToolApproval.MOBILE) { _, _ -> buildJsonObject {} }

        val timeout = timeoutRuntime.executeBlocking(
            ToolCall("call-timeout", "get_profile", buildJsonObject { put("userId", "user-7") }),
        )

        assertFalse(timeout.ok)
        assertEquals("APPROVAL_TIMEOUT", timeout.error?.code)
        assertTrue(timeout.error?.retryable == true)

        val unavailableRuntime = ToolRuntime(
            approvalDecisionHandler = ToolApprovalDecisionHandler {
                ToolApprovalDecision.UNAVAILABLE
            },
        )
        unavailableRuntime.register(profileSpec(), ToolApproval.MOBILE) { _, _ -> buildJsonObject {} }

        val unavailable = unavailableRuntime.executeBlocking(
            ToolCall("call-unavailable", "get_profile", buildJsonObject { put("userId", "user-7") }),
        )

        assertFalse(unavailable.ok)
        assertEquals("APPROVAL_UNAVAILABLE", unavailable.error?.code)
    }

    private fun profileSpec(): ToolSpec = ToolSpec(
        name = "get_profile",
        description = "Returns a profile when the user asks for signed-in account information.",
        inputSchema = ToolJson.parseObject(
            """
            {
              "type": "object",
              "properties": {
                "userId": { "type": "string", "minLength": 1 }
              },
              "required": ["userId"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
    )
}
