import { isJsonObject, type ToolSpec, type ToolsList } from "@pumpkinredbean/app-agent-client";
import { Ajv, type AnySchema } from "ajv";

import type { RequestedToolCall } from "./contracts.js";

const ajv = new Ajv({ allErrors: true, strict: false });

export function authorizeToolCall(toolCall: RequestedToolCall, manifest: ToolsList): ToolSpec {
  if (!toolCall || typeof toolCall !== "object") throw new Error("Provider returned an invalid ToolCall");
  const descriptor = manifest.tools.find((tool) => tool.name === toolCall.name);
  if (!descriptor) throw new Error(`Provider requested unavailable tool: ${toolCall.name}`);
  if (!isJsonObject(toolCall.arguments)) throw new Error(`Tool arguments must be an object: ${toolCall.name}`);

  const validate = ajv.compile(descriptor.inputSchema as AnySchema);
  if (!validate(toolCall.arguments)) {
    const details = ajv.errorsText(validate.errors, { separator: "; " });
    throw new Error(`Provider returned invalid arguments for ${toolCall.name}: ${details}`);
  }
  return descriptor;
}
