import { isToolCallError as isHarnessToolCallError } from "@pumpkinredbean/app-agent-android-ui";

import { createDefaultAgentToolClient } from "./agent-tool-client.mjs";
import { createAgentProvider } from "./provider-factory.mjs";
import { authorizeToolCall } from "./policy.mjs";
import { ToolCallError } from "./tool-client.mjs";
import { sanitizeToolExchange, sanitizeToolInput, sanitizeToolOutput } from "./tool-result-content.mjs";

const DEFAULT_MAX_MODEL_STEPS = 8;
const DEFAULT_MAX_TOOL_CALLS = 12;

export async function runAgent(userMessage, options = {}) {
  if (!userMessage?.trim()) throw new Error("User message is required");
  const startedAt = Date.now();
  const emit = (type, detail = {}) => options.onEvent?.({ type, at: Date.now(), ...detail });
  const client = options.client ?? createDefaultAgentToolClient({
    baseUrl: options.baseUrl,
    uiBaseUrl: options.uiBaseUrl,
    uiEnabled: options.uiEnabled,
  });
  const ownsProvider = !options.provider;
  const provider = options.provider ?? await createAgentProvider(options.providerName, {
    model: options.model,
    cwd: options.cwd,
  });
  const maxModelSteps = options.maxModelSteps ?? DEFAULT_MAX_MODEL_STEPS;
  const maxToolCalls = options.maxToolCalls ?? DEFAULT_MAX_TOOL_CALLS;

  try {
    const manifest = await client.capabilities();
    const conversationHistory = normalizeHistory(options.history);
    const toolExchanges = [];
    const assistantMessages = [];
    emit("agent.started", { userMessage, provider: provider.kind });

    for (let step = 1; step <= maxModelSteps; step += 1) {
      emit("model.started", { provider: provider.kind, step, toolCallCount: toolExchanges.length });
      const action = await provider.next({
        userMessage,
        manifest,
        conversationHistory,
        toolExchanges,
        assistantMessages,
        step,
      });

      if (action.type === "final") {
        const answer = action.text.trim();
        emit("agent.completed", {
          answer,
          durationMs: Date.now() - startedAt,
          modelStepCount: step,
          toolCallCount: toolExchanges.length,
        });
        return createTrace({ provider, manifest, answer, step, toolExchanges, assistantMessages });
      }

      if (action.text) {
        assistantMessages.push(action.text);
        emit("assistant.message", { text: action.text, step });
      }
      if (toolExchanges.length + action.toolCalls.length > maxToolCalls) {
        throw new Error(`Agent exceeded the ${maxToolCalls}-ToolCall limit`);
      }
      if (step === maxModelSteps) {
        throw new Error(`Agent requested another ToolCall on its final allowed model step (${maxModelSteps})`);
      }

      for (const requestedCall of action.toolCalls) authorizeToolCall(requestedCall, manifest);

      for (const requestedCall of action.toolCalls) {
        let dispatchedAt = Date.now();
        let dispatchedCall;
        try {
          const exchange = await client.call(requestedCall.name, requestedCall.arguments, {
            onDispatch: (call) => {
              dispatchedCall = call;
              dispatchedAt = Date.now();
              emit("tool.dispatched", { call, step });
            },
          });
          toolExchanges.push(exchange);
          emit("tool.completed", {
            call: exchange.call,
            result: exchange.result,
            durationMs: Date.now() - dispatchedAt,
            step,
          });
        } catch (error) {
          if (!dispatchedCall) throw error;
          const handledToolError = error instanceof ToolCallError || isHarnessToolCallError(error);
          const result = handledToolError
            ? error.envelope
            : {
                protocol: manifest.protocol,
                type: "tool.result",
                id: dispatchedCall.id,
                ok: false,
                error: {
                  code: "TOOL_TRANSPORT_FAILED",
                  message: error.message,
                  retryable: true,
                },
              };
          emit("tool.failed", {
            call: dispatchedCall,
            result,
            durationMs: Date.now() - dispatchedAt,
            step,
          });
          if (!handledToolError) throw error;
          toolExchanges.push({ call: dispatchedCall, result });
        }
      }
    }

    throw new Error(`Agent reached the ${maxModelSteps}-step limit without a final response`);
  } catch (error) {
    emit("agent.failed", { message: error.message, durationMs: Date.now() - startedAt });
    throw error;
  } finally {
    if (ownsProvider) await provider.close?.();
  }
}

function createTrace({ provider, manifest, answer, step, toolExchanges, assistantMessages }) {
  return {
    agent: "app-agent-interface-weather-agent-example",
    provider: provider.kind,
    manifest: {
      protocol: manifest.protocol,
      availableTools: manifest.tools.map((tool) => tool.name),
    },
    modelStepCount: step,
    toolCallCount: toolExchanges.length,
    toolExchanges: toolExchanges.map(sanitizeToolExchange),
    assistantMessages,
    attributions: collectAttributions(toolExchanges),
    answer,
  };
}

function collectAttributions(toolExchanges) {
  const seen = new Set();
  const attributions = [];
  for (const exchange of toolExchanges) {
    const attribution = exchange.result?.ok ? exchange.result.output?.attribution : null;
    if (!attribution?.text || !attribution?.url) continue;
    const key = `${attribution.text}\n${attribution.url}\n${attribution.license ?? ""}`;
    if (seen.has(key)) continue;
    seen.add(key);
    attributions.push({
      text: attribution.text,
      url: attribution.url,
      ...(attribution.license ? { license: attribution.license } : {}),
    });
  }
  return attributions;
}

function normalizeHistory(history) {
  if (!Array.isArray(history)) return [];
  return history
    .filter((message) => message && ["user", "assistant"].includes(message.role) && typeof message.content === "string")
    .map((message) => ({
      role: message.role,
      content: message.content.slice(0, 4_000),
      ...(message.role === "assistant" && Array.isArray(message.toolExchanges)
        ? { toolExchanges: message.toolExchanges.slice(-8).map(normalizeHistoricalExchange).filter(Boolean) }
        : {}),
    }))
    .slice(-12);
}

function normalizeHistoricalExchange(exchange) {
  if (!exchange?.call?.name || !exchange?.result) return null;
  return {
    call: {
      name: exchange.call.name,
      input: sanitizeToolInput(exchange.call.name, exchange.call.input ?? {}),
    },
    result: exchange.result.ok
      ? { ok: true, output: sanitizeToolOutput(exchange.result.output) }
      : { ok: false, error: exchange.result.error },
  };
}
