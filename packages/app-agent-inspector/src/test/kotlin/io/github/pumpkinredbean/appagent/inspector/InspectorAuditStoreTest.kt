package io.github.pumpkinredbean.appagent.inspector

import io.github.pumpkinredbean.appagent.ToolApproval
import io.github.pumpkinredbean.appagent.ToolApprovalDecision
import io.github.pumpkinredbean.appagent.ToolApprovalDecisionHandler
import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolJson
import io.github.pumpkinredbean.appagent.ToolRuntime
import io.github.pumpkinredbean.appagent.ToolSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectorAuditStoreTest {
    @Test
    fun recordsRealRuntimeEventsAndExecutionContext() {
        val store = InspectorAuditStore(appName = "Test app", maxEntries = 10)
        val runtime = ToolRuntime()
        runtime.addAuditListener(store)
        runtime.register(spec()) { _, _ -> buildJsonObject { put("created", true) } }

        runtime.executeBlocking(
            call("call-1"),
            attributes = mapOf("source" to "Desktop agent", "transport" to "loopback-http"),
        )

        val record = store.snapshot.value.calls.single()
        assertEquals("calendar_event_create", record.call.name)
        assertEquals("Desktop agent", record.source)
        assertEquals("loopback-http", record.transport)
        assertEquals(InspectorCallState.COMPLETED, record.callState)
        assertEquals(
            listOf("Received", "Validated", "Execution started", "Completed"),
            record.lifecycle.map(InspectorLifecycleEvent::label),
        )
        assertTrue(record.requestPayload.contains("Project review"))
        assertTrue(record.responsePayload.orEmpty().contains("created"))
    }

    @Test
    fun recordsApprovalDecisionsWithoutInventingAnActor() {
        val store = InspectorAuditStore(appName = "Test app", maxEntries = 10)
        val runtime = ToolRuntime(
            approvalDecisionHandler = ToolApprovalDecisionHandler {
                ToolApprovalDecision.DENIED
            },
        )
        runtime.addAuditListener(store)
        runtime.register(spec(), ToolApproval.MOBILE) { _, _ -> buildJsonObject {} }

        runtime.executeBlocking(call("call-approval"))

        val record = store.snapshot.value.calls.single()
        assertEquals(InspectorApprovalState.DENIED, record.approvalState)
        assertEquals(InspectorCallState.DENIED, record.callState)
        assertTrue(record.approvalWaitMillis != null)
    }

    @Test
    fun boundsHistoryAndClearsItAtomically() {
        val store = InspectorAuditStore(appName = "Test app", maxEntries = 2)
        val runtime = ToolRuntime()
        runtime.addAuditListener(store)
        runtime.register(spec()) { _, _ -> buildJsonObject {} }

        runtime.executeBlocking(call("call-1"))
        runtime.executeBlocking(call("call-2"))
        runtime.executeBlocking(call("call-3"))

        assertEquals(listOf("call-3", "call-2"), store.snapshot.value.calls.map { it.call.id })

        store.clear()

        assertTrue(store.snapshot.value.calls.isEmpty())
    }

    @Test
    fun marksAnInstallationAsDetachedWithoutDroppingItsVisibleHistory() {
        val store = InspectorAuditStore(appName = "Test app", maxEntries = 2)
        val runtime = ToolRuntime()
        runtime.addAuditListener(store)
        runtime.register(spec()) { _, _ -> buildJsonObject {} }
        runtime.executeBlocking(call("call-1"))

        store.markDetached()

        assertFalse(store.snapshot.value.attached)
        assertEquals(1, store.snapshot.value.calls.size)
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

    private fun call(id: String): ToolCall = ToolCall(
        id = id,
        name = "calendar_event_create",
        input = buildJsonObject { put("title", "Project review") },
    )
}
