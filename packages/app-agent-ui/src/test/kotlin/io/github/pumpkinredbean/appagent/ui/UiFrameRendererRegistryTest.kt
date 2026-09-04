package io.github.pumpkinredbean.appagent.ui

import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UiFrameRendererRegistryTest {
    @Test
    fun `registry resolves by application-defined frame tag`() {
        val registry = UiFrameRendererRegistry<String>()
        val registration = registry.register("market.asset", "asset-renderer")
        val frame = UiFrame.create("market.asset", buildJsonObject {})

        assertEquals("asset-renderer", registry.require(frame))
        assertEquals(setOf("market.asset"), registry.tags)

        registration.close()
        assertNull(registry.find(frame))
    }

    @Test
    fun `registry rejects duplicate tags and missing renderers`() {
        val registry = UiFrameRendererRegistry<String>()
        val frame = UiFrame.create("portfolio.positions", buildJsonObject {})
        registry.register(frame.tag, "first")

        assertFailsWith<IllegalStateException> { registry.register(frame.tag, "second") }
        assertFailsWith<UiFrameRendererNotFoundException> {
            registry.require(UiFrame.create("orders.activity", buildJsonObject {}))
        }
    }
}
