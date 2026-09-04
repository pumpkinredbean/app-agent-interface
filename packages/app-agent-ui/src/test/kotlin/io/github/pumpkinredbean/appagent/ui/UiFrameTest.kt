package io.github.pumpkinredbean.appagent.ui

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UiFrameTest {
    @Test
    fun `frame key is stable across parameter insertion order`() {
        val first = frame(
            tag = "market.asset",
            params = buildJsonObject {
                put("symbol", "BTC")
                put("period", "1d")
            },
        )
        val second = frame(
            tag = "market.asset",
            params = buildJsonObject {
                put("period", "1d")
                put("symbol", "BTC")
            },
        )

        assertEquals(first.key, second.key)
        assertTrue(first.key.startsWith("market.asset?"))
    }

    @Test
    fun `different parameters produce different identities`() {
        val bitcoin = frame("market.asset", buildJsonObject { put("symbol", "BTC") })
        val ethereum = frame("market.asset", buildJsonObject { put("symbol", "ETH") })

        assertNotEquals(bitcoin.key, ethereum.key)
    }

    @Test
    fun `frame key uses RFC 3986 query encoding`() {
        val frame = UiFrame(
            tag = "search.results",
            params = buildJsonObject { put("query", "a b~*'") },
            payload = JsonNull,
        )

        assertEquals("search.results?query=a%20b~%2A%27", frame.key)
    }

    private fun frame(tag: String, params: kotlinx.serialization.json.JsonObject): UiFrame =
        UiFrame(tag, params, buildJsonObject { put("ready", true) })
}
