import assert from "node:assert/strict";
import test from "node:test";

import { createAgentWebServer } from "../src/web-server.mjs";

const manifest = {
  protocol: "app-agent/1",
  type: "tools.list",
  tools: [{ name: "weather_get_current" }],
};

test("web UI status exposes the desktop-to-Android connection", async (t) => {
  const server = await startServer({
    client: {
      health: async () => ({ ready: true, protocol: "app-agent/1", runtime: "android" }),
      capabilities: async () => manifest,
      uiStatus: async () => ({ enabled: true, ready: true, runtime: "androidx-uiautomator2", toolCount: 9 }),
    },
  });
  t.after(() => closeServer(server));

  const response = await fetch(`${origin(server)}/api/status`);
  const status = await response.json();

  assert.equal(response.status, 200);
  assert.equal(status.agent.ready, true);
  assert.equal(status.app.runtime, "android");
  assert.equal(status.app.toolCount, 1);
  assert.equal(status.uiDriver.runtime, "androidx-uiautomator2");
  assert.equal(status.uiDriver.toolCount, 9);
});

test("web client renders actual ToolCall events instead of a fixed four-stage pipeline", async (t) => {
  const server = await startServer({
    client: {
      health: async () => ({ ready: true, protocol: "app-agent/1", runtime: "android" }),
      capabilities: async () => manifest,
    },
  });
  t.after(() => closeServer(server));

  const source = await (await fetch(`${origin(server)}/app.js`)).text();

  assert.doesNotMatch(source, /App connection|Tool selection|Mobile execution|Response generation/);
  assert.match(source, /appendToolCard/);
  assert.match(source, /Direct response · no ToolCall/);
});

test("web chat streams actual agent events and carries tool-aware conversation history", async (t) => {
  const calls = [];
  const client = {
    health: async () => ({ ready: true }),
    capabilities: async () => manifest,
  };
  const runAgentFn = async (message, options) => {
    calls.push({ message, history: options.history, model: options.model });
    const requestId = `request-${calls.length}`;
    const exchange = {
      call: { name: "weather_get_current", id: requestId, input: { location: "Seoul" } },
      result: { ok: true, output: { weather: {} } },
    };
    options.onEvent({ type: "model.started", at: 1, provider: "fixture", step: 1, toolCallCount: 0 });
    options.onEvent({ type: "tool.dispatched", at: 2, step: 1, call: exchange.call });
    options.onEvent({ type: "tool.completed", at: 3, step: 1, call: exchange.call, result: exchange.result, durationMs: 12 });
    return {
      provider: "fixture",
      modelStepCount: 2,
      toolCallCount: 1,
      toolExchanges: [exchange],
      answer: `Response: ${message}`,
    };
  };
  const server = await startServer({ client, runAgent: runAgentFn });
  t.after(() => closeServer(server));
  const sessionId = "session-web-test";

  const first = await postChat(server, { message: "Weather in Seoul", sessionId, provider: "codex", model: "test-model" });
  const second = await postChat(server, { message: "What about tomorrow?", sessionId, provider: "codex" });

  assert.equal(first.at(-1).type, "chat.completed");
  assert.equal(first.at(-1).answer, "Response: Weather in Seoul");
  assert.equal(first.at(-1).run.toolCallCount, 1);
  assert.equal(first.find((event) => event.event === "tool.dispatched").requestId, "request-");
  assert.deepEqual(calls[0].history, []);
  assert.deepEqual(calls[1].history, [
    { role: "user", content: "Weather in Seoul" },
    {
      role: "assistant",
      content: "Response: Weather in Seoul",
      toolExchanges: [{
        call: { name: "weather_get_current", id: "request-1", input: { location: "Seoul" } },
        result: { ok: true, output: { weather: {} } },
      }],
    },
  ]);
  assert.equal(second.at(-1).answer, "Response: What about tomorrow?");
  assert.equal(calls[0].model, "test-model");
});

async function startServer(options) {
  const server = createAgentWebServer(options);
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  return server;
}

function closeServer(server) {
  return new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

function origin(server) {
  return `http://127.0.0.1:${server.address().port}`;
}

async function postChat(server, body) {
  const response = await fetch(`${origin(server)}/api/chat`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  assert.equal(response.status, 200);
  return (await response.text()).trim().split("\n").map((line) => JSON.parse(line));
}
