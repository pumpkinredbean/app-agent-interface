package io.github.pumpkinredbean.appagent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolAuditTest {
    @Test
    fun successfulCallsEmitOnlyThePhasesThatActuallyOccurred() {
        val events = mutableListOf<ToolAuditEvent>()
        val runtime = ToolRuntime()
        runtime.addAuditListener(events::add)
        runtime.register(spec()) { _, _ -> buildJsonObject { put("saved", true) } }

        val result = runtime.executeBlocking(call())

        assertTrue(result.ok)
        assertEquals(
            listOf(
                ToolAuditEvent.Received::class,
                ToolAuditEvent.ValidationCompleted::class,
                ToolAuditEvent.ExecutionStarted::class,
                ToolAuditEvent.Completed::class,
            ),
            events.map { it::class },
        )
        assertTrue(assertIs<ToolAuditEvent.ValidationCompleted>(events[1]).valid)
        assertEquals(result, assertIs<ToolAuditEvent.Completed>(events.last()).result)
    }

    @Test
    fun approvalOutcomesAreEmittedBeforeTheFinalResult() {
        val events = mutableListOf<ToolAuditEvent>()
        val runtime = ToolRuntime(
            approvalDecisionHandler = ToolApprovalDecisionHandler {
                ToolApprovalDecision.DENIED
            },
        )
        runtime.addAuditListener(events::add)
        runtime.register(spec(), ToolApproval.MOBILE) { _, _ -> buildJsonObject {} }

        val result = runtime.executeBlocking(call())

        assertFalse(result.ok)
        assertEquals("APPROVAL_DENIED", result.error?.code)
        assertEquals(
            listOf(
                ToolAuditEvent.Received::class,
                ToolAuditEvent.ValidationCompleted::class,
                ToolAuditEvent.ApprovalRequested::class,
                ToolAuditEvent.ApprovalResolved::class,
                ToolAuditEvent.Completed::class,
            ),
            events.map { it::class },
        )
        assertEquals(
            ToolApprovalDecision.DENIED,
            assertIs<ToolAuditEvent.ApprovalResolved>(events[3]).decision,
        )
    }

    @Test
    fun closedSubscriptionsStopReceivingEvents() {
        val events = mutableListOf<ToolAuditEvent>()
        val runtime = ToolRuntime()
        val subscription = runtime.addAuditListener(events::add)
        runtime.register(spec()) { _, _ -> buildJsonObject {} }

        subscription.close()
        subscription.close()
        runtime.executeBlocking(call())

        assertTrue(events.isEmpty())
    }

    @Test
    fun listenerFailuresNeverAffectToolExecution() {
        val runtime = ToolRuntime()
        runtime.addAuditListener { error("Inspector failed") }
        runtime.register(spec()) { _, _ -> buildJsonObject { put("saved", true) } }

        val result = runtime.executeBlocking(call())

        assertTrue(result.ok)
    }

    private fun spec(): ToolSpec = ToolSpec(
        name = "calendar_event_create",
        description = "Creates a calendar event in the current workspace.",
        inputSchema = ToolJson.parseObject(
            """
            {
              "type": "object",
              "properties": {
                "title": { "type": "string" }
              },
              "required": ["title"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
    )

    private fun call(): ToolCall = ToolCall(
        id = "call-audit-1",
        name = "calendar_event_create",
        input = buildJsonObject { put("title", "Project review") },
    )
}
