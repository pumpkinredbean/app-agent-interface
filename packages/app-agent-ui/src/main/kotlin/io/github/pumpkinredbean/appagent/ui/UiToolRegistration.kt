package io.github.pumpkinredbean.appagent.ui

import io.github.pumpkinredbean.appagent.BlockingToolHandler
import io.github.pumpkinredbean.appagent.ToolApproval
import io.github.pumpkinredbean.appagent.ToolExecutionContext
import io.github.pumpkinredbean.appagent.ToolHandler
import io.github.pumpkinredbean.appagent.ToolRegistration
import io.github.pumpkinredbean.appagent.ToolRuntime
import io.github.pumpkinredbean.appagent.ToolSpec
import kotlinx.serialization.json.JsonObject

public fun interface UiToolHandler {
    public suspend fun execute(input: JsonObject, context: ToolExecutionContext): UiToolOutput
}

public fun interface BlockingUiToolHandler {
    public fun execute(input: JsonObject, context: ToolExecutionContext): UiToolOutput
}

/** Registers a handler whose output contains both model data and an app-defined UI frame. */
@JvmOverloads
public fun ToolRuntime.registerUi(
    spec: ToolSpec,
    approval: ToolApproval = ToolApproval.NONE,
    handler: UiToolHandler,
): ToolRegistration = register(
    spec = spec,
    approval = approval,
    handler = ToolHandler { input, context ->
        UiToolOutputCodec.encode(handler.execute(input, context))
    },
)

/** Java-friendly registration path for blocking UI handlers. */
@JvmOverloads
public fun ToolRuntime.registerUiBlocking(
    spec: ToolSpec,
    approval: ToolApproval = ToolApproval.NONE,
    handler: BlockingUiToolHandler,
): ToolRegistration = registerBlocking(
    spec = spec,
    approval = approval,
    handler = BlockingToolHandler { input, context ->
        UiToolOutputCodec.encode(handler.execute(input, context))
    },
)
