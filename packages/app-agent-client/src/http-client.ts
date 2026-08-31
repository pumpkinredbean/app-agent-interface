import {
  APP_AGENT_PROTOCOL,
  type AppAgentClientOptions,
  type JsonObject,
  type JsonValue,
  type ToolCall,
  type ToolCallOptions,
  type SuccessfulToolExchange,
  type ToolsList,
} from "./contracts.js";
import { decodeJsonObject, decodeToolResult, decodeToolsList, isJsonValue, ProtocolDecodeError } from "./decode.js";
import { AppAgentClientError, ToolCallError } from "./errors.js";

const DEFAULT_BASE_URL = "http://127.0.0.1:18765";
const DEFAULT_REQUEST_TIMEOUT_MS = 12_000;
const DEFAULT_TOOL_CALL_TIMEOUT_MS = 75_000;

export class AppAgentClient {
  readonly #baseUrl: string;
  readonly #fetch: typeof globalThis.fetch;
  readonly #headers: Readonly<Record<string, string>>;
  readonly #idFactory: () => string;
  readonly #requestTimeoutMs: number;
  readonly #toolCallTimeoutMs: number;

  public constructor(options: AppAgentClientOptions = {}) {
    this.#baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/$/, "");
    this.#fetch = options.fetch ?? globalThis.fetch;
    this.#headers = options.headers ?? {};
    this.#idFactory = options.idFactory ?? (() => globalThis.crypto.randomUUID());
    this.#requestTimeoutMs = positiveTimeout(options.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS);
    this.#toolCallTimeoutMs = positiveTimeout(options.toolCallTimeoutMs ?? DEFAULT_TOOL_CALL_TIMEOUT_MS);
  }

  public async health(): Promise<JsonObject> {
    return decodeJsonObject(await this.#json("/healthz", {}, this.#requestTimeoutMs), "Health response");
  }

  public async capabilities(): Promise<ToolsList> {
    return decodeToolsList(await this.#json("/v1/tools", {}, this.#requestTimeoutMs));
  }

  public async call<TOutput = JsonValue>(
    name: string,
    input: JsonObject = {},
    options: ToolCallOptions = {},
  ): Promise<SuccessfulToolExchange<TOutput>> {
    if (name.length === 0) throw new TypeError("Tool name must not be empty");
    const id = this.#idFactory();
    if (id.length === 0) throw new TypeError("Generated ToolCall id must not be empty");
    const call: ToolCall = { protocol: APP_AGENT_PROTOCOL, type: "tool.call", id, name, input };
    options.onDispatch?.(call);

    const body = await this.#json(
      "/v1/tool-calls",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(call),
      },
      positiveTimeout(options.timeoutMs ?? this.#toolCallTimeoutMs),
    );
    const result = decodeToolResult(body, id);
    if (!result.ok) throw new ToolCallError(result);
    return { call, result: result as SuccessfulToolExchange<TOutput>["result"] };
  }

  async #json(path: string, init: RequestInit, timeoutMs: number): Promise<unknown> {
    let response: Response;
    try {
      response = await this.#fetch(`${this.#baseUrl}${path}`, {
        ...init,
        headers: { ...this.#headers, ...headersRecord(init.headers) },
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (cause) {
      throw new AppAgentClientError(`Cannot connect to the App Agent runtime: ${errorMessage(cause)}`, {
        code: "RUNTIME_UNAVAILABLE",
        cause,
      });
    }

    const text = await response.text();
    let body: unknown;
    try {
      body = JSON.parse(text) as unknown;
    } catch (cause) {
      throw new AppAgentClientError(`App Agent runtime returned non-JSON HTTP ${response.status}`, {
        code: "INVALID_RESPONSE",
        status: response.status,
        cause,
      });
    }
    if (!response.ok) {
      const message = protocolErrorMessage(body) ?? `App Agent runtime HTTP ${response.status}`;
      throw new AppAgentClientError(message, {
        code: "REQUEST_FAILED",
        status: response.status,
        ...(isJsonValue(body) ? { body } : {}),
      });
    }
    return body;
  }
}

function headersRecord(headers: HeadersInit | undefined): Record<string, string> {
  if (headers === undefined) return {};
  return Object.fromEntries(new Headers(headers).entries());
}

function protocolErrorMessage(value: unknown): string | undefined {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return undefined;
  const error = (value as Record<string, unknown>).error;
  if (error === null || typeof error !== "object" || Array.isArray(error)) return undefined;
  const message = (error as Record<string, unknown>).message;
  return typeof message === "string" && message.length > 0 ? message : undefined;
}

function positiveTimeout(value: number): number {
  if (!Number.isFinite(value) || value <= 0) throw new RangeError("Timeout must be a positive finite number");
  return value;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

export { ProtocolDecodeError };
