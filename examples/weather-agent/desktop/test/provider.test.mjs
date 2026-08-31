import assert from "node:assert/strict";
import test from "node:test";

import { runAgent } from "../src/agent.mjs";
import { authorizeToolCall } from "../src/policy.mjs";
import { ClaudeAgentSdkProvider } from "../src/providers/claude-agent.mjs";
import { VercelAiAgentProvider, parseModelJson } from "../src/providers/vercel-ai.mjs";
import { ToolCallError } from "../src/tool-client.mjs";
import { sanitizeToolExchange } from "../src/tool-result-content.mjs";

const manifest = {
  protocol: "app-agent/1",
  type: "tools.list",
  tools: [
    {
      name: "weather_get_current",
      description: "Returns current weather. Use it when the user asks about conditions now.",
      inputSchema: {
        type: "object",
        additionalProperties: false,
        required: ["location"],
        properties: { location: { type: "string" } },
      },
    },
  ],
};

test("Vercel AI provider can answer directly without selecting a tool", async () => {
  const provider = fixtureVercelProvider({
    text: "I can read current weather with weather_get_current.",
    toolCalls: [],
  });

  assert.deepEqual(await provider.next(context("Which tools can you use?")), {
    type: "final",
    text: "I can read current weather with weather_get_current.",
  });
});

test("Vercel AI provider normalizes one or more ToolCalls", async () => {
  const provider = fixtureVercelProvider({
    text: "I’ll check both locations.",
    toolCalls: [
      { name: "weather_get_current", argumentsJson: "{\"location\":\"Seoul\"}" },
      { name: "weather_get_current", argumentsJson: "{\"location\":\"Tokyo\"}" },
    ],
  });

  assert.deepEqual(await provider.next(context("Compare Seoul and Tokyo")), {
    type: "tool_calls",
    text: "I’ll check both locations.",
    toolCalls: [
      { name: "weather_get_current", arguments: { location: "Seoul" } },
      { name: "weather_get_current", arguments: { location: "Tokyo" } },
    ],
  });
});

test("Vercel AI provider attaches screenshots as image parts without embedding base64 in JSON", async () => {
  let capturedPrompt;
  const provider = new VercelAiAgentProvider({
    providerName: "fixture",
    modelId: "fixture-model",
    model: {},
    generateObjectFn: async ({ prompt }) => {
      capturedPrompt = prompt;
      return { object: { text: "I can see the custom control.", toolCalls: [] } };
    },
  });
  const base64 = Buffer.from("fake-png-binary").toString("base64");
  const input = context("What is on the screen?");
  input.toolExchanges = [screenshotExchange(base64)];

  await provider.next(input);

  assert.ok(Array.isArray(capturedPrompt));
  const content = capturedPrompt[0].content;
  assert.equal(content.find((part) => part.type === "image").mediaType, "image/png");
  assert.equal(Buffer.compare(content.find((part) => part.type === "image").image, Buffer.from("fake-png-binary")), 0);
  const text = content.filter((part) => part.type === "text").map((part) => part.text).join("\n");
  assert.doesNotMatch(text, new RegExp(base64));
  assert.match(text, /"attachedToModel":true/);
});

test("prompt JSON mode parses fenced Copilot output", () => {
  assert.deepEqual(parseModelJson("```json\n{\"text\":\"hello\",\"toolCalls\":[]}\n```"), {
    text: "hello",
    toolCalls: [],
  });
});

test("harness rejects arguments that violate the app tool schema", () => {
  assert.throws(
    () => authorizeToolCall({ name: "weather_get_current", arguments: {} }, manifest),
    /must have required property 'location'/,
  );
});

test("Claude Agent SDK adapter returns an agent step without enabling SDK tools", async () => {
  const calls = [];
  const queryFn = ({ options }) => {
    calls.push(options);
    return (async function* messages() {
      yield {
        type: "result",
        subtype: "success",
        is_error: false,
        result: "",
        structured_output: {
          text: "",
          toolCalls: [{ name: "weather_get_current", argumentsJson: "{\"location\":\"Seoul\"}" }],
        },
      };
    }());
  };
  const provider = new ClaudeAgentSdkProvider({ queryFn });
  const step = await provider.next(context("Weather in Seoul"));
  assert.equal(step.type, "tool_calls");
  assert.deepEqual(calls[0].tools, []);
  assert.equal(calls[0].outputFormat.type, "json_schema");
});

