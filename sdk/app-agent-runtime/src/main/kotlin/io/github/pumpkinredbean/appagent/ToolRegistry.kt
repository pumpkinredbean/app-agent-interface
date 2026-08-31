package io.github.pumpkinredbean.appagent

public class ToolRegistry {
    private val tools: LinkedHashMap<String, RegisteredTool> = linkedMapOf()

    @Synchronized
    public fun register(
        spec: ToolSpec,
        approval: ToolApproval = ToolApproval.NONE,
        handler: ToolHandler,
    ): ToolRegistration {
        require(spec.name !in tools) { "Tool is already registered: ${spec.name}" }
        val registered = RegisteredTool(spec, approval, handler)
        tools[spec.name] = registered
        return ToolRegistration(this, registered)
    }

    @JvmOverloads
    public fun registerBlocking(
        spec: ToolSpec,
        approval: ToolApproval = ToolApproval.NONE,
        handler: BlockingToolHandler,
    ): ToolRegistration = register(spec, approval) { input, context ->
        handler.execute(input, context)
    }

    @Synchronized
    public fun specs(): List<ToolSpec> = tools.values.map { it.spec }

    @Synchronized
    public fun contains(name: String): Boolean = name in tools

    @Synchronized
    internal fun find(name: String): RegisteredTool? = tools[name]

    @Synchronized
    internal fun unregister(registered: RegisteredTool) {
        if (tools[registered.spec.name] === registered) {
            tools.remove(registered.spec.name)
        }
    }

    internal data class RegisteredTool(
        val spec: ToolSpec,
        val approval: ToolApproval,
        val handler: ToolHandler,
    )
}

public class ToolRegistration internal constructor(
    private val registry: ToolRegistry,
    private val registered: ToolRegistry.RegisteredTool,
) : AutoCloseable {
    @Volatile
    private var closed: Boolean = false

    override fun close() {
        if (!closed) {
            synchronized(this) {
                if (!closed) {
                    registry.unregister(registered)
                    closed = true
                }
            }
        }
    }
}
