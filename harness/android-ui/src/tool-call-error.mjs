export class ToolCallError extends Error {
  constructor(envelope) {
    super(envelope?.error?.message ?? "ToolCall failed");
    this.name = "ToolCallError";
    this.code = envelope?.error?.code ?? "UNKNOWN_TOOL_ERROR";
    this.retryable = Boolean(envelope?.error?.retryable);
    this.envelope = envelope;
  }
}

export function isToolCallError(error) {
  return error instanceof ToolCallError
    || (error?.envelope?.type === "tool.result" && error.envelope?.ok === false);
}
