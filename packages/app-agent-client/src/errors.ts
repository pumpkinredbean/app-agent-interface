import type { JsonValue, ToolFailure } from "./contracts.js";

export type AppAgentClientErrorCode =
  | "INVALID_RESPONSE"
  | "REQUEST_FAILED"
  | "RUNTIME_UNAVAILABLE";

export class AppAgentClientError extends Error {
  public readonly code: AppAgentClientErrorCode;
  public readonly status: number;
  public readonly body?: JsonValue;

  public constructor(
    message: string,
    options: { code: AppAgentClientErrorCode; status?: number; body?: JsonValue; cause?: unknown },
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = "AppAgentClientError";
    this.code = options.code;
    this.status = options.status ?? 0;
    if (options.body !== undefined) this.body = options.body;
  }
}

export class ToolCallError extends Error {
  public readonly code: string;
  public readonly retryable: boolean;
  public readonly envelope: ToolFailure;

  public constructor(envelope: ToolFailure) {
    super(envelope.error.message);
    this.name = "ToolCallError";
    this.code = envelope.error.code;
    this.retryable = envelope.error.retryable;
    this.envelope = envelope;
  }
}

export function isToolCallError(error: unknown): error is ToolCallError {
  return error instanceof ToolCallError;
}
