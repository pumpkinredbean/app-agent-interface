package io.github.pumpkinredbean.appagent.inspector

import android.app.Application
import io.github.pumpkinredbean.appagent.ToolRuntime

public data class InspectorConfiguration @JvmOverloads constructor(
    val openOnShake: Boolean = true,
    val maxEntries: Int = 200,
) {
    init {
        require(maxEntries in 1..10_000) { "maxEntries must be between 1 and 10000" }
    }
}

public interface InspectorHandle : AutoCloseable {
    public fun open()
    override fun close()
}

public object AppAgentInspector {
    @JvmStatic
    @JvmOverloads
    @Synchronized
    public fun install(
        application: Application,
        runtime: ToolRuntime,
        configuration: InspectorConfiguration = InspectorConfiguration(),
    ): InspectorHandle = NoOpInspectorHandle

    private object NoOpInspectorHandle : InspectorHandle {
        override fun open() = Unit
        override fun close() = Unit
    }
}
