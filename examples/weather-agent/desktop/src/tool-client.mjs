import { randomUUID } from "node:crypto";

export const PROTOCOL = "app-agent/1";

export class ToolCallError extends Error {
  constructor(envelope) {
    super(envelope?.error?.message ?? "ToolCall failed");
    this.name = "ToolCallError";
    this.code = envelope?.error?.code ?? "UNKNOWN_TOOL_ERROR";
    this.retryable = Boolean(envelope?.error?.retryable);
    this.envelope = envelope;
  }
}

export class AppToolClient {
  constructor(baseUrl = process.env.APP_TOOL_BASE_URL ?? "http://127.0.0.1:18765") {
    this.baseUrl = baseUrl.replace(/\/$/, "");
  }

  async health() {
    return this.#json("/healthz");
  }

  async capabilities() {
    const manifest = await this.#json("/v1/tools");
    if (manifest.protocol !== PROTOCOL || manifest.type !== "tools.list" || !Array.isArray(manifest.tools)) {
      throw new Error("Unsupported app capability manifest");
    }
    return manifest;
  }

  async call(tool, args, options = {}) {
    const id = randomUUID();
    const envelope = {
      protocol: PROTOCOL,
      type: "tool.call",
      id,
      name: tool,
      input: args ?? {},
    };
    options.onDispatch?.(envelope);

    const result = await this.#json(
      "/v1/tool-calls",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(envelope),
      },
      75_000,
    );

    if (result.protocol !== PROTOCOL || result.type !== "tool.result" || result.id !== id) {
      throw new Error("Invalid ToolResult envelope from app");
    }
    if (!result.ok) throw new ToolCallError(result);
    return { call: envelope, result };
  }

  async #json(path, options = {}, timeoutMs = 12_000) {
    const response = await fetch(`${this.baseUrl}${path}`, {
      ...options,
      signal: AbortSignal.timeout(timeoutMs),
    });
    const text = await response.text();
    let body;
    try {
      body = JSON.parse(text);
    } catch {
      throw new Error(`App Tool Runtime returned non-JSON HTTP ${response.status}`);
    }
    if (!response.ok) throw new Error(body?.error?.message ?? `App Tool Runtime HTTP ${response.status}`);
    return body;
  }
}
