import assert from "node:assert/strict";
import test from "node:test";

import {
  APP_AGENT_PROTOCOL,
  ToolCallError,
  type JsonObject,
  type SuccessfulToolExchange,
  type ToolExchange,
  type ToolsList,
} from "@pumpkinredbean/app-agent-client";

import type {
  AgentAction,
  AgentEvent,
  AgentProvider,
  AgentProviderContext,
  AgentToolClient,
} from "../../src/agent/contracts.js";
import { authorizeToolCall } from "../../src/agent/policy.js";
import { runAgent } from "../../src/agent/run-agent.js";
import { sanitizeToolExchange } from "../../src/agent/tool-result-content.js";
import { ClaudeAgentSdkProvider, type ClaudeQueryFunction } from "../../src/providers/claude-agent.js";
import {
  VercelAiAgentProvider,
  parseModelJson,
  type ModelPrompt,
} from "../../src/providers/vercel-ai.js";

const manifest = {
  protocol: APP_AGENT_PROTOCOL,
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
} satisfies ToolsList;

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
  let capturedPrompt: ModelPrompt | undefined;
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
  const parts = capturedPrompt[0]!.content;
  const image = parts.find((part) => part.type === "image");
  assert.ok(image?.type === "image");
  assert.equal(image.mediaType, "image/png");
  assert.equal(Buffer.compare(image.image, Buffer.from("fake-png-binary")), 0);
  const text = parts
    .filter((part): part is Extract<typeof part, { type: "text" }> => part.type === "text")
    .map((part) => part.text)
    .join("\n");
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
  const calls: Array<Parameters<ClaudeQueryFunction>[0]["options"]> = [];
  const queryFn: ClaudeQueryFunction = ({ options }) => {
    calls.push(options);
    return (async function* messages() {
      yield {
        type: "result" as const,
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
  assert.deepEqual(calls[0]!.tools, []);
  assert.equal(calls[0]!.outputFormat?.type, "json_schema");
});

test("Claude Agent SDK adapter sends screenshots as image blocks", async () => {
  type PromptMessage = {
    message: {
      content: Array<
        | { type: "text"; text: string }
        | { type: "image"; source: { type: "base64"; media_type: string; data: string } }
      >;
    };
  };
  let promptMessage: PromptMessage | undefined;
  const queryFn: ClaudeQueryFunction = ({ prompt }) => (async function* messages() {
    if (typeof prompt !== "string") {
      for await (const message of prompt) promptMessage = message;
    }
    yield {
      type: "result" as const,
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

  assert.ok(promptMessage !== undefined);
  const parts = promptMessage.message.content;
  const image = parts.find((part) => part.type === "image");
  assert.ok(image?.type === "image");
  assert.deepEqual(image.source, { type: "base64", media_type: "image/png", data: base64 });
  const text = parts
    .filter((part): part is Extract<typeof part, { type: "text" }> => part.type === "text")
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
  const events: AgentEvent[] = [];
  let mobileCalls = 0;
  const provider: AgentProvider = {
    kind: "fixture",
    next: async () => ({ type: "final", text: "I can use weather_get_current for live conditions." }),
  };
  const client: AgentToolClient = {
    capabilities: async () => manifest,
    call: async () => {
      mobileCalls += 1;
      throw new Error("should not execute");
    },
  };

  const trace = await runAgent("Which tools can you use?", { provider, client, onEvent: (event) => events.push(event) });

  assert.equal(trace.toolCallCount, 0);
  assert.equal(mobileCalls, 0);
  assert.deepEqual(events.map((event) => event.type), ["agent.started", "model.started", "agent.completed"]);
});

test("agent executes multiple validated own-protocol ToolCalls and then asks the model again", async () => {
  const events: AgentEvent[] = [];
  const providerInputs: AgentProviderContext[] = [];
  const actions: AgentAction[] = [
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
  const provider = sequenceProvider(actions, providerInputs);
  let requestNumber = 0;
  const client: AgentToolClient = {
    capabilities: async () => manifest,
    call: async (tool, args, options) => {
      requestNumber += 1;
      const call = {
        protocol: APP_AGENT_PROTOCOL,
        type: "tool.call" as const,
        id: `request-${requestNumber}`,
        name: tool,
        input: args,
      };
      options?.onDispatch?.(call);
      const location = typeof args.location === "string" ? args.location : "unknown";
      return success(call, { location, temperatureCelsius: location === "Seoul" ? 24 : 22 });
    },
  };

  const trace = await runAgent("Compare Seoul and Tokyo", { provider, client, onEvent: (event) => events.push(event) });

  assert.equal(trace.modelStepCount, 2);
  assert.equal(trace.toolCallCount, 2);
  assert.equal(providerInputs[1]!.toolExchanges.length, 2);
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
  const events: AgentEvent[] = [];
  const providerInputs: AgentProviderContext[] = [];
  const provider = sequenceProvider([
    { type: "tool_calls", text: "", toolCalls: [{ name: "weather_get_current", arguments: { location: "Seoul" } }] },
    { type: "final", text: "The app rejected that request." },
  ], providerInputs);
  const client: AgentToolClient = {
    capabilities: async () => manifest,
    call: async (tool, args, options) => {
      const call = {
        protocol: APP_AGENT_PROTOCOL,
        type: "tool.call" as const,
        id: "request-denied",
        name: tool,
        input: args,
      };
      options?.onDispatch?.(call);
      throw new ToolCallError({
        protocol: APP_AGENT_PROTOCOL,
        type: "tool.result",
        id: call.id,
        ok: false,
        error: { code: "APPROVAL_DENIED", message: "The user denied the request", retryable: false },
      });
    },
  };

  const trace = await runAgent("Check Seoul", { provider, client, onEvent: (event) => events.push(event) });

  assert.equal(trace.answer, "The app rejected that request.");
  const failure = providerInputs[1]!.toolExchanges[0]!.result;
  assert.equal(failure.ok, false);
  if (failure.ok) throw new Error("Fixture ToolResult must fail");
  assert.equal(failure.error.code, "APPROVAL_DENIED");
  assert.ok(events.some((event) => event.type === "tool.failed"));
  assert.ok(events.some((event) => event.type === "agent.completed"));
});

test("agent keeps screenshot bytes for the next model step but removes them from persisted traces", async () => {
  const imageManifest = {
    protocol: APP_AGENT_PROTOCOL,
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
  } satisfies ToolsList;
  const base64 = Buffer.from("private-screen-bytes").toString("base64");
  const providerInputs: AgentProviderContext[] = [];
  const provider = sequenceProvider([
    { type: "tool_calls", text: "", toolCalls: [{ name: "mobile_screenshot", arguments: { snapshotId: "ui-1" } }] },
    { type: "final", text: "Done." },
  ], providerInputs);
  const client: AgentToolClient = {
    capabilities: async () => imageManifest,
    call: async (name, input, options) => {
      const call = {
        protocol: APP_AGENT_PROTOCOL,
        type: "tool.call" as const,
        id: "screen-1",
        name,
        input,
      };
      options?.onDispatch?.(call);
      return success(call, {
        snapshotId: "ui-1",
        image: { mediaType: "image/png", data: base64 },
      });
    },
  };

  const trace = await runAgent("Inspect it", { provider, client });

  const modelResult = providerInputs[1]!.toolExchanges[0]!.result;
  assert.equal(modelResult.ok, true);
  if (!modelResult.ok || typeof modelResult.output !== "object" || modelResult.output === null || Array.isArray(modelResult.output)) {
    throw new Error("Fixture screenshot result must be an object");
  }
  const image = modelResult.output.image as JsonObject;
  assert.equal(image.data, base64);
  const persisted = trace.toolExchanges[0]!.result;
  assert.equal(persisted.ok, true);
  if (!persisted.ok || typeof persisted.output !== "object" || persisted.output === null || Array.isArray(persisted.output)) {
    throw new Error("Persisted screenshot result must be an object");
  }
  assert.deepEqual(persisted.output.image, { mediaType: "image/png", attachedToModel: true });
  assert.doesNotMatch(JSON.stringify(trace), new RegExp(base64));
});

test("persisted UI exchanges redact entered text and ordered keypad coordinates", () => {
  const inputText = sanitizeToolExchange(successExchange(
    "mobile_input_text",
    { snapshotId: "ui-1", nodeId: "c2", text: "secret-value" },
    { performed: true },
  ));
  const multiTap = sanitizeToolExchange(successExchange(
    "mobile_multi_tap",
    { snapshotId: "ui-1", positions: [{ x: 10, y: 20 }, { x: 30, y: 40 }], intervalMs: 80 },
    { performed: true },
  ));

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

function context(userMessage: string): AgentProviderContext {
  return {
    userMessage,
    manifest,
    conversationHistory: [],
    toolExchanges: [],
    assistantMessages: [],
    step: 1,
  };
}

function fixtureVercelProvider(object: {
  text: string;
  toolCalls: Array<{ name: string; argumentsJson: string }>;
}): VercelAiAgentProvider {
  return new VercelAiAgentProvider({
    providerName: "fixture",
    modelId: "fixture-model",
    model: {},
    generateObjectFn: async ({ prompt }) => {
      assert.equal(typeof prompt, "string");
      assert.match(prompt as string, /weather_get_current/);
      return { object };
    },
  });
}

function screenshotExchange(data: string): ToolExchange {
  return successExchange("mobile_screenshot", { snapshotId: "ui-1" }, {
    snapshotId: "ui-1",
    screen: { width: 1080, height: 2400, rotation: 0 },
    image: { mediaType: "image/png", data },
  });
}

function sequenceProvider(actions: AgentAction[], inputs: AgentProviderContext[]): AgentProvider {
  return {
    kind: "fixture",
    next: async (input) => {
      inputs.push(input);
      const action = actions.shift();
      if (action === undefined) throw new Error("Fixture provider ran out of actions");
      return action;
    },
  };
}

function success(
  call: SuccessfulToolExchange["call"],
  output: JsonObject,
): SuccessfulToolExchange {
  return {
    call,
    result: {
      protocol: APP_AGENT_PROTOCOL,
      type: "tool.result",
      id: call.id,
      ok: true,
      output,
    },
  };
}

function successExchange(name: string, input: JsonObject, output: JsonObject): ToolExchange {
  const call = {
    protocol: APP_AGENT_PROTOCOL,
    type: "tool.call" as const,
    id: `${name}-call`,
    name,
    input,
  };
  return success(call, output);
}
