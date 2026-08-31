import type { JsonObject, JsonValue, ToolExchange } from "@pumpkinredbean/app-agent-client";

export interface ToolResultImage {
  readonly callId: string;
  readonly toolName: string;
  readonly mediaType: string;
  readonly data: string;
}

export function sanitizeToolOutput(value: JsonValue): JsonValue {
  if (Array.isArray(value)) return value.map(sanitizeToolOutput);
  if (value === null || typeof value !== "object") return value;

  const output: JsonObject = {};
  for (const [key, child] of Object.entries(value)) {
    if (key === "image" && isImage(child)) {
      output.image = { mediaType: child.mediaType, attachedToModel: true };
    } else {
      output[key] = sanitizeToolOutput(child);
    }
  }
  return output;
}

export function sanitizeToolInput(toolName: string, input: JsonObject): JsonObject {
  if (toolName === "mobile_input_text" && typeof input.text === "string") {
    const { text, ...rest } = input;
    return { ...rest, text: "[REDACTED]", characterCount: text.length };
  }
  if (toolName === "mobile_multi_tap" && Array.isArray(input.positions)) {
    const { positions, ...rest } = input;
    return { ...rest, positionCount: positions.length };
  }
  return { ...input };
}

export function collectToolResultImages(exchanges: readonly ToolExchange[], maximum = 2): ToolResultImage[] {
  const images: ToolResultImage[] = [];
  for (const exchange of exchanges) {
    const image = exchange.result.ok
      && isJsonObjectLike(exchange.result.output)
      ? exchange.result.output.image
      : null;
    if (!isImage(image)) continue;
    images.push({
      callId: exchange.call.id,
      toolName: exchange.call.name,
      mediaType: image.mediaType,
      data: image.data,
    });
  }
  return images.slice(-maximum);
}

export function sanitizeToolExchange(exchange: ToolExchange): ToolExchange {
  return {
    call: {
      ...exchange.call,
      input: sanitizeToolInput(exchange.call.name, exchange.call.input),
    },
    result: exchange.result.ok
      ? { ...exchange.result, output: sanitizeToolOutput(exchange.result.output) }
      : { ...exchange.result },
  };
}

function isImage(value: unknown): value is { mediaType: string; data: string } {
  return value !== null
    && typeof value === "object"
    && typeof (value as Record<string, unknown>).mediaType === "string"
    && ((value as Record<string, unknown>).mediaType as string).startsWith("image/")
    && typeof (value as Record<string, unknown>).data === "string"
    && ((value as Record<string, unknown>).data as string).length > 0;
}

function isJsonObjectLike(value: JsonValue): value is JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
