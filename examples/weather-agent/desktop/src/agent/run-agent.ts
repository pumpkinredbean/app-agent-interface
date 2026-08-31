import {
  isJsonObject,
  isToolCallError,
  type JsonValue,
  type ToolCall,
  type ToolExchange,
  type ToolFailure,
  type ToolsList,
} from "@pumpkinredbean/app-agent-client";

import { createAgentProvider } from "../providers/factory.js";
import { authorizeToolCall } from "./policy.js";
import { sanitizeToolExchange, sanitizeToolInput, sanitizeToolOutput } from "./tool-result-content.js";
import { createDefaultAgentToolClient } from "./tool-client.js";
import type {
  AgentEvent,
  AgentProvider,
  AgentTrace,
  Attribution,
  ConversationMessage,
  HistoricalToolExchange,
  RunAgentOptions,
} from "./contracts.js";

const DEFAULT_MAX_MODEL_STEPS = 8;
const DEFAULT_MAX_TOOL_CALLS = 12;

export async function runAgent(userMessage: string, options: RunAgentOptions = {}): Promise<AgentTrace> {
  if (!userMessage.trim()) throw new Error("User message is required");
  const startedAt = Date.now();
  const emit = (event: AgentEvent): void => options.onEvent?.(event);
  const client = options.client ?? createDefaultAgentToolClient({
    ...(options.baseUrl === undefined ? {} : { baseUrl: options.baseUrl }),
    ...(options.uiBaseUrl === undefined ? {} : { uiBaseUrl: options.uiBaseUrl }),
    ...(options.uiEnabled === undefined ? {} : { uiEnabled: options.uiEnabled }),
  });
  const ownsProvider = options.provider === undefined;
  const provider = options.provider ?? await createAgentProvider(options.providerName, {
    ...(options.model === undefined ? {} : { model: options.model }),
    ...(options.cwd === undefined ? {} : { cwd: options.cwd }),
  });
  const maxModelSteps = options.maxModelSteps ?? DEFAULT_MAX_MODEL_STEPS;
  const maxToolCalls = options.maxToolCalls ?? DEFAULT_MAX_TOOL_CALLS;

  try {
    const manifest = await client.capabilities();
    const conversationHistory = normalizeHistory(options.history);
    const toolExchanges: ToolExchange[] = [];
    const assistantMessages: string[] = [];
    emit({ type: "agent.started", at: Date.now(), userMessage, provider: provider.kind });

    for (let step = 1; step <= maxModelSteps; step += 1) {
      emit({
        type: "model.started",
        at: Date.now(),
        provider: provider.kind,
        step,
        toolCallCount: toolExchanges.length,
      });
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
        emit({
          type: "agent.completed",
          at: Date.now(),
          answer,
          durationMs: Date.now() - startedAt,
          modelStepCount: step,
          toolCallCount: toolExchanges.length,
        });
        return createTrace({ provider, manifest, answer, step, toolExchanges, assistantMessages });
      }

      if (action.text) {
        assistantMessages.push(action.text);
        emit({ type: "assistant.message", at: Date.now(), text: action.text, step });
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
        let dispatchedCall: ToolCall | undefined;
        try {
          const exchange = await client.call(requestedCall.name, requestedCall.arguments, {
            onDispatch: (call) => {
              dispatchedCall = call;
              dispatchedAt = Date.now();
              emit({ type: "tool.dispatched", at: Date.now(), call, step });
            },
          });
          toolExchanges.push(exchange);
          emit({
            type: "tool.completed",
            at: Date.now(),
            call: exchange.call,
            result: exchange.result,
            durationMs: Date.now() - dispatchedAt,
            step,
          });
        } catch (error) {
          if (dispatchedCall === undefined) throw error;
          const result: ToolFailure = isToolCallError(error)
            ? error.envelope
            : {
                protocol: manifest.protocol,
                type: "tool.result",
                id: dispatchedCall.id,
                ok: false,
                error: {
                  code: "TOOL_TRANSPORT_FAILED",
                  message: errorMessage(error),
                  retryable: true,
                },
              };
          emit({
            type: "tool.failed",
            at: Date.now(),
            call: dispatchedCall,
            result,
            durationMs: Date.now() - dispatchedAt,
            step,
          });
          if (!isToolCallError(error)) throw error;
          toolExchanges.push({ call: dispatchedCall, result });
        }
      }
    }

    throw new Error(`Agent reached the ${maxModelSteps}-step limit without a final response`);
  } catch (error) {
    emit({ type: "agent.failed", at: Date.now(), message: errorMessage(error), durationMs: Date.now() - startedAt });
    throw error;
  } finally {
    if (ownsProvider) await provider.close?.();
  }
}

function createTrace({
  provider,
  manifest,
  answer,
  step,
  toolExchanges,
  assistantMessages,
}: {
  provider: AgentProvider;
  manifest: ToolsList;
  answer: string;
  step: number;
  toolExchanges: readonly ToolExchange[];
  assistantMessages: readonly string[];
}): AgentTrace {
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

function collectAttributions(toolExchanges: readonly ToolExchange[]): Attribution[] {
  const seen = new Set<string>();
  const attributions: Attribution[] = [];
  for (const exchange of toolExchanges) {
    if (!exchange.result.ok || !isJsonObject(exchange.result.output)) continue;
    const attribution = exchange.result.output.attribution;
    if (!isJsonObject(attribution) || typeof attribution.text !== "string" || typeof attribution.url !== "string") {
      continue;
    }
    const license = typeof attribution.license === "string" ? attribution.license : undefined;
    const key = `${attribution.text}\n${attribution.url}\n${license ?? ""}`;
    if (seen.has(key)) continue;
    seen.add(key);
    attributions.push({
      text: attribution.text,
      url: attribution.url,
      ...(license === undefined ? {} : { license }),
    });
  }
  return attributions;
}

function normalizeHistory(history: readonly ConversationMessage[] | undefined): ConversationMessage[] {
  if (history === undefined) return [];
  return history
    .filter((message) => ["user", "assistant"].includes(message.role) && typeof message.content === "string")
    .map((message): ConversationMessage => ({
      role: message.role,
      content: message.content.slice(0, 4_000),
      ...(message.role === "assistant" && message.toolExchanges !== undefined
        ? { toolExchanges: message.toolExchanges.slice(-8).map(normalizeHistoricalExchange) }
        : {}),
    }))
    .slice(-12);
}

function normalizeHistoricalExchange(exchange: HistoricalToolExchange): HistoricalToolExchange {
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

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
