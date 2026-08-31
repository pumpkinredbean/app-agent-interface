#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import type { JsonObject, ToolsList } from "@pumpkinredbean/app-agent-client";

import type { AgentEvent, AgentToolClient, AgentTrace, ConversationMessage, RunAgentOptions } from "../agent/contracts.js";
import { runAgent } from "../agent/run-agent.js";
import { createDefaultAgentToolClient } from "../agent/tool-client.js";
import { formatArguments, summarizeResult, toolLabel } from "../cli/trace-printer.js";

const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../../web");
const SESSION_TTL_MS = 2 * 60 * 60 * 1_000;
const MAX_HISTORY_MESSAGES = 12;
const MAX_MESSAGE_LENGTH = 2_000;
const PROVIDERS = new Set(["codex", "copilot", "claude"]);
const STATIC_FILES = new Map<string, readonly [filename: string, contentType: string]>([
  ["/", ["index.html", "text/html; charset=utf-8"]],
  ["/index.html", ["index.html", "text/html; charset=utf-8"]],
  ["/styles.css", ["styles.css", "text/css; charset=utf-8"]],
  ["/app.js", ["app.js", "text/javascript; charset=utf-8"]],
]);

interface WebClient {
  health(): Promise<JsonObject>;
  capabilities(): Promise<ToolsList>;
  appCapabilities?(): Promise<ToolsList>;
  uiStatus?(): Promise<Record<string, unknown>>;
}

interface Session {
  history: ConversationMessage[];
  touchedAt: number;
}

export interface AgentWebServerOptions {
  readonly baseUrl?: string;
  readonly client?: WebClient;
  readonly runAgent?: (message: string, options: RunAgentOptions) => Promise<AgentTrace>;
  readonly uiBaseUrl?: string;
  readonly uiEnabled?: boolean;
}

