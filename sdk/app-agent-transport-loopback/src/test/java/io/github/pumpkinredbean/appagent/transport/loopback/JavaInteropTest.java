package io.github.pumpkinredbean.appagent.transport.loopback;

import io.github.pumpkinredbean.appagent.ToolApproval;
import io.github.pumpkinredbean.appagent.ToolCall;
import io.github.pumpkinredbean.appagent.ToolJson;
import io.github.pumpkinredbean.appagent.ToolResult;
import io.github.pumpkinredbean.appagent.ToolRuntime;
import io.github.pumpkinredbean.appagent.ToolSpec;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class JavaInteropTest {
    @Test
    public void javaAppsCanRegisterAndHostKotlinSdkTools() throws Exception {
        ToolRuntime runtime = new ToolRuntime();
        runtime.registerBlocking(
            new ToolSpec(
                "ping",
                "Returns pong when the user asks to verify the app connection.",
                ToolJson.parseObject("{\"type\":\"object\"}")
            ),
            ToolApproval.NONE,
            (input, context) -> ToolJson.parseElement("{\"pong\":true}")
        );

        LoopbackToolServer server = new LoopbackToolServer(
            runtime,
            0,
            "java-test",
            null,
            new LoopbackToolServerListener() {
                @Override
                public void onCallReceived(ToolCall call) {
                }

                @Override
                public void onCallCompleted(ToolCall call, ToolResult result, long durationMs) {
                }

                @Override
                public void onServerError(Throwable error) {
                }
            }
        );
        server.start();
        try {
            assertTrue(server.isRunning());
        } finally {
            server.close();
        }
    }
}
