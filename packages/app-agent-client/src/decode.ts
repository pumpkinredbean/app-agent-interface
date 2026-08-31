import {
  APP_AGENT_PROTOCOL,
  type JsonObject,
  type JsonValue,
  type ToolError,
  type ToolFailure,
  type ToolResult,
  type ToolsList,
  type ToolSpec,
} from "./contracts.js";

const TOOL_NAME = /^[A-Za-z0-9_-]{1,64}$/;

export class ProtocolDecodeError extends Error {
  public constructor(message: string) {
    super(message);
    this.name = "ProtocolDecodeError";
  }
}

export function decodeJsonObject(value: unknown, label = "value"): JsonObject {
  if (!isJsonObject(value)) throw new ProtocolDecodeError(`${label} must be a JSON object`);
  return value;
}

export function decodeToolsList(value: unknown): ToolsList {
  const body = record(value, "Capability manifest");
  requireProtocolMessage(body, "tools.list");
  if (!Array.isArray(body.tools)) throw new ProtocolDecodeError("Capability manifest tools must be an array");
  return {
    protocol: APP_AGENT_PROTOCOL,
    type: "tools.list",
    tools: body.tools.map((tool, index) => decodeToolSpec(tool, index)),
  };
}

export function decodeToolResult(value: unknown, expectedId?: string): ToolResult {
  const body = record(value, "ToolResult");
  requireProtocolMessage(body, "tool.result");
  const id = nonEmptyString(body.id, "ToolResult id");
  if (expectedId !== undefined && id !== expectedId) {
    throw new ProtocolDecodeError(`ToolResult id ${id} does not match call id ${expectedId}`);
  }
  if (body.ok === true) {
    if (!("output" in body) || !isJsonValue(body.output)) {
      throw new ProtocolDecodeError("Successful ToolResult output must be valid JSON");
    }
    if ("error" in body) throw new ProtocolDecodeError("Successful ToolResult must not contain error");
    return { protocol: APP_AGENT_PROTOCOL, type: "tool.result", id, ok: true, output: body.output };
  }
  if (body.ok === false) {
    if ("output" in body) throw new ProtocolDecodeError("Failed ToolResult must not contain output");
    return { protocol: APP_AGENT_PROTOCOL, type: "tool.result", id, ok: false, error: decodeToolError(body.error) };
  }
  throw new ProtocolDecodeError("ToolResult ok must be a boolean");
}

export function decodeToolFailure(value: unknown): ToolFailure {
  const result = decodeToolResult(value);
  if (result.ok) throw new ProtocolDecodeError("Expected a failed ToolResult");
  return result;
}

export function isJsonValue(value: unknown): value is JsonValue {
  if (value === null || typeof value === "boolean" || typeof value === "string") return true;
  if (typeof value === "number") return Number.isFinite(value);
  if (Array.isArray(value)) return value.every(isJsonValue);
  return isJsonObject(value);
}

export function isJsonObject(value: unknown): value is JsonObject {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value) as object | null;
  if (prototype !== Object.prototype && prototype !== null) return false;
  return Object.values(value).every(isJsonValue);
}

function decodeToolSpec(value: unknown, index: number): ToolSpec {
  const spec = record(value, `ToolSpec at index ${index}`);
  const name = nonEmptyString(spec.name, `ToolSpec at index ${index} name`);
  if (!TOOL_NAME.test(name)) throw new ProtocolDecodeError(`Invalid ToolSpec name: ${name}`);
  const description = nonEmptyString(spec.description, `ToolSpec ${name} description`);
  const inputSchema = decodeJsonObject(spec.inputSchema, `ToolSpec ${name} inputSchema`);
  if (inputSchema.type !== "object") throw new ProtocolDecodeError(`ToolSpec ${name} inputSchema type must be object`);
  return { name, description, inputSchema };
}

function decodeToolError(value: unknown): ToolError {
  const error = record(value, "ToolResult error");
  if (typeof error.retryable !== "boolean") {
    throw new ProtocolDecodeError("ToolResult error retryable must be a boolean");
  }
  return {
    code: nonEmptyString(error.code, "ToolResult error code"),
    message: nonEmptyString(error.message, "ToolResult error message"),
    retryable: error.retryable,
  };
}

function requireProtocolMessage(body: Record<string, unknown>, type: string): void {
  if (body.protocol !== APP_AGENT_PROTOCOL) {
    throw new ProtocolDecodeError(`Unsupported protocol; expected ${APP_AGENT_PROTOCOL}`);
  }
  if (body.type !== type) throw new ProtocolDecodeError(`Expected message type ${type}`);
}

function record(value: unknown, label: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new ProtocolDecodeError(`${label} must be an object`);
  }
  return value as Record<string, unknown>;
}

function nonEmptyString(value: unknown, label: string): string {
  if (typeof value !== "string" || value.length === 0) throw new ProtocolDecodeError(`${label} must be a non-empty string`);
  return value;
}
