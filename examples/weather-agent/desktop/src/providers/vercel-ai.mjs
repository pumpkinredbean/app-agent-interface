import { generateObject, generateText } from "ai";
import { z } from "zod";

import { collectToolResultImages, sanitizeToolInput, sanitizeToolOutput } from "../tool-result-content.mjs";

const AGENT_SYSTEM_PROMPT = [
  "You are a conversational agent with semantic application tools and optional harness-owned mobile UI tools for one connected Android app.",
  "For each step, either answer the user or request one or more available tools. Tool calls are optional.",
  "Answer directly when the request can be handled from the conversation or Capability Manifest, including questions about available tools.",
  "Request tools only when current application data or an application action is needed.",
  "You may request multiple independent tools in one step. Calls are executed in the listed order; never batch calls that depend on an earlier result.",
  "After ToolResults are supplied, decide again whether to answer or request another tool.",
  "Prefer semantic application tools when they can complete the request. Use mobile UI tools only for an interaction that is not exposed semantically.",
  "Before a mobile UI action, use mobile_observe. It returns flat readable content and controls without an image. Use nodeId controls whenever possible.",
  "Use mobile_screenshot only when the semantic snapshot cannot identify a custom-rendered or otherwise missing visual target.",
  "mobile_multi_tap executes multiple different coordinates once each in the supplied order; it is not double-tap.",
  "When requesting tools, text may contain a short useful preamble but must not claim that execution succeeded.",
  "Use only tools from the Capability Manifest and arguments allowed by their inputSchema.",
  "Answer concisely in the user's language. Do not invent facts absent from the conversation, manifest, or ToolResults.",
  "Preserve dates and measurement units exactly. Do not repeat ToolResult attribution in response text; the harness renders it separately.",
].join("\n");

const MAX_CALLS_PER_STEP = 4;

export class VercelAiAgentProvider {
  constructor({
    providerName,
    modelId,
    model,
    stepMode = "native-object",
    generateObjectFn = generateObject,
    generateTextFn = generateText,
    close,
  }) {
    this.providerName = providerName;
    this.modelId = modelId;
    this.model = model;
    this.stepMode = stepMode;
    this.generateObjectFn = generateObjectFn;
    this.generateTextFn = generateTextFn;
    this.closeFn = close;
    this.kind = `vercel-ai/${providerName}:${modelId}`;
  }

  async next(context) {
    const schema = createAgentStepSchema(context.manifest);
    const prompt = buildAgentStepPrompt(context);
    const modelPrompt = promptWithImages(prompt, collectToolResultImages(context.toolExchanges));

    if (this.stepMode === "native-object") {
      const { object } = await this.generateObjectFn({
        model: this.model,
        schema,
        schemaName: "appAgentStep",
        schemaDescription: "A conversational response or zero-to-four available ToolCalls",
        system: AGENT_SYSTEM_PROMPT,
        prompt: modelPrompt,
      });
      return normalizeAgentStep(schema.parse(object));
    }

    const { text } = await this.generateTextFn({
      model: this.model,
      system: `${AGENT_SYSTEM_PROMPT}\nReturn JSON only, without prose or Markdown.`,
      prompt: promptWithImages(
        `${prompt}\n\nOutput JSON Schema:\n${JSON.stringify(agentStepJsonSchema(context.manifest))}`,
        collectToolResultImages(context.toolExchanges),
      ),
    });
    return normalizeAgentStep(schema.parse(parseModelJson(text)));
  }

  async close() {
    await this.closeFn?.();
  }
}

export function createAgentStepSchema(manifest) {
  const names = manifest.tools.map((tool) => tool.name);
  const nameSchema = names.length > 0 ? z.enum(names) : z.string();
  return z.object({
    text: z.string(),
    toolCalls: z.array(z.object({
      name: nameSchema,
      argumentsJson: z.string().min(2),
    })).max(names.length > 0 ? MAX_CALLS_PER_STEP : 0),
  });
}

export function agentStepJsonSchema(manifest) {
  return {
    type: "object",
    additionalProperties: false,
    required: ["text", "toolCalls"],
    properties: {
      text: {
        type: "string",
        description: "Final answer when toolCalls is empty, or an optional short preamble before tool execution",
      },
      toolCalls: {
        type: "array",
        maxItems: manifest.tools.length > 0 ? MAX_CALLS_PER_STEP : 0,
        items: {
          type: "object",
          additionalProperties: false,
          required: ["name", "argumentsJson"],
          properties: {
            name: { type: "string", enum: manifest.tools.map((tool) => tool.name) },
            argumentsJson: {
              type: "string",
              minLength: 2,
              description: "JSON-serialized argument object matching the requested tool inputSchema",
            },
          },
        },
      },
    },
  };
}

export function buildAgentStepPrompt({
  userMessage,
  manifest,
  conversationHistory = [],
  toolExchanges = [],
  assistantMessages = [],
  step = 1,
}) {
  const tools = manifest.tools.map(({ name, description, inputSchema }) => ({
    name,
    description,
    inputSchema,
  }));
  const clock = {
    currentDate: new Intl.DateTimeFormat("en-CA").format(new Date()),
    timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone,
  };
  return JSON.stringify({
    clock,
    conversationHistory,
    currentTurn: {
      userMessage,
      step,
      assistantMessages,
      toolExchanges: toolExchanges.map(presentToolExchange),
    },
    capabilityManifest: { protocol: manifest.protocol, tools },
  });
}

export function parseModelJson(text = "") {
  const trimmed = text.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "");
  try {
    return JSON.parse(trimmed);
  } catch (error) {
    throw new Error(`Model did not return valid agent-step JSON: ${error.message}`);
  }
}

export function normalizeAgentStep(modelStep) {
  const text = modelStep.text.trim();
  const toolCalls = modelStep.toolCalls.map((call) => ({
    name: call.name,
    arguments: parseArguments(call.name, call.argumentsJson),
  }));
  if (toolCalls.length === 0 && !text) {
    throw new Error("Provider returned neither a response nor a ToolCall");
  }
  return toolCalls.length > 0
    ? { type: "tool_calls", text, toolCalls }
    : { type: "final", text };
}

function parseArguments(tool, value) {
  const parsed = parseModelJson(value);
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`Tool arguments must encode a JSON object: ${tool}`);
  }
  return parsed;
}

function presentToolExchange(exchange) {
  return {
    call: {
      name: exchange.call.name,
      input: sanitizeToolInput(exchange.call.name, exchange.call.input),
    },
    result: exchange.result.ok
      ? { ok: true, output: sanitizeToolOutput(exchange.result.output) }
      : { ok: false, error: exchange.result.error },
  };
}

function promptWithImages(prompt, images) {
  if (images.length === 0) return prompt;
  const content = [{ type: "text", text: prompt }];
  for (const image of images) {
    content.push({
      type: "text",
      text: `Image attached from ${image.toolName} ToolCall ${image.callId}. Coordinate space is described by that ToolResult.`,
    });
    content.push({
      type: "image",
      image: Buffer.from(image.data, "base64"),
      mediaType: image.mediaType,
    });
  }
  return [{ role: "user", content }];
}
