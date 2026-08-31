import {
  agentStepJsonSchema,
  buildAgentStepPrompt,
  createAgentStepSchema,
  normalizeAgentStep,
} from "./vercel-ai.mjs";
import { collectToolResultImages } from "../tool-result-content.mjs";

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

export class ClaudeAgentSdkProvider {
  constructor({ model = process.env.CLAUDE_MODEL, queryFn, cwd = process.cwd() } = {}) {
    this.modelId = model ?? "claude-code-default";
    this.kind = `claude-agent-sdk:${this.modelId}`;
    this.queryFn = queryFn;
    this.cwd = cwd;
  }

  async next(context) {
    const schema = createAgentStepSchema(context.manifest);
    const prompt = buildAgentStepPrompt(context);
    const result = await this.#run({
      systemPrompt: AGENT_SYSTEM_PROMPT,
      prompt: claudePromptWithImages(prompt, collectToolResultImages(context.toolExchanges)),
      outputFormat: { type: "json_schema", schema: agentStepJsonSchema(context.manifest) },
    });
    const output = result.structured_output ?? JSON.parse(result.result);
    return normalizeAgentStep(schema.parse(output));
  }

  async #run({ prompt, systemPrompt, outputFormat }) {
    if (!this.queryFn && !process.env.ANTHROPIC_API_KEY) {
      throw new Error("Claude Agent SDK provider requires ANTHROPIC_API_KEY; claude.ai subscription auth is not reused");
    }
    const query = this.queryFn ?? (await import("@anthropic-ai/claude-agent-sdk")).query;
    const options = {
      cwd: this.cwd,
      maxTurns: 1,
      tools: [],
      permissionMode: "dontAsk",
      settingSources: [],
      systemPrompt,
      ...(this.modelId === "claude-code-default" ? {} : { model: this.modelId }),
      ...(process.env.CLAUDE_CODE_PATH ? { pathToClaudeCodeExecutable: process.env.CLAUDE_CODE_PATH } : {}),
      ...(outputFormat ? { outputFormat } : {}),
    };

    let finalResult;
    for await (const message of query({ prompt, options })) {
      if (message.type === "result") finalResult = message;
    }
    if (!finalResult) throw new Error("Claude Agent SDK ended without a result message");
    if (finalResult.subtype !== "success" || finalResult.is_error) {
      const details = finalResult.errors?.join("; ") || finalResult.result || finalResult.subtype;
      throw new Error(`Claude Agent SDK failed: ${details}`);
    }
    return finalResult;
  }
}

export function createClaudeAgentProvider(options = {}) {
  return new ClaudeAgentSdkProvider(options);
}

function claudePromptWithImages(prompt, images) {
  if (images.length === 0) return prompt;
  return (async function* messages() {
    const content = [{ type: "text", text: prompt }];
    for (const image of images) {
      content.push({
        type: "text",
        text: `Image attached from ${image.toolName} ToolCall ${image.callId}. Coordinate space is described by that ToolResult.`,
      });
      content.push({
        type: "image",
        source: {
          type: "base64",
          media_type: image.mediaType,
          data: image.data,
        },
      });
    }
    yield {
      type: "user",
      message: { role: "user", content },
      parent_tool_use_id: null,
    };
  }());
}
