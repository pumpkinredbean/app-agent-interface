import type { JsonObject } from "@pumpkinredbean/app-agent-client";

import type { AgentAction, AgentProvider, AgentProviderContext } from "../agent/contracts.js";
import { collectToolResultImages, type ToolResultImage } from "../agent/tool-result-content.js";
import type { AgentProviderOptions } from "./types.js";
import {
  agentStepJsonSchema,
  buildAgentStepPrompt,
  createAgentStepSchema,
  normalizeAgentStep,
} from "./vercel-ai.js";

const AGENT_SYSTEM_PROMPT = [
  "You are a conversational agent with semantic application tools and optional harness-owned mobile UI tools for one connected Android app.",
  "For each step, either answer the user or request one or more available tools. Tool calls are optional.",
  "Answer directly when the request can be handled from the conversation or Capability Manifest, including questions about available tools.",
  "Request tools only for current application data or an application action.",
  "After ToolResults are supplied, decide again whether to answer or request another tool.",
  "Prefer semantic application tools. For UI interaction use mobile_observe first, control nodeIds before coordinates, and mobile_screenshot only when the semantic snapshot is insufficient.",
  "mobile_multi_tap executes multiple different coordinates once each in array order; it is not double-tap.",
  "Use only the supplied Capability Manifest and ToolResults. Do not execute Claude SDK tools.",
  "Do not repeat ToolResult attribution in response text; the harness renders it separately.",
].join("\n");

type ClaudeContent = Array<
  | { type: "text"; text: string }
  | { type: "image"; source: { type: "base64"; media_type: string; data: string } }
>;

type ClaudePrompt = string | AsyncIterable<{
  type: "user";
  message: { role: "user"; content: ClaudeContent };
  parent_tool_use_id: null;
}>;

interface ClaudeQueryOptions {
  readonly cwd: string;
  readonly maxTurns: number;
  readonly tools: readonly never[];
  readonly permissionMode: "dontAsk";
  readonly settingSources: readonly never[];
  readonly systemPrompt: string;
  readonly model?: string;
  readonly pathToClaudeCodeExecutable?: string;
  readonly outputFormat?: { readonly type: "json_schema"; readonly schema: JsonObject };
}

interface ClaudeResultMessage {
  readonly type: "result";
  readonly subtype: string;
  readonly is_error: boolean;
  readonly result: string;
  readonly structured_output?: unknown;
  readonly errors?: readonly string[];
}

type ClaudeMessage = ClaudeResultMessage | { readonly type: string };
export type ClaudeQueryFunction = (request: {
  readonly prompt: ClaudePrompt;
  readonly options: ClaudeQueryOptions;
}) => AsyncIterable<ClaudeMessage>;

export interface ClaudeAgentProviderOptions extends AgentProviderOptions {
  readonly queryFn?: ClaudeQueryFunction;
}

export class ClaudeAgentSdkProvider implements AgentProvider {
  public readonly kind: string;
  readonly #modelId: string;
  readonly #query: ClaudeQueryFunction | undefined;
  readonly #cwd: string;

  public constructor({
    model = process.env.CLAUDE_MODEL,
    queryFn,
    cwd = process.cwd(),
  }: ClaudeAgentProviderOptions = {}) {
    this.#modelId = model ?? "claude-code-default";
    this.kind = `claude-agent-sdk:${this.#modelId}`;
    this.#query = queryFn;
    this.#cwd = cwd;
  }

  public async next(context: AgentProviderContext): Promise<AgentAction> {
    const schema = createAgentStepSchema(context.manifest);
    const prompt = buildAgentStepPrompt(context);
    const result = await this.#run({
      systemPrompt: AGENT_SYSTEM_PROMPT,
      prompt: claudePromptWithImages(prompt, collectToolResultImages(context.toolExchanges)),
      outputFormat: { type: "json_schema", schema: agentStepJsonSchema(context.manifest) },
    });
    const output = result.structured_output ?? JSON.parse(result.result) as unknown;
    return normalizeAgentStep(schema.parse(output));
  }

  async #run({
    prompt,
    systemPrompt,
    outputFormat,
  }: {
    prompt: ClaudePrompt;
    systemPrompt: string;
    outputFormat?: ClaudeQueryOptions["outputFormat"];
  }): Promise<ClaudeResultMessage> {
    if (this.#query === undefined && !process.env.ANTHROPIC_API_KEY) {
      throw new Error("Claude Agent SDK provider requires ANTHROPIC_API_KEY; claude.ai subscription auth is not reused");
    }
    const query = this.#query
      ?? (await import("@anthropic-ai/claude-agent-sdk")).query as unknown as ClaudeQueryFunction;
    const options: ClaudeQueryOptions = {
      cwd: this.#cwd,
      maxTurns: 1,
      tools: [],
      permissionMode: "dontAsk",
      settingSources: [],
      systemPrompt,
      ...(this.#modelId === "claude-code-default" ? {} : { model: this.#modelId }),
      ...(process.env.CLAUDE_CODE_PATH
        ? { pathToClaudeCodeExecutable: process.env.CLAUDE_CODE_PATH }
        : {}),
      ...(outputFormat === undefined ? {} : { outputFormat }),
    };

    let finalResult: ClaudeResultMessage | undefined;
    for await (const message of query({ prompt, options })) {
      if (message.type === "result" && "subtype" in message) finalResult = message;
    }
    if (finalResult === undefined) throw new Error("Claude Agent SDK ended without a result message");
    if (finalResult.subtype !== "success" || finalResult.is_error) {
      const details = finalResult.errors?.join("; ") || finalResult.result || finalResult.subtype;
      throw new Error(`Claude Agent SDK failed: ${details}`);
    }
    return finalResult;
  }
}

export function createClaudeAgentProvider(options: ClaudeAgentProviderOptions = {}): ClaudeAgentSdkProvider {
  return new ClaudeAgentSdkProvider(options);
}

function claudePromptWithImages(prompt: string, images: readonly ToolResultImage[]): ClaudePrompt {
  if (images.length === 0) return prompt;
  return (async function* messages() {
    const content: ClaudeContent = [{ type: "text", text: prompt }];
    for (const image of images) {
      content.push({
        type: "text",
        text: `Image attached from ${image.toolName} ToolCall ${image.callId}. Coordinate space is described by that ToolResult.`,
      });
      content.push({
        type: "image",
        source: { type: "base64", media_type: image.mediaType, data: image.data },
      });
    }
    yield {
      type: "user" as const,
      message: { role: "user" as const, content },
      parent_tool_use_id: null,
    };
  }());
}
