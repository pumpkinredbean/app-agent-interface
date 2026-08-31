export function sanitizeToolOutput(value) {
  if (Array.isArray(value)) return value.map(sanitizeToolOutput);
  if (!value || typeof value !== "object") return value;

  const output = {};
  for (const [key, child] of Object.entries(value)) {
    if (key === "image" && isImage(child)) {
      output.image = { mediaType: child.mediaType, attachedToModel: true };
    } else {
      output[key] = sanitizeToolOutput(child);
    }
  }
  return output;
}

export function sanitizeToolInput(toolName, input) {
  if (!input || typeof input !== "object" || Array.isArray(input)) return input;
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

export function collectToolResultImages(exchanges, maximum = 2) {
  const images = [];
  for (const exchange of exchanges ?? []) {
    const image = exchange?.result?.ok ? exchange.result.output?.image : null;
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

export function sanitizeToolExchange(exchange) {
  if (!exchange?.call || !exchange?.result) return exchange;
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

function isImage(value) {
  return value
    && typeof value === "object"
    && typeof value.mediaType === "string"
    && value.mediaType.startsWith("image/")
    && typeof value.data === "string"
    && value.data.length > 0;
}
