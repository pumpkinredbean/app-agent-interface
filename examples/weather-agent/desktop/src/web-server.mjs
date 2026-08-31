#!/usr/bin/env node

import { randomUUID } from "node:crypto";
import { existsSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { homedir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { createDefaultAgentToolClient } from "./agent-tool-client.mjs";
import { runAgent } from "./agent.mjs";
import { formatArguments, summarizeResult, toolLabel } from "./trace-printer.mjs";

const WEB_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../web");
const SESSION_TTL_MS = 2 * 60 * 60 * 1_000;
const MAX_HISTORY_MESSAGES = 12;
const MAX_MESSAGE_LENGTH = 2_000;
const PROVIDERS = new Set(["codex", "copilot", "claude"]);
const STATIC_FILES = new Map([
  ["/", ["index.html", "text/html; charset=utf-8"]],
  ["/index.html", ["index.html", "text/html; charset=utf-8"]],
  ["/styles.css", ["styles.css", "text/css; charset=utf-8"]],
  ["/app.js", ["app.js", "text/javascript; charset=utf-8"]],
]);

export function createAgentWebServer(options = {}) {
  const client = options.client ?? createDefaultAgentToolClient({
    baseUrl: options.baseUrl,
    uiBaseUrl: options.uiBaseUrl,
    uiEnabled: options.uiEnabled,
  });
  const runAgentFn = options.runAgent ?? runAgent;
  const sessions = new Map();
  const busySessions = new Set();

  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? "/", "http://127.0.0.1");

      if (request.method === "GET" && url.pathname === "/api/status") {
        let app;
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
        return sendJson(response, 200, {
          agent: { ready: true, transport: "local-web" },
          app,
          uiDriver,
          providers: providerStatus(),
        });
      }

      if (request.method === "POST" && url.pathname === "/api/chat") {
        const body = await readJsonBody(request);
        const message = typeof body.message === "string" ? body.message.trim() : "";
        const sessionId = validSessionId(body.sessionId) ? body.sessionId : randomUUID();
        const providerName = PROVIDERS.has(body.provider) ? body.provider : "codex";
        const model = typeof body.model === "string" && body.model.trim() ? body.model.trim().slice(0, 100) : undefined;

        if (!message) return sendJson(response, 400, { error: "Enter a message." });
        if (message.length > MAX_MESSAGE_LENGTH) {
          return sendJson(response, 400, { error: `Messages are limited to ${MAX_MESSAGE_LENGTH.toLocaleString()} characters.` });
        }
        if (busySessions.has(sessionId)) {
          return sendJson(response, 409, { error: "A previous request is still running in this conversation." });
        }

        expireSessions(sessions);
        const session = sessions.get(sessionId) ?? { history: [], touchedAt: Date.now() };
        sessions.set(sessionId, session);
        busySessions.add(sessionId);
        openEventStream(response);
        writeEvent(response, { type: "chat.accepted", sessionId, provider: providerName });

        try {
          const trace = await runAgentFn(message, {
            client,
            providerName,
            model,
            sessionId,
            history: session.history.map((entry) => ({ ...entry })),
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
          writeEvent(response, {
            type: "chat.error",
            message: friendlyError(error),
          });
        } finally {
          busySessions.delete(sessionId);
          response.end();
        }
        return;
      }

      if (request.method === "GET" && STATIC_FILES.has(url.pathname)) {
        const [filename, contentType] = STATIC_FILES.get(url.pathname);
        const contents = await readFile(join(WEB_ROOT, filename));
        response.writeHead(200, {
          "Content-Type": contentType,
          "Cache-Control": "no-store",
          "X-Content-Type-Options": "nosniff",
        });
        response.end(contents);
        return;
      }

      sendJson(response, 404, { error: "Not found" });
    } catch (error) {
      if (!response.headersSent) sendJson(response, 500, { error: friendlyError(error) });
      else response.end();
    }
  });
}

function presentAgentEvent(event) {
  const base = { type: "agent.event", event: event.type, at: event.at };
  switch (event.type) {
    case "agent.started":
      return { ...base, provider: providerLabel(event.provider) };
    case "model.started":
      return {
        ...base,
        provider: providerLabel(event.provider),
        step: event.step,
        toolCallCount: event.toolCallCount,
      };
    case "assistant.message":
      return { ...base, text: event.text, step: event.step };
    case "tool.dispatched":
      return {
        ...base,
        tool: event.call.name,
        toolLabel: toolLabel(event.call.name),
        argumentsLabel: formatArguments(event.call.name, event.call.input),
        requestId: event.call.id.slice(0, 8),
        step: event.step,
      };
    case "tool.completed":
      return {
        ...base,
        tool: event.call.name,
        requestId: event.call.id.slice(0, 8),
        durationMs: event.durationMs,
        summary: summarizeResult(event.call.name, event.result.output),
        step: event.step,
      };
    case "tool.failed":
      return {
        ...base,
        tool: event.call.name,
        toolLabel: toolLabel(event.call.name),
        requestId: event.call.id.slice(0, 8),
        durationMs: event.durationMs,
        code: event.result.error?.code ?? "TOOL_CALL_FAILED",
        message: event.result.error?.message ?? "The app rejected the ToolCall",
        step: event.step,
      };
    case "agent.completed":
      return {
        ...base,
        durationMs: event.durationMs,
        modelStepCount: event.modelStepCount,
        toolCallCount: event.toolCallCount,
      };
    case "agent.failed":
      return { ...base, message: friendlyError({ message: event.message }), durationMs: event.durationMs };
    default:
      return base;
  }
}

function openEventStream(response) {
  response.writeHead(200, {
    "Content-Type": "application/x-ndjson; charset=utf-8",
    "Cache-Control": "no-store",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
    "X-Content-Type-Options": "nosniff",
  });
  response.flushHeaders();
}

function writeEvent(response, event) {
  if (!response.destroyed && !response.writableEnded) response.write(`${JSON.stringify(event)}\n`);
}

async function readJsonBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 32 * 1_024) throw new Error("The request is too large.");
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  } catch {
    throw new Error("The request format is invalid.");
  }
}

function sendJson(response, status, body) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(JSON.stringify(body));
}

function providerStatus() {
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

function validSessionId(value) {
  return typeof value === "string" && /^[a-zA-Z0-9-]{8,80}$/.test(value);
}

function expireSessions(sessions) {
  const cutoff = Date.now() - SESSION_TTL_MS;
  for (const [id, session] of sessions) {
    if (session.touchedAt < cutoff) sessions.delete(id);
  }
}

function providerLabel(provider = "") {
  if (provider.startsWith("vercel-ai/codex-cli:")) return `Codex · ${provider.split(":").at(-1)}`;
  if (provider.startsWith("vercel-ai/github-copilot-sdk:")) return `GitHub Copilot · ${provider.split(":").at(-1)}`;
  if (provider.startsWith("claude-agent-sdk:")) return `Claude · ${provider.split(":").at(-1)}`;
  return provider;
}

function friendlyError(error) {
  const message = error?.message ?? String(error);
  if (/fetch failed|ECONNREFUSED|App Tool Runtime/i.test(message)) {
    return "Cannot connect to the Android app service. Check the emulator and ADB forwarding.";
  }
  if (/ANTHROPIC_API_KEY/.test(message)) return "Claude requires ANTHROPIC_API_KEY.";
  if (/auth|login|credential/i.test(message)) return "The selected provider requires authentication.";
  return message;
}

const isMain = process.argv[1]
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
