export const APP_AGENT_PROTOCOL = "app-agent/1" as const;

export type JsonPrimitive = boolean | number | string | null;
export type JsonValue = JsonPrimitive | JsonValue[] | JsonObject;
export type JsonObject = { [key: string]: JsonValue };

export interface ToolSpec {
  readonly name: string;
  readonly description: string;
  readonly inputSchema: JsonObject;
}

export interface ToolsList {
  readonly protocol: typeof APP_AGENT_PROTOCOL;
  readonly type: "tools.list";
  readonly tools: readonly ToolSpec[];
}

export interface ToolCall {
  readonly protocol: typeof APP_AGENT_PROTOCOL;
  readonly type: "tool.call";
  readonly id: string;
  readonly name: string;
  readonly input: JsonObject;
}

export interface ToolError {
  readonly code: string;
  readonly message: string;
  readonly retryable: boolean;
}

export interface ToolSuccess<TOutput = JsonValue> {
  readonly protocol: typeof APP_AGENT_PROTOCOL;
  readonly type: "tool.result";
  readonly id: string;
  readonly ok: true;
  readonly output: TOutput;
}

export interface ToolFailure {
  readonly protocol: typeof APP_AGENT_PROTOCOL;
  readonly type: "tool.result";
  readonly id: string;
  readonly ok: false;
  readonly error: ToolError;
}

export type ToolResult<TOutput = JsonValue> = ToolSuccess<TOutput> | ToolFailure;

export interface ToolExchange<TOutput = JsonValue> {
  readonly call: ToolCall;
  readonly result: ToolResult<TOutput>;
}

export interface SuccessfulToolExchange<TOutput = JsonValue> {
  readonly call: ToolCall;
  readonly result: ToolSuccess<TOutput>;
}

export interface ProtocolError {
  readonly protocol: typeof APP_AGENT_PROTOCOL;
  readonly type: "protocol.error";
  readonly error: ToolError;
}

export interface ToolCallOptions {
  readonly onDispatch?: (call: ToolCall) => void;
  readonly timeoutMs?: number;
}

export interface AppAgentClientOptions {
  readonly baseUrl?: string;
  readonly fetch?: typeof globalThis.fetch;
  readonly headers?: Readonly<Record<string, string>>;
  readonly idFactory?: () => string;
  readonly requestTimeoutMs?: number;
  readonly toolCallTimeoutMs?: number;
}
