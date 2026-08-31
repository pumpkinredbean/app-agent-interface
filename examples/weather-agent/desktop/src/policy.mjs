import Ajv from "ajv";

const ajv = new Ajv({ allErrors: true, strict: false });

export function authorizeToolCall(toolCall, manifest) {
  if (!toolCall || typeof toolCall !== "object") throw new Error("Provider returned an invalid ToolCall");
  const descriptor = manifest.tools.find((tool) => tool.name === toolCall.name);
  if (!descriptor) throw new Error(`Provider requested unavailable tool: ${toolCall.name}`);
  if (!isPlainObject(toolCall.arguments)) throw new Error(`Tool arguments must be an object: ${toolCall.name}`);

  const validate = ajv.compile(descriptor.inputSchema);
  if (!validate(toolCall.arguments)) {
    const details = ajv.errorsText(validate.errors, { separator: "; " });
    throw new Error(`Provider returned invalid arguments for ${toolCall.name}: ${details}`);
  }
  return descriptor;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
