package io.github.pumpkinredbean.appagent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ToolApprovalControllerTest {
    @Test
    fun `requests are presented FIFO and queued time does not consume decision time`() = runTest {
        val controller = testController(timeoutMillis = 1_000L)
        val first = backgroundScope.async { controller.requestApproval(request("call-1")) }
        val second = backgroundScope.async { controller.requestApproval(request("call-2")) }
        runCurrent()

        assertEquals("call-1", controller.state.value.current?.call?.id)
        assertEquals(ToolApprovalPresentationStatus.WAITING_FOR_UI, controller.state.value.status)
        assertEquals(1, controller.state.value.queuedCount)
        assertNull(controller.state.value.remainingDecisionMillis)

        advanceTimeBy(5_000L)
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        controller.markPresented("call-1")
        runCurrent()
        assertEquals(ToolApprovalPresentationStatus.PRESENTED, controller.state.value.status)
        assertEquals(1_000L, controller.state.value.remainingDecisionMillis)

        controller.approve("call-1")
        runCurrent()
        assertEquals(ToolApprovalDecision.APPROVED, first.await())
        assertEquals("call-2", controller.state.value.current?.call?.id)
        assertEquals(ToolApprovalPresentationStatus.WAITING_FOR_UI, controller.state.value.status)
        assertNull(controller.state.value.remainingDecisionMillis)

        controller.markPresented("call-2")
        controller.deny("call-2")
        runCurrent()
        assertEquals(ToolApprovalDecision.DENIED, second.await())
        assertEquals(0, controller.state.value.totalCount)
        controller.close()
    }

    @Test
    fun `only the presented request times out and a stale approval cannot resolve the next request`() = runTest {
        val controller = testController(timeoutMillis = 1_000L)
        val first = backgroundScope.async { controller.requestApproval(request("call-1")) }
        val second = backgroundScope.async { controller.requestApproval(request("call-2")) }
        runCurrent()

        controller.markPresented("call-1")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(ToolApprovalDecision.TIMED_OUT, first.await())
        assertEquals("call-2", controller.state.value.current?.call?.id)
        assertFalse(second.isCompleted)

        controller.approve("call-1")
        runCurrent()
        assertFalse(second.isCompleted)

        controller.markPresented("call-2")
        runCurrent()
        controller.approve("call-2")
        runCurrent()
        assertEquals(ToolApprovalDecision.APPROVED, second.await())
        controller.close()
    }

    @Test
    fun `queue is bounded duplicate call ids fail closed and caller cancellation removes its request`() = runTest {
        val controller = testController(timeoutMillis = 1_000L, maxPendingRequests = 2)
        val first = backgroundScope.async { controller.requestApproval(request("call-1")) }
        val second = backgroundScope.async { controller.requestApproval(request("call-2")) }
        runCurrent()

        val overflow = async { controller.requestApproval(request("call-3")) }
        val duplicate = async { controller.requestApproval(request("call-1")) }
        runCurrent()
        assertEquals(ToolApprovalDecision.UNAVAILABLE, overflow.await())
        assertEquals(ToolApprovalDecision.UNAVAILABLE, duplicate.await())

        second.cancel()
        runCurrent()
        assertEquals(0, controller.state.value.queuedCount)

        controller.markPresented("call-1")
        controller.approve("call-1")
        runCurrent()
        assertEquals(ToolApprovalDecision.APPROVED, first.await())
        controller.close()
    }

    @Test
    fun `host visibility is aggregated and closing the controller fails pending requests closed`() = runTest {
        val controller = testController(timeoutMillis = 1_000L)
        val decision = backgroundScope.async { controller.requestApproval(request("call-1")) }
        val firstHost = controller.registerHost()
        val secondHost = controller.registerHost()
        runCurrent()

        assertTrue(controller.state.value.requiresUserAttention)
        firstHost.setVisible(true)
        secondHost.setVisible(true)
        runCurrent()
        assertTrue(controller.state.value.hostVisible)
        assertFalse(controller.state.value.requiresUserAttention)

        firstHost.close()
        runCurrent()
        assertTrue(controller.state.value.hostVisible)
        secondHost.close()
        runCurrent()
        assertFalse(controller.state.value.hostVisible)

        controller.close()
        runCurrent()
        assertEquals(ToolApprovalDecision.UNAVAILABLE, decision.await())
        assertEquals(
            ToolApprovalDecision.UNAVAILABLE,
            controller.requestApproval(request("call-after-close")),
        )
        assertEquals(ToolApprovalQueueState(), controller.state.value)
    }

    private fun kotlinx.coroutines.test.TestScope.testController(
        timeoutMillis: Long,
        maxPendingRequests: Int = 16,
    ): ToolApprovalController = ToolApprovalController(
        configuration = ToolApprovalConfiguration(
            decisionTimeoutMillis = timeoutMillis,
            maxPendingRequests = maxPendingRequests,
        ),
        scope = backgroundScope,
        nowMillis = { testScheduler.currentTime },
        tickIntervalMillis = 100L,
    )

    private fun request(callId: String): ToolApprovalRequest = ToolApprovalRequest(
        call = ToolCall(
            id = callId,
            name = "weather_alert_create",
            input = buildJsonObject { put("location", "Seoul") },
        ),
        spec = ToolSpec(
            name = "weather_alert_create",
            description = "Creates one weather alert stored by this app.",
            inputSchema = buildJsonObject { put("type", "object") },
        ),
    )
}