export function createAgentWebServer(options: AgentWebServerOptions = {}): Server {
  const client = options.client ?? createDefaultAgentToolClient({
    ...(options.baseUrl === undefined ? {} : { baseUrl: options.baseUrl }),
    ...(options.uiBaseUrl === undefined ? {} : { uiBaseUrl: options.uiBaseUrl }),
    ...(options.uiEnabled === undefined ? {} : { uiEnabled: options.uiEnabled }),
  });
  const runAgentFn = options.runAgent ?? runAgent;
  const sessions = new Map<string, Session>();
  const busySessions = new Set<string>();

  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? "/", "http://127.0.0.1");

      if (request.method === "GET" && url.pathname === "/api/status") {
        let app: Record<string, unknown>;
        try {
          const [health, manifest] = await Promise.all([
            client.health(),
            client.appCapabilities?.() ?? client.capabilities(),
          ]);
          app = {
            id: "weather-agent-android",
            label: "Weather Agent",
            ready: Boolean(health.ready),
            protocol: health.protocol,
            runtime: health.runtime,
            toolCount: manifest.tools.length,
            tools: manifest.tools.map((tool) => tool.name),
          };
        } catch {
          app = {
            id: "weather-agent-android",
            label: "Weather Agent",
            ready: false,
            protocol: null,
            runtime: "android",
            toolCount: 0,
            tools: [],
          };
        }
        const uiDriver = await client.uiStatus?.() ?? { enabled: false, ready: false };
        sendJson(response, 200, {
          agent: { ready: true, transport: "local-web" },
          app,
          uiDriver,
          providers: providerStatus(),
        });
        return;
      }

      if (request.method === "POST" && url.pathname === "/api/chat") {
        const body = await readJsonBody(request);
        const message = typeof body.message === "string" ? body.message.trim() : "";
        const sessionId = validSessionId(body.sessionId) ? body.sessionId : randomUUID();
        const providerName = typeof body.provider === "string" && PROVIDERS.has(body.provider) ? body.provider : "codex";
        const model = typeof body.model === "string" && body.model.trim()
          ? body.model.trim().slice(0, 100)
          : undefined;

        if (!message) {
          sendJson(response, 400, { error: "Enter a message." });
          return;
        }
        if (message.length > MAX_MESSAGE_LENGTH) {
          sendJson(response, 400, { error: `Messages are limited to ${MAX_MESSAGE_LENGTH.toLocaleString()} characters.` });
          return;
        }
        if (busySessions.has(sessionId)) {
          sendJson(response, 409, { error: "A previous request is still running in this conversation." });
          return;
        }

        expireSessions(sessions);
        const session = sessions.get(sessionId) ?? { history: [], touchedAt: Date.now() };
        sessions.set(sessionId, session);
        busySessions.add(sessionId);
        openEventStream(response);
        writeEvent(response, { type: "chat.accepted", sessionId, provider: providerName });

        try {
          const trace = await runAgentFn(message, {
            client: client as unknown as AgentToolClient,
            providerName,
            sessionId,
            history: session.history.map((entry) => ({ ...entry })),
            ...(model === undefined ? {} : { model }),
            onEvent: (event) => writeEvent(response, presentAgentEvent(event)),
          });

          session.history.push(
            { role: "user", content: message },
            { role: "assistant", content: trace.answer, toolExchanges: trace.toolExchanges },
          );
          session.history = session.history.slice(-MAX_HISTORY_MESSAGES);
          session.touchedAt = Date.now();

          writeEvent(response, {
            type: "chat.completed",
            sessionId,
            answer: trace.answer,
            attributions: trace.attributions ?? [],
            run: {
              provider: providerLabel(trace.provider),
              modelStepCount: trace.modelStepCount,
              toolCallCount: trace.toolCallCount,
            },
          });
        } catch (error) {
          writeEvent(response, { type: "chat.error", message: friendlyError(error) });
        } finally {
          busySessions.delete(sessionId);
          response.end();
        }
        return;
      }

      if (request.method === "GET") {
        const staticFile = STATIC_FILES.get(url.pathname);
        if (staticFile !== undefined) {
          const [filename, contentType] = staticFile;
          const contents = await readFile(join(WEB_ROOT, filename));
          response.writeHead(200, {
            "Content-Type": contentType,
            "Cache-Control": "no-store",
            "X-Content-Type-Options": "nosniff",
          });
          response.end(contents);
          return;
        }
      }

      sendJson(response, 404, { error: "Not found" });
    } catch (error) {
      if (!response.headersSent) sendJson(response, 500, { error: friendlyError(error) });
      else response.end();
    }
  });
}

function presentAgentEvent(event: AgentEvent): Record<string, unknown> {
  const base = { type: "agent.event", event: event.type, at: event.at };
  switch (event.type) {
    case "agent.started": return { ...base, provider: providerLabel(event.provider) };
    case "model.started": return {
      ...base,
      provider: providerLabel(event.provider),
      step: event.step,
      toolCallCount: event.toolCallCount,
    };
    case "assistant.message": return { ...base, text: event.text, step: event.step };
    case "tool.dispatched": return {
      ...base,
      tool: event.call.name,
      toolLabel: toolLabel(event.call.name),
      argumentsLabel: formatArguments(event.call.name, event.call.input),
      requestId: event.call.id.slice(0, 8),
      step: event.step,
    };
    case "tool.completed": return {
      ...base,
      tool: event.call.name,
      requestId: event.call.id.slice(0, 8),
      durationMs: event.durationMs,
      summary: summarizeResult(event.call.name, event.result.output),
      step: event.step,
    };
    case "tool.failed": return {
      ...base,
      tool: event.call.name,
      toolLabel: toolLabel(event.call.name),
      requestId: event.call.id.slice(0, 8),
      durationMs: event.durationMs,
      code: event.result.error.code,
      message: event.result.error.message,
      step: event.step,
    };
    case "agent.completed": return {
      ...base,
      durationMs: event.durationMs,
      modelStepCount: event.modelStepCount,
      toolCallCount: event.toolCallCount,
    };
    case "agent.failed": return {
      ...base,
      message: friendlyError({ message: event.message }),
      durationMs: event.durationMs,
    };
  }
}