test("Claude Agent SDK adapter sends screenshots as image blocks", async () => {
  let promptMessage;
  const queryFn = ({ prompt }) => (async function* messages() {
    for await (const message of prompt) promptMessage = message;
    yield {
      type: "result",
      subtype: "success",
      is_error: false,
      result: "",
      structured_output: { text: "The image is available.", toolCalls: [] },
    };
  }());
  const base64 = Buffer.from("fake-png-binary").toString("base64");
  const input = context("Inspect the screen");
  input.toolExchanges = [screenshotExchange(base64)];

  await new ClaudeAgentSdkProvider({ queryFn }).next(input);

  const image = promptMessage.message.content.find((part) => part.type === "image");
  assert.deepEqual(image.source, { type: "base64", media_type: "image/png", data: base64 });
  const text = promptMessage.message.content
    .filter((part) => part.type === "text")
    .map((part) => part.text)
    .join("\n");
  assert.doesNotMatch(text, new RegExp(base64));
});

test("Claude Agent SDK adapter requires API credentials for a real call", async () => {
  const previous = process.env.ANTHROPIC_API_KEY;
  delete process.env.ANTHROPIC_API_KEY;
  try {
    const provider = new ClaudeAgentSdkProvider();
    await assert.rejects(() => provider.next(context("Weather in Seoul")), /requires ANTHROPIC_API_KEY/);
  } finally {
    if (previous === undefined) delete process.env.ANTHROPIC_API_KEY;
    else process.env.ANTHROPIC_API_KEY = previous;
  }
});

test("agent completes a conversational request with zero ToolCalls", async () => {
  const events = [];
  let mobileCalls = 0;
  const provider = {
    kind: "fixture",
    next: async () => ({ type: "final", text: "I can use weather_get_current for live conditions." }),
  };
  const client = {
    capabilities: async () => manifest,
    call: async () => {
      mobileCalls += 1;
      throw new Error("should not execute");
    },
  };

  const trace = await runAgent("Which tools can you use?", { provider, client, onEvent: (event) => events.push(event) });

  assert.equal(trace.toolCallCount, 0);
  assert.equal(mobileCalls, 0);
  assert.deepEqual(events.map((event) => event.type), [
    "agent.started",
    "model.started",
    "agent.completed",
  ]);
});

test("agent executes multiple validated own-protocol ToolCalls and then asks the model again", async () => {
  const events = [];
  const providerInputs = [];
  const actions = [
    {
      type: "tool_calls",
      text: "I’ll check both locations.",
      toolCalls: [
        { name: "weather_get_current", arguments: { location: "Seoul" } },
        { name: "weather_get_current", arguments: { location: "Tokyo" } },
      ],
    },
    { type: "final", text: "Seoul is warmer than Tokyo." },
  ];
  const provider = {
    kind: "fixture",
    next: async (input) => {
      providerInputs.push(input);
      return actions.shift();
    },
  };
  let requestNumber = 0;
  const client = {
    capabilities: async () => manifest,
    call: async (tool, args, options) => {
      requestNumber += 1;
      const call = {
        protocol: "app-agent/1",
        type: "tool.call",
        id: `request-${requestNumber}`,
        name: tool,
        input: args,
      };
      options.onDispatch(call);
      return {
        call,
        result: {
          protocol: "app-agent/1",
          type: "tool.result",
          id: call.id,
          ok: true,
          output: { location: args.location, temperatureCelsius: args.location === "Seoul" ? 24 : 22 },
        },
      };
    },
  };

  const trace = await runAgent("Compare Seoul and Tokyo", { provider, client, onEvent: (event) => events.push(event) });

  assert.equal(trace.modelStepCount, 2);
  assert.equal(trace.toolCallCount, 2);
  assert.equal(providerInputs[1].toolExchanges.length, 2);
  assert.deepEqual(trace.toolExchanges.map((exchange) => exchange.call.input.location), ["Seoul", "Tokyo"]);
  assert.deepEqual(events.map((event) => event.type), [
    "agent.started",
    "model.started",
    "assistant.message",
    "tool.dispatched",
    "tool.completed",
    "tool.dispatched",
    "tool.completed",
    "model.started",
    "agent.completed",
  ]);
});

