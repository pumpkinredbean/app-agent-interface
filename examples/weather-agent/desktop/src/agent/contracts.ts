import type {
  JsonObject,
  JsonValue,
  SuccessfulToolExchange,
  ToolCall,
  ToolCallOptions,
  ToolExchange,
  ToolFailure,
  ToolSuccess,
  ToolsList,
} from "@pumpkinredbean/app-agent-client";

export interface RequestedToolCall {
  readonly name: string;
  readonly arguments: JsonObject;
}

export type AgentAction =
  | { readonly type: "final"; readonly text: string }
  | { readonly type: "tool_calls"; readonly text: string; readonly toolCalls: readonly RequestedToolCall[] };

export interface ConversationMessage {
  readonly role: "assistant" | "user";
  readonly content: string;
  readonly toolExchanges?: readonly HistoricalToolExchange[];
}

export interface HistoricalToolExchange {
  readonly call: { readonly name: string; readonly input: JsonObject };
  readonly result:
    | { readonly ok: true; readonly output: JsonValue }
    | { readonly ok: false; readonly error: ToolFailure["error"] };
}

export interface AgentProviderContext {
  readonly userMessage: string;
  readonly manifest: ToolsList;
  readonly conversationHistory: readonly ConversationMessage[];
  toolExchanges: ToolExchange[];
  readonly assistantMessages: readonly string[];
  readonly step: number;
}

export interface AgentProvider {
  readonly kind: string;
  next(context: AgentProviderContext): Promise<AgentAction>;
  close?(): Promise<void> | void;
}

export interface AgentToolClient {
  capabilities(): Promise<ToolsList>;
  call(name: string, input: JsonObject, options?: ToolCallOptions): Promise<SuccessfulToolExchange>;
}

export type AgentEvent =
  | { readonly type: "agent.started"; readonly at: number; readonly userMessage: string; readonly provider: string }
  | {
      readonly type: "model.started";
      readonly at: number;
      readonly provider: string;
      readonly step: number;
      readonly toolCallCount: number;
    }
  | { readonly type: "assistant.message"; readonly at: number; readonly text: string; readonly step: number }
  | { readonly type: "tool.dispatched"; readonly at: number; readonly call: ToolCall; readonly step: number }
  | {
      readonly type: "tool.completed";
      readonly at: number;
      readonly call: ToolCall;
      readonly result: ToolSuccess;
      readonly durationMs: number;
      readonly step: number;
    }
  | {
      readonly type: "tool.failed";
      readonly at: number;
      readonly call: ToolCall;
      readonly result: ToolFailure;
      readonly durationMs: number;
      readonly step: number;
    }
  | {
      readonly type: "agent.completed";
      readonly at: number;
      readonly answer: string;
      readonly durationMs: number;
      readonly modelStepCount: number;
      readonly toolCallCount: number;
    }
  | { readonly type: "agent.failed"; readonly at: number; readonly message: string; readonly durationMs: number };

export interface Attribution {
  readonly text: string;
  readonly url: string;
  readonly license?: string;
}

export interface AgentTrace {
  readonly agent: string;
  readonly provider: string;
  readonly manifest: { readonly protocol: string; readonly availableTools: readonly string[] };
  readonly modelStepCount: number;
  readonly toolCallCount: number;
  readonly toolExchanges: readonly ToolExchange[];
  readonly assistantMessages: readonly string[];
  readonly attributions: readonly Attribution[];
  readonly answer: string;
}

export interface RunAgentOptions {
  readonly baseUrl?: string;
  readonly client?: AgentToolClient;
  readonly cwd?: string;
  readonly history?: readonly ConversationMessage[];
  readonly maxModelSteps?: number;
  readonly maxToolCalls?: number;
  readonly model?: string;
  readonly onEvent?: (event: AgentEvent) => void;
  readonly provider?: AgentProvider;
  readonly providerName?: string;
  readonly sessionId?: string;
  readonly uiBaseUrl?: string;
  readonly uiEnabled?: boolean;
}