function openEventStream(response: ServerResponse): void {
  response.writeHead(200, {
    "Content-Type": "application/x-ndjson; charset=utf-8",
    "Cache-Control": "no-store",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
    "X-Content-Type-Options": "nosniff",
  });
  response.flushHeaders();
}

function writeEvent(response: ServerResponse, event: unknown): void {
  if (!response.destroyed && !response.writableEnded) response.write(`${JSON.stringify(event)}\n`);
}

async function readJsonBody(request: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    size += buffer.length;
    if (size > 32 * 1_024) throw new Error("The request is too large.");
    chunks.push(buffer);
  }
  try {
    const value = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}") as unknown;
    if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error();
    return value as Record<string, unknown>;
  } catch {
    throw new Error("The request format is invalid.");
  }
}

function sendJson(response: ServerResponse, status: number, body: unknown): void {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(JSON.stringify(body));
}

function providerStatus(): readonly Record<string, unknown>[] {
  const codexHome = process.env.CODEX_HOME ?? join(homedir(), ".codex");
  return [
    {
      id: "codex",
      label: "Codex",
      detail: "ChatGPT subscription",
      configured: existsSync(join(codexHome, "auth.json")),
      defaultModel: process.env.CODEX_MODEL ?? "gpt-5.6-luna",
      models: [process.env.CODEX_MODEL ?? "gpt-5.6-luna"],
    },
    {
      id: "copilot",
      label: "GitHub Copilot",
      detail: "Copilot subscription",
      configured: true,
      defaultModel: process.env.COPILOT_MODEL ?? "auto",
      models: [process.env.COPILOT_MODEL ?? "auto"],
    },
    {
      id: "claude",
      label: "Claude",
      detail: "Agent SDK",
      configured: Boolean(process.env.ANTHROPIC_API_KEY),
      defaultModel: process.env.CLAUDE_MODEL ?? "claude-code-default",
      models: [process.env.CLAUDE_MODEL ?? "claude-code-default"],
    },
  ];
}

function validSessionId(value: unknown): value is string {
  return typeof value === "string" && /^[a-zA-Z0-9-]{8,80}$/.test(value);
}

function expireSessions(sessions: Map<string, Session>): void {
  const cutoff = Date.now() - SESSION_TTL_MS;
  for (const [id, session] of sessions) {
    if (session.touchedAt < cutoff) sessions.delete(id);
  }
}

function providerLabel(provider = ""): string {
  if (provider.startsWith("vercel-ai/codex-cli:")) return `Codex · ${provider.split(":").at(-1)}`;
  if (provider.startsWith("vercel-ai/github-copilot-sdk:")) return `GitHub Copilot · ${provider.split(":").at(-1)}`;
  if (provider.startsWith("claude-agent-sdk:")) return `Claude · ${provider.split(":").at(-1)}`;
  return provider;
}

function friendlyError(error: unknown): string {
  const message = error instanceof Error
    ? error.message
    : typeof error === "object" && error !== null && "message" in error
      ? String(error.message)
      : String(error);
  if (/fetch failed|ECONNREFUSED|App Agent runtime/i.test(message)) {
    return "Cannot connect to the Android app service. Check the emulator and ADB forwarding.";
  }
  if (/ANTHROPIC_API_KEY/.test(message)) return "Claude requires ANTHROPIC_API_KEY.";
  if (/auth|login|credential/i.test(message)) return "The selected provider requires authentication.";
  return message;
}

const isMain = process.argv[1] !== undefined
  && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;

if (isMain) {
  const host = process.env.AGENT_UI_HOST ?? "127.0.0.1";
  const port = Number(process.env.AGENT_UI_PORT ?? 4317);
  const server = createAgentWebServer();
  server.listen(port, host, () => {
    console.log(`Weather Agent Example UI · http://${host}:${port}`);
    console.log("Android Tool Runtime · http://127.0.0.1:18765");
  });
}
