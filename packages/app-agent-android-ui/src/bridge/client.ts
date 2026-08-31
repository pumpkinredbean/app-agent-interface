import { isJsonValue, type JsonValue } from "@pumpkinredbean/app-agent-client";

import type {
  UiActionResponse,
  UiAutomatorBridge,
  UiDriverAction,
  UiDriverHealth,
  UiDriverState,
  UiHierarchyResponse,
  UiScreenshotResponse,
} from "./contracts.js";

export type UiAutomatorBridgeErrorCode =
  | "UI_DRIVER_ERROR"
  | "UI_DRIVER_INVALID_RESPONSE"
  | "UI_DRIVER_REQUEST_FAILED"
  | "UI_DRIVER_UNAVAILABLE"
  | string;

export class UiAutomatorBridgeError extends Error {
  public readonly code: UiAutomatorBridgeErrorCode;
  public readonly status: number;
  public readonly body?: JsonValue;

  public constructor(
    message: string,
    options: { code?: UiAutomatorBridgeErrorCode; status?: number; body?: JsonValue; cause?: unknown } = {},
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = "UiAutomatorBridgeError";
    this.code = options.code ?? "UI_DRIVER_ERROR";
    this.status = options.status ?? 0;
    if (options.body !== undefined) this.body = options.body;
  }
}

export interface UiAutomatorBridgeClientOptions {
  readonly baseUrl?: string;
  readonly fetch?: typeof globalThis.fetch;
}

export class UiAutomatorBridgeClient implements UiAutomatorBridge {
  readonly #baseUrl: string;
  readonly #fetch: typeof globalThis.fetch;

  public constructor(options: UiAutomatorBridgeClientOptions = {}) {
    this.#baseUrl = (options.baseUrl ?? "http://127.0.0.1:18766").replace(/\/$/, "");
    this.#fetch = options.fetch ?? globalThis.fetch;
  }

  public async health(): Promise<UiDriverHealth> {
    return decodeHealth(await this.#json("/healthz", {}, 2_000));
  }

  public async hierarchy(): Promise<UiHierarchyResponse> {
    return decodeHierarchy(await this.#json("/v1/hierarchy", {}, 12_000));
  }

  public async screenshot(): Promise<UiScreenshotResponse> {
    return decodeScreenshot(await this.#json("/v1/screenshot", {}, 15_000));
  }

  public async action(action: UiDriverAction): Promise<UiActionResponse> {
    return decodeAction(await this.#json("/v1/actions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(action),
    }, 20_000));
  }

  public async shutdown(): Promise<{ [key: string]: JsonValue }> {
    return jsonObject(await this.#json("/shutdown", { method: "POST" }, 2_000), "Shutdown response");
  }

  async #json(path: string, options: RequestInit, timeoutMs: number): Promise<unknown> {
    let response: Response;
    try {
      response = await this.#fetch(`${this.#baseUrl}${path}`, {
        ...options,
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch (cause) {
      throw new UiAutomatorBridgeError(`Cannot connect to the Android UI driver: ${errorMessage(cause)}`, {
        code: "UI_DRIVER_UNAVAILABLE",
        cause,
      });
    }

    const text = await response.text();
    let body: unknown;
    try {
      body = JSON.parse(text) as unknown;
    } catch (cause) {
      throw new UiAutomatorBridgeError(`Android UI driver returned non-JSON HTTP ${response.status}`, {
        code: "UI_DRIVER_INVALID_RESPONSE",
        status: response.status,
        cause,
      });
    }
    if (!response.ok) {
      throw new UiAutomatorBridgeError(errorField(body, "message") ?? `Android UI driver HTTP ${response.status}`, {
        code: errorField(body, "code") ?? "UI_DRIVER_REQUEST_FAILED",
        status: response.status,
        ...(isJsonValue(body) ? { body } : {}),
      });
    }
    return body;
  }
}

function decodeHealth(value: unknown): UiDriverHealth {
  const body = record(value, "Health response");
  return {
    ...decodeState(body),
    ready: boolean(body.ready, "ready"),
    runtime: string(body.runtime, "runtime"),
  };
}

function decodeHierarchy(value: unknown): UiHierarchyResponse {
  const body = record(value, "Hierarchy response");
  return { ...decodeState(body), xml: string(body.xml, "xml") };
}

function decodeScreenshot(value: unknown): UiScreenshotResponse {
  const body = record(value, "Screenshot response");
  return {
    ...decodeState(body),
    mediaType: string(body.mediaType, "mediaType"),
    data: string(body.data, "data"),
  };
}

function decodeAction(value: unknown): UiActionResponse {
  const body = record(value, "Action response");
  return {
    performed: boolean(body.performed, "performed"),
    ...(typeof body.targetPackage === "string" ? { targetPackage: body.targetPackage } : {}),
    ...(body.currentPackage === null || typeof body.currentPackage === "string"
      ? { currentPackage: body.currentPackage }
      : {}),
    ...(typeof body.requested === "number" ? { requested: body.requested } : {}),
    ...(typeof body.completed === "number" ? { completed: body.completed } : {}),
    ...(typeof body.failedIndex === "number" ? { failedIndex: body.failedIndex } : {}),
  };
}

function decodeState(body: Record<string, unknown>): UiDriverState {
  const currentPackage = body.currentPackage;
  if (currentPackage !== null && typeof currentPackage !== "string") invalid("currentPackage");
  return {
    targetPackage: string(body.targetPackage, "targetPackage"),
    currentPackage,
    width: number(body.width, "width"),
    height: number(body.height, "height"),
    rotation: number(body.rotation, "rotation"),
  };
}

function jsonObject(value: unknown, label: string): { [key: string]: JsonValue } {
  if (!isJsonValue(value) || value === null || Array.isArray(value) || typeof value !== "object") {
    throw new UiAutomatorBridgeError(`${label} must be a JSON object`, { code: "UI_DRIVER_INVALID_RESPONSE" });
  }
  return value;
}

function record(value: unknown, label: string): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new UiAutomatorBridgeError(`${label} must be an object`, { code: "UI_DRIVER_INVALID_RESPONSE" });
  }
  return value as Record<string, unknown>;
}

function string(value: unknown, field: string): string {
  if (typeof value !== "string") invalid(field);
  return value;
}

function number(value: unknown, field: string): number {
  if (typeof value !== "number" || !Number.isFinite(value)) invalid(field);
  return value;
}

function boolean(value: unknown, field: string): boolean {
  if (typeof value !== "boolean") invalid(field);
  return value;
}

function invalid(field: string): never {
  throw new UiAutomatorBridgeError(`Android UI driver field ${field} has an invalid type`, {
    code: "UI_DRIVER_INVALID_RESPONSE",
  });
}

function errorField(value: unknown, field: "code" | "message"): string | undefined {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return undefined;
  const error = (value as Record<string, unknown>).error;
  if (error === null || typeof error !== "object" || Array.isArray(error)) return undefined;
  const output = (error as Record<string, unknown>)[field];
  return typeof output === "string" && output.length > 0 ? output : undefined;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
