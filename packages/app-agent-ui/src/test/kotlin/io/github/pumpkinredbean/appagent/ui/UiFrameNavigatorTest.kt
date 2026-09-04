package io.github.pumpkinredbean.appagent.ui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiFrameNavigatorTest {
    @Test
    fun observerReceivesInitialAndSubsequentSnapshots() {
        val navigator = UiFrameNavigator()
        val snapshots = mutableListOf<UiFrameNavigationSnapshot>()

        val subscription = navigator.observe(snapshots::add)
        assertEquals(1, snapshots.size)
        navigator.navigate(frame("market.overview"))
        assertEquals(2, snapshots.size)
        navigator.onTurnStarted()
        assertEquals(3, snapshots.size)
        subscription.close()
        navigator.resumeCurrent()

        assertEquals(3, snapshots.size)
        assertEquals(0, snapshots[0].depth)
        assertEquals("market.overview", snapshots[1].current?.tag)
        assertEquals(true, snapshots[1].currentResumed)
        assertEquals(false, snapshots[2].currentResumed)
    }

    @Test
    fun `push moves an existing identity to the top without increasing depth`() {
        val navigator = UiFrameNavigator()
        val overview = frame("market.overview")
        val bitcoin = frame("market.asset", "symbol", "BTC")
        val returns = frame("portfolio.return", "period", "today")

        navigator.navigate(overview)
        navigator.navigate(bitcoin)
        navigator.navigate(returns)
        navigator.navigate(bitcoin.copy(payload = buildJsonObject { put("price", 72_500) }))

        assertEquals(3, navigator.depth)
        assertEquals(
            listOf(overview.key, returns.key, bitcoin.key),
            navigator.frames.map(UiFrame::key),
        )
        assertEquals(72_500, navigator.current?.payload?.jsonObject?.get("price")?.jsonPrimitive?.int)
    }

    @Test
    fun `moving an existing identity disposes its previous frame instance`() {
        val events = mutableListOf<UiFrameLifecycleEvent>()
        val navigator = UiFrameNavigator(events::add)
        val bitcoin = frame("market.asset", "symbol", "BTC")
        val returns = frame("portfolio.return", "period", "today")

        navigator.navigate(bitcoin)
        navigator.navigate(returns)
        events.clear()
        navigator.navigate(bitcoin.copy(payload = buildJsonObject { put("price", 72_500) }))

        assertEquals(
            listOf(
                returns to UiFrameLifecycleState.PAUSED,
                bitcoin to UiFrameLifecycleState.DISPOSED,
                navigator.current to UiFrameLifecycleState.RESUMED,
            ),
            events.map { it.frame to it.state },
        )
    }

    @Test
    fun `new turn pauses current frame and resume does not change the stack`() {
        val events = mutableListOf<UiFrameLifecycleEvent>()
        val navigator = UiFrameNavigator(events::add)
        val overview = frame("market.overview")

        navigator.navigate(overview)
        navigator.onTurnStarted()
        navigator.onTurnStarted()
        navigator.resumeCurrent()

        assertEquals(1, navigator.depth)
        assertEquals(
            listOf(
                UiFrameLifecycleState.RESUMED,
                UiFrameLifecycleState.PAUSED,
                UiFrameLifecycleState.RESUMED,
            ),
            events.map(UiFrameLifecycleEvent::state),
        )
    }

    @Test
    fun `back disposes top frame and resumes previous frame`() {
        val events = mutableListOf<UiFrameLifecycleEvent>()
        val navigator = UiFrameNavigator(events::add)
        val overview = frame("market.overview")
        val bitcoin = frame("market.asset", "symbol", "BTC")

        navigator.navigate(overview)
        navigator.navigate(bitcoin)
        events.clear()

        assertTrue(navigator.back())
        assertEquals(overview, navigator.current)
        assertEquals(
            listOf(
                bitcoin to UiFrameLifecycleState.PAUSED,
                bitcoin to UiFrameLifecycleState.DISPOSED,
                overview to UiFrameLifecycleState.RESUMED,
            ),
            events.map { it.frame to it.state },
        )
        assertFalse(navigator.back())
    }

    @Test
    fun `replace removes the old top and any duplicate destination`() {
        val navigator = UiFrameNavigator()
        val overview = frame("market.overview")
        val bitcoin = frame("market.asset", "symbol", "BTC")
        val returns = frame("portfolio.return", "period", "today")

        navigator.navigate(overview)
        navigator.navigate(bitcoin)
        navigator.navigate(returns)
        navigator.navigate(bitcoin, UiFrameNavigationMode.REPLACE)

        assertEquals(listOf(overview.key, bitcoin.key), navigator.frames.map(UiFrame::key))
    }

    private fun frame(tag: String): UiFrame = UiFrame.create(tag, buildJsonObject {})

    private fun frame(tag: String, name: String, value: String): UiFrame = UiFrame(
        tag = tag,
        params = buildJsonObject { put(name, value) },
        payload = buildJsonObject {},
    )
}