test("agent returns an app-level ToolCall failure to the model instead of treating it as a fixed-pipeline crash", async () => {
  const events = [];
  const providerInputs = [];
  const actions = [
    {
      type: "tool_calls",
      text: "",
      toolCalls: [{ name: "weather_get_current", arguments: { location: "Seoul" } }],
    },
    { type: "final", text: "The app rejected that request." },
  ];
  const provider = {
    kind: "fixture",
    next: async (input) => {
      providerInputs.push(input);
      return actions.shift();
    },
  };
  const client = {
    capabilities: async () => manifest,
    call: async (tool, args, options) => {
      const call = { protocol: "app-agent/1", type: "tool.call", id: "request-denied", name: tool, input: args };
      options.onDispatch(call);
      throw new ToolCallError({
        protocol: "app-agent/1",
        type: "tool.result",
        id: call.id,
        ok: false,
        error: { code: "APPROVAL_DENIED", message: "The user denied the request", retryable: false },
      });
    },
  };

  const trace = await runAgent("Check Seoul", { provider, client, onEvent: (event) => events.push(event) });

  assert.equal(trace.answer, "The app rejected that request.");
  assert.equal(providerInputs[1].toolExchanges[0].result.error.code, "APPROVAL_DENIED");
  assert.ok(events.some((event) => event.type === "tool.failed"));
  assert.ok(events.some((event) => event.type === "agent.completed"));
});

test("agent keeps screenshot bytes for the next model step but removes them from persisted traces", async () => {
  const imageManifest = {
    protocol: "app-agent/1",
    type: "tools.list",
    tools: [{
      name: "mobile_screenshot",
      description: "Captures the current Android display.",
      inputSchema: {
        type: "object",
        properties: { snapshotId: { type: "string" } },
        required: ["snapshotId"],
        additionalProperties: false,
      },
    }],
  };
  const base64 = Buffer.from("private-screen-bytes").toString("base64");
  const providerInputs = [];
  const actions = [
    { type: "tool_calls", text: "", toolCalls: [{ name: "mobile_screenshot", arguments: { snapshotId: "ui-1" } }] },
    { type: "final", text: "Done." },
  ];
  const provider = {
    kind: "fixture",
    next: async (input) => {
      providerInputs.push(input);
      return actions.shift();
    },
  };
  const client = {
    capabilities: async () => imageManifest,
    call: async (name, input, options) => {
      const call = { protocol: "app-agent/1", type: "tool.call", id: "screen-1", name, input };
      options.onDispatch(call);
      return {
        call,
        result: {
          protocol: "app-agent/1",
          type: "tool.result",
          id: call.id,
          ok: true,
          output: { snapshotId: "ui-1", image: { mediaType: "image/png", data: base64 } },
        },
      };
    },
  };

  const trace = await runAgent("Inspect it", { provider, client });

  assert.equal(providerInputs[1].toolExchanges[0].result.output.image.data, base64);
  assert.deepEqual(trace.toolExchanges[0].result.output.image, {
    mediaType: "image/png",
    attachedToModel: true,
  });
  assert.doesNotMatch(JSON.stringify(trace), new RegExp(base64));
});

test("persisted UI exchanges redact entered text and ordered keypad coordinates", () => {
  const inputText = sanitizeToolExchange({
    call: { name: "mobile_input_text", input: { snapshotId: "ui-1", nodeId: "c2", text: "secret-value" } },
    result: { ok: true, output: { performed: true } },
  });
  const multiTap = sanitizeToolExchange({
    call: {
      name: "mobile_multi_tap",
      input: { snapshotId: "ui-1", positions: [{ x: 10, y: 20 }, { x: 30, y: 40 }], intervalMs: 80 },
    },
    result: { ok: true, output: { performed: true } },
  });

  assert.deepEqual(inputText.call.input, {
    snapshotId: "ui-1",
    nodeId: "c2",
    text: "[REDACTED]",
    characterCount: 12,
  });
  assert.deepEqual(multiTap.call.input, {
    snapshotId: "ui-1",
    intervalMs: 80,
    positionCount: 2,
  });
});

function context(userMessage) {
  return {
    userMessage,
    manifest,
    conversationHistory: [],
    toolExchanges: [],
    assistantMessages: [],
    step: 1,
  };
}

function fixtureVercelProvider(object) {
  return new VercelAiAgentProvider({
    providerName: "fixture",
    modelId: "fixture-model",
    model: {},
    generateObjectFn: async ({ prompt }) => {
      assert.match(prompt, /weather_get_current/);
      return { object };
    },
  });
}

function screenshotExchange(data) {
  return {
    call: {
      protocol: "app-agent/1",
      type: "tool.call",
      id: "screen-call-1",
      name: "mobile_screenshot",
      input: { snapshotId: "ui-1" },
    },
    result: {
      protocol: "app-agent/1",
      type: "tool.result",
      id: "screen-call-1",
      ok: true,
      output: {
        snapshotId: "ui-1",
        screen: { width: 1080, height: 2400, rotation: 0 },
        image: { mediaType: "image/png", data },
      },
    },
  };
}
