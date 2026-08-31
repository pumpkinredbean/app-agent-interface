import { isJsonObject, type JsonObject, type JsonValue, type ToolsList } from "@pumpkinredbean/app-agent-client";
import { generateObject, generateText } from "ai";
import { z } from "zod";

import type { AgentAction, AgentProvider, AgentProviderContext } from "../agent/contracts.js";
import {
  collectToolResultImages,
  sanitizeToolInput,
  sanitizeToolOutput,
  type ToolResultImage,
} from "../agent/tool-result-content.js";

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

type AgentStepWire = {
  text: string;
  toolCalls: Array<{ name: string; argumentsJson: string }>;
};

export type ModelPrompt = string | Array<{
  role: "user";
  content: Array<
    | { type: "text"; text: string }
    | { type: "image"; image: Buffer; mediaType: string }
  >;
}>;

export interface GenerateObjectRequest {
  readonly model: unknown;
  readonly schema: z.ZodType<AgentStepWire>;
  readonly schemaName: string;
  readonly schemaDescription: string;
  readonly system: string;
  readonly prompt: ModelPrompt;
}

export interface GenerateTextRequest {
  readonly model: unknown;
  readonly system: string;
  readonly prompt: ModelPrompt;
}

export type GenerateObjectFunction = (request: GenerateObjectRequest) => Promise<{ object: unknown }>;
export type GenerateTextFunction = (request: GenerateTextRequest) => Promise<{ text: string }>;

export interface VercelAiAgentProviderOptions {
  readonly providerName: string;
  readonly modelId: string;
  readonly model: unknown;
  readonly stepMode?: "native-object" | "prompt-json";
  readonly generateObjectFn?: GenerateObjectFunction;
  readonly generateTextFn?: GenerateTextFunction;
  readonly close?: () => Promise<void> | void;
}

export class VercelAiAgentProvider implements AgentProvider {
  public readonly kind: string;
  readonly #model: unknown;
  readonly #stepMode: "native-object" | "prompt-json";
  readonly #generateObject: GenerateObjectFunction;
  readonly #generateText: GenerateTextFunction;
  readonly #close: (() => Promise<void> | void) | undefined;

  public constructor({
    providerName,
    modelId,
    model,
    stepMode = "native-object",
    generateObjectFn = generateObject as unknown as GenerateObjectFunction,
    generateTextFn = generateText as unknown as GenerateTextFunction,
    close,
  }: VercelAiAgentProviderOptions) {
    this.#model = model;
    this.#stepMode = stepMode;
    this.#generateObject = generateObjectFn;
    this.#generateText = generateTextFn;
    this.#close = close;
    this.kind = `vercel-ai/${providerName}:${modelId}`;
  }

  public async next(context: AgentProviderContext): Promise<AgentAction> {
    const schema = createAgentStepSchema(context.manifest);
    const prompt = buildAgentStepPrompt(context);
    const images = collectToolResultImages(context.toolExchanges);

    if (this.#stepMode === "native-object") {
      const { object } = await this.#generateObject({
        model: this.#model,
        schema,
        schemaName: "appAgentStep",
        schemaDescription: "A conversational response or zero-to-four available ToolCalls",
        system: AGENT_SYSTEM_PROMPT,
        prompt: promptWithImages(prompt, images),
      });
      return normalizeAgentStep(schema.parse(object));
    }

    const { text } = await this.#generateText({
      model: this.#model,
      system: `${AGENT_SYSTEM_PROMPT}\nReturn JSON only, without prose or Markdown.`,
      prompt: promptWithImages(
        `${prompt}\n\nOutput JSON Schema:\n${JSON.stringify(agentStepJsonSchema(context.manifest))}`,
        images,
      ),
    });
    return normalizeAgentStep(schema.parse(parseModelJson(text)));
  }

  public async close(): Promise<void> {
    await this.#close?.();
  }
}

export function createAgentStepSchema(manifest: ToolsList): z.ZodType<AgentStepWire> {
  const names = manifest.tools.map((tool) => tool.name);
  const nameSchema = names.length > 0
    ? z.enum(names as [string, ...string[]])
    : z.string();
  return z.object({
    text: z.string(),
    toolCalls: z.array(z.object({
      name: nameSchema,
      argumentsJson: z.string().min(2),
    })).max(names.length > 0 ? MAX_CALLS_PER_STEP : 0),
  });
}

export function agentStepJsonSchema(manifest: ToolsList): JsonObject {
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
}: AgentProviderContext): string {
  const tools = manifest.tools.map(({ name, description, inputSchema }) => ({ name, description, inputSchema }));
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

export function parseModelJson(text = ""): unknown {
  const trimmed = text.trim().replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "");
  try {
    return JSON.parse(trimmed) as unknown;
  } catch (error) {
    throw new Error(`Model did not return valid agent-step JSON: ${errorMessage(error)}`, { cause: error });
  }
}

export function normalizeAgentStep(modelStep: AgentStepWire): AgentAction {
  const text = modelStep.text.trim();
  const toolCalls = modelStep.toolCalls.map((call) => ({
    name: call.name,
    arguments: parseArguments(call.name, call.argumentsJson),
  }));
  if (toolCalls.length === 0 && !text) throw new Error("Provider returned neither a response nor a ToolCall");
  return toolCalls.length > 0
    ? { type: "tool_calls", text, toolCalls }
    : { type: "final", text };
}

function parseArguments(tool: string, value: string): JsonObject {
  const parsed = parseModelJson(value);
  if (!isJsonObject(parsed)) throw new Error(`Tool arguments must encode a JSON object: ${tool}`);
  return parsed;
}

function presentToolExchange(exchange: AgentProviderContext["toolExchanges"][number]): object {
  return {
    call: {
      name: exchange.call.name,
      input: sanitizeToolInput(exchange.call.name, exchange.call.input),
    },
    result: exchange.result.ok
      ? { ok: true, output: sanitizeToolOutput(exchange.result.output as JsonValue) }
      : { ok: false, error: exchange.result.error },
  };
}

function promptWithImages(prompt: string, images: readonly ToolResultImage[]): ModelPrompt {
  if (images.length === 0) return prompt;
  const parts: Array<
    | { type: "text"; text: string }
    | { type: "image"; image: Buffer; mediaType: string }
  > = [{ type: "text", text: prompt }];
  for (const image of images) {
    parts.push({
      type: "text",
      text: `Image attached from ${image.toolName} ToolCall ${image.callId}. Coordinate space is described by that ToolResult.`,
    });
    parts.push({ type: "image", image: Buffer.from(image.data, "base64"), mediaType: image.mediaType });
  }
  return [{ role: "user", content: parts }];
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
