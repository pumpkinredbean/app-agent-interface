import type { Bounds, RawUiNode } from "./types.js";

export function parseRawNodes(xml: string, maxRawNodes: number): RawUiNode[] {
  const output: RawUiNode[] = [];
  const parents: number[] = [];
  const tags = xml.matchAll(/<\/?node\b[^>]*>/g);
  for (const match of tags) {
    const tag = match[0];
    if (tag.startsWith("</")) {
      parents.pop();
      continue;
    }
    if (output.length >= maxRawNodes) {
      throw new Error("UiAutomator hierarchy exceeds the configured node limit");
    }
    const value = parseAttributes(tag);
    const parentIndex = parents.at(-1) ?? null;
    const parent = parentIndex === null ? undefined : output[parentIndex];
    const index = output.length;
    const node: RawUiNode = {
      index,
      parentIndex,
      depth: parents.length,
      children: [],
      text: cleanText(value.text),
      hint: cleanText(value.hint),
      resourceId: cleanText(value["resource-id"]),
      className: cleanText(value.class),
      packageName: cleanText(value.package) || parent?.packageName || "",
      description: cleanText(value["content-desc"]),
      checkable: bool(value.checkable),
      checked: bool(value.checked),
      clickable: bool(value.clickable),
      enabled: value.enabled !== "false",
      focusable: bool(value.focusable),
      focused: bool(value.focused),
      scrollable: bool(value.scrollable),
      longClickable: bool(value["long-clickable"]),
      password: bool(value.password),
      selected: bool(value.selected),
      bounds: parseBounds(value.bounds),
      included: true,
    };
    output.push(node);
    parent?.children.push(index);
    if (!/\/\s*>$/.test(tag)) parents.push(index);
  }
  return output;
}

function parseAttributes(tag: string): Record<string, string> {
  const attributes: Record<string, string> = {};
  const expression = /([A-Za-z_:][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
  for (const match of tag.matchAll(expression)) {
    const name = match[1];
    if (name !== undefined) attributes[name] = match[2] ?? match[3] ?? "";
  }
  return attributes;
}

function parseBounds(value = ""): Bounds {
  const match = value.match(/^\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]$/);
  if (!match) return [0, 0, 0, 0];
  return [number(match[1]), number(match[2]), number(match[3]), number(match[4])];
}

function number(value: string | undefined): number {
  return value === undefined ? 0 : Number(value);
}

function cleanText(value: string | undefined): string {
  if (value === undefined) return "";
  let output = value;
  for (let pass = 0; pass < 2; pass += 1) {
    output = output
      .replace(/&(quot|apos|lt|gt|amp);/g, (_match, entity: string) => decodeNamedEntity(entity))
      .replace(/&#(x?)([0-9a-f]+);/gi, (_match, hexadecimal: string, digits: string) => {
        const codePoint = Number.parseInt(digits, hexadecimal ? 16 : 10);
        return Number.isFinite(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff
          ? String.fromCodePoint(codePoint)
          : "";
      });
  }
  return output.replace(/\r/g, "").trim();
}

function decodeNamedEntity(entity: string): string {
  switch (entity) {
    case "quot": return "\"";
    case "apos": return "'";
    case "lt": return "<";
    case "gt": return ">";
    case "amp": return "&";
    default: return "";
  }
}

function bool(value: string | boolean | undefined): boolean {
  return value === true || value === "true";
}
