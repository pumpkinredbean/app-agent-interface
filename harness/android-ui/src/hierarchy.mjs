import { createHash } from "node:crypto";

const DEFAULT_MAX_NODES = 120;
const DEFAULT_MAX_NODE_TEXT = 560;
const DEFAULT_MAX_TOTAL_TEXT = 12_000;
const MAX_XML_LENGTH = 8 * 1024 * 1024;
const DEFAULT_MAX_RAW_NODES = 20_000;

export function projectUiHierarchy(xml, options = {}) {
  if (typeof xml !== "string" || !xml.includes("<hierarchy")) {
    throw new Error("UiAutomator returned an invalid hierarchy");
  }
  if (xml.length > (options.maxXmlLength ?? MAX_XML_LENGTH)) {
    throw new Error("UiAutomator hierarchy exceeds the configured size limit");
  }

  const rawNodes = parseRawNodes(xml, options.maxRawNodes ?? DEFAULT_MAX_RAW_NODES);
  const allowedPackages = new Set(options.allowedPackages ?? []);
  for (const node of rawNodes) {
    node.included = allowedPackages.size === 0 || allowedPackages.has(node.packageName);
  }

  const maxNodeText = options.maxNodeText ?? DEFAULT_MAX_NODE_TEXT;
  const candidates = rawNodes
    .filter((node) => node.included && isVisible(node, options.screenWidth, options.screenHeight))
    .map((node) => toCandidate(node, rawNodes, maxNodeText))
    .filter(Boolean);

  const consumedLabels = new Set();
  for (const candidate of candidates) {
    if (!candidate.labelOwner) continue;
    const labels = descendantLabels(candidate.raw, rawNodes, maxNodeText);
    candidate.descendantText = labels.text;
    candidate.descendantDescriptions = labels.descriptions;
    candidate.textTruncated ||= labels.truncated;
    for (const index of labels.consumed) consumedLabels.add(index);
  }

  const deduplicated = [];
  const seen = new Set();
  for (const candidate of candidates) {
    if (consumedLabels.has(candidate.raw.index) && !candidate.control) continue;
    const key = candidateKey(candidate);
    if (seen.has(key)) continue;
    seen.add(key);
    deduplicated.push(candidate);
  }

  deduplicated.sort(readingOrder);
  const budget = enforceBudget(
    deduplicated,
    options.maxNodes ?? DEFAULT_MAX_NODES,
    options.maxTotalText ?? DEFAULT_MAX_TOTAL_TEXT,
  );
  const selected = budget.nodes;

  const controlCandidates = selected.filter((candidate) => candidate.control);
  const contentCandidates = selected.filter((candidate) => !candidate.control);
  const controls = controlCandidates.map((candidate, index) => presentControl(candidate, `c${index + 1}`));
  const content = contentCandidates.map((candidate) => presentContent(candidate));
  const nodeMap = new Map(controls.map((control, index) => [control.nodeId, {
    ...controlCandidates[index],
    presented: control,
  }]));

  return {
    content,
    controls,
    nodeMap,
    fingerprint: createHash("sha256")
      .update(JSON.stringify(deduplicated.map(fingerprintCandidate)))
      .digest("hex")
      .slice(0, 20),
    metrics: {
      rawNodeCount: rawNodes.length,
      includedRawNodeCount: rawNodes.filter((node) => node.included).length,
      semanticNodeCount: content.length + controls.length,
      contentCount: content.length,
      controlCount: controls.length,
      omittedNodeCount: Math.max(0, rawNodes.length - content.length - controls.length),
      omittedSemanticNodeCount: budget.omittedCount,
      maxRawDepth: rawNodes.reduce((max, node) => Math.max(max, node.depth), 0),
      truncatedTextNodeCount: selected.filter((node) => node.textTruncated || node.descriptionTruncated).length,
    },
  };
}

function parseRawNodes(xml, maxRawNodes) {
  const output = [];
  const parents = [];
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
    const parentIndex = parents.length > 0 ? parents.at(-1) : null;
    const depth = parents.length;
    const index = output.length;
    const node = {
      index,
      parentIndex,
      depth,
      children: [],
      text: cleanText(value.text),
      hint: cleanText(value.hint),
      resourceId: cleanText(value["resource-id"]),
      className: cleanText(value.class),
      packageName: cleanText(value.package) || (parentIndex === null ? "" : output[parentIndex].packageName),
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
    };
    output.push(node);
    if (parentIndex !== null) output[parentIndex].children.push(index);
    if (!/\/\s*>$/.test(tag)) parents.push(index);
  }
  return output;
}

function parseAttributes(tag) {
  const attributes = {};
  const expression = /([A-Za-z_:][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
  for (const match of tag.matchAll(expression)) attributes[match[1]] = match[2] ?? match[3] ?? "";
  return attributes;
}

function toCandidate(node, rawNodes, maxNodeText) {
  const role = roleFor(node.className);
  const editable = role === "text_field";
  const actions = [];
  if (node.clickable || node.checkable) actions.push("tap");
  if (node.longClickable) actions.push("long_press");
  if (editable) actions.push("set_text");
  if (node.scrollable) actions.push("scroll");
  const control = actions.length > 0;
  const labelOwner = node.clickable || node.longClickable || node.checkable || editable;
  const hasLabel = Boolean(node.text || node.hint || node.description);
  if (!control && !hasLabel) return null;

  const text = truncateMiddle(node.password ? maskPassword(node.text) : node.text, maxNodeText);
  const hint = truncateMiddle(node.hint, Math.min(maxNodeText, 320));
  const description = truncateMiddle(node.description, Math.min(maxNodeText, 320));
  return {
    raw: node,
    role,
    control,
    labelOwner,
    editable,
    actions,
    text: text.value,
    hint: hint.value,
    textTruncated: text.truncated || hint.truncated,
    description: description.value,
    descriptionTruncated: description.truncated,
    descendantText: [],
    descendantDescriptions: [],
    priority: (control ? 20 : 0) + (editable ? 8 : 0) + (hasLabel ? 4 : 0) + (node.focused ? 2 : 0),
    maxNodeText,
  };
}

function descendantLabels(node, rawNodes, maxNodeText) {
  const queue = [...node.children];
  const text = [];
  const descriptions = [];
  const consumed = [];
  let truncated = false;
  while (queue.length > 0) {
    const index = queue.shift();
    const child = rawNodes[index];
    if (!child || !child.included || !contains(node.bounds, child.bounds)) continue;
    const childRole = roleFor(child.className);
    const childActionable = child.clickable || child.longClickable || child.scrollable || child.checkable || childRole === "text_field";
    if (!childActionable) {
      const childText = truncateMiddle(child.password ? maskPassword(child.text) : child.text, maxNodeText);
      const childDescription = truncateMiddle(child.description || child.hint, Math.min(maxNodeText, 320));
      if (childText.value) text.push(childText.value);
      if (childDescription.value) descriptions.push(childDescription.value);
      truncated ||= childText.truncated || childDescription.truncated;
      if (child.text || child.hint || child.description) consumed.push(index);
      queue.push(...child.children);
    }
  }
  return {
    text: uniqueText(text),
    descriptions: uniqueText(descriptions),
    consumed,
    truncated,
  };
}

function presentControl(candidate, nodeId) {
  const raw = candidate.raw;
  const labels = uniqueText([
    candidate.description,
    candidate.hint,
    candidate.text,
    ...candidate.descendantText,
    ...candidate.descendantDescriptions,
  ]);
  const role = controlRole(candidate);
  const preferredLabel = candidate.editable
    ? candidate.description || candidate.hint || candidate.descendantDescriptions[0] || candidate.descendantText[0]
    : labels[0];
  const label = truncateMiddle(preferredLabel, Math.min(candidate.maxNodeText, 240));
  const details = labels.filter((value) => value !== preferredLabel && (!candidate.editable || value !== candidate.text));
  const detail = truncateMiddle(details.join(" · "), Math.min(candidate.maxNodeText, 360));
  const result = {
    nodeId,
    role,
    ...(label.value ? { label: label.value } : {}),
    ...(candidate.editable && candidate.text ? { value: candidate.text } : {}),
    ...(detail.value ? { detail: detail.value } : {}),
    ...(candidate.textTruncated || candidate.descriptionTruncated || label.truncated || detail.truncated
      ? { textTruncated: true }
      : {}),
    ...(raw.resourceId ? { resourceId: raw.resourceId } : {}),
    ...(role === "control" && raw.className ? { className: shortClassName(raw.className) } : {}),
    bounds: raw.bounds,
    actions: candidate.actions,
  };
  const state = {
    ...(!raw.enabled ? { enabled: false } : {}),
    ...(raw.checkable ? { checked: raw.checked } : {}),
    ...(raw.selected ? { selected: true } : {}),
    ...(raw.focused ? { focused: true } : {}),
    ...(raw.password ? { password: true } : {}),
  };
  if (Object.keys(state).length > 0) result.state = state;
  return result;
}

function presentContent(candidate) {
  const raw = candidate.raw;
  return {
    role: candidate.role === "view" ? "text" : candidate.role,
    ...(candidate.text ? { text: candidate.text } : {}),
    ...(!candidate.text && (candidate.description || candidate.hint)
      ? { text: candidate.description || candidate.hint }
      : {}),
    ...(candidate.text && candidate.description && candidate.text !== candidate.description
      ? { contentDescription: candidate.description }
      : {}),
    ...(candidate.textTruncated || candidate.descriptionTruncated ? { textTruncated: true } : {}),
    ...(raw.resourceId ? { resourceId: raw.resourceId } : {}),
    bounds: raw.bounds,
    ...(!raw.enabled ? { state: { enabled: false } } : {}),
  };
}

function enforceBudget(candidates, maxNodes, maxTotalText) {
  const ranked = [...candidates].sort((a, b) => b.priority - a.priority || readingOrder(a, b));
  let total = 0;
  const output = [];
  for (const candidate of ranked) {
    if (output.length >= maxNodes) break;
    const size = candidateTextSize(candidate);
    if (total + size <= maxTotalText || output.length === 0) {
      output.push(candidate);
      total += size;
    }
  }
  output.sort(readingOrder);
  return {
    nodes: output,
    omittedCount: Math.max(0, candidates.length - output.length),
  };
}

function candidateTextSize(candidate) {
  return uniqueText([
    candidate.text,
    candidate.hint,
    candidate.description,
    ...candidate.descendantText,
    ...candidate.descendantDescriptions,
  ]).reduce((total, value) => total + value.length, 0);
}

function fingerprintCandidate(candidate) {
  const raw = candidate.raw;
  return {
    bounds: raw.bounds,
    packageName: raw.packageName,
    resourceId: raw.resourceId,
    className: raw.className,
    role: candidate.role,
    actions: candidate.actions,
    text: candidate.text,
    hint: candidate.hint,
    description: candidate.description,
    descendantText: candidate.descendantText,
    descendantDescriptions: candidate.descendantDescriptions,
    enabled: raw.enabled,
    clickable: raw.clickable,
    longClickable: raw.longClickable,
    scrollable: raw.scrollable,
    checkable: raw.checkable,
    checked: raw.checked,
    selected: raw.selected,
    focused: raw.focused,
    password: raw.password,
  };
}

function controlRole(candidate) {
  if (candidate.role === "text_field") return "text_field";
  if (["checkbox", "radio", "switch", "select", "slider", "button", "image_button"].includes(candidate.role)) {
    return candidate.role;
  }
  if (candidate.raw.checkable) return "checkbox";
  if (candidate.raw.scrollable && !candidate.raw.clickable && !candidate.raw.checkable) return "scroll_area";
  if (candidate.raw.clickable || candidate.raw.checkable) return "button";
  return "control";
}

function candidateKey(candidate) {
  const raw = candidate.raw;
  return [
    raw.bounds.join(","),
    candidate.role,
    candidate.text,
    candidate.hint,
    candidate.description,
    raw.resourceId,
    candidate.actions.join(","),
  ].join("|");
}

function readingOrder(a, b) {
  const [aLeft, aTop, aRight, aBottom] = a.raw.bounds;
  const [bLeft, bTop, bRight, bBottom] = b.raw.bounds;
  return aTop - bTop || aLeft - bLeft || aBottom - bBottom || aRight - bRight || a.raw.index - b.raw.index;
}

function roleFor(className) {
  const name = shortClassName(className);
  if (/EditText|TextInputEditText/.test(name)) return "text_field";
  if (/ImageButton/.test(name)) return "image_button";
  if (/Button/.test(name)) return "button";
  if (/CheckBox/.test(name)) return "checkbox";
  if (/RadioButton/.test(name)) return "radio";
  if (/Switch|ToggleButton/.test(name)) return "switch";
  if (/Spinner/.test(name)) return "select";
  if (/SeekBar/.test(name)) return "slider";
  if (/RecyclerView|ListView/.test(name)) return "list";
  if (/ScrollView/.test(name)) return "scroll_view";
  if (/WebView/.test(name)) return "web_view";
  if (/TextView/.test(name)) return "text";
  if (/ImageView/.test(name)) return "image";
  return "view";
}

function parseBounds(value = "") {
  const match = String(value).match(/^\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]$/);
  if (!match) return [0, 0, 0, 0];
  return match.slice(1).map(Number);
}

function isVisible(node, screenWidth, screenHeight) {
  const [left, top, right, bottom] = node.bounds;
  if (!(right > left && bottom > top && right > 0 && bottom > 0)) return false;
  if (Number.isFinite(screenWidth) && left >= screenWidth) return false;
  if (Number.isFinite(screenHeight) && top >= screenHeight) return false;
  return true;
}

function contains(outer, inner) {
  return outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3];
}

function truncateMiddle(value, maxLength) {
  if (!value || value.length <= maxLength) return { value, truncated: false };
  const edge = Math.max(1, Math.floor((maxLength - 5) / 2));
  return {
    value: `${value.slice(0, edge)}\n…\n${value.slice(-edge)}`,
    truncated: true,
  };
}

function maskPassword(value) {
  if (!value) return "";
  return "•".repeat(Math.min(8, Math.max(1, value.length)));
}

function shortClassName(value = "") {
  return value.split(".").at(-1) ?? value;
}

function cleanText(value) {
  if (value === undefined || value === null) return "";
  let output = String(value);
  for (let pass = 0; pass < 2; pass += 1) {
    output = output
      .replace(/&(quot|apos|lt|gt|amp);/g, (_, entity) => ({
        quot: '"',
        apos: "'",
        lt: "<",
        gt: ">",
        amp: "&",
      })[entity])
      .replace(/&#(x?)([0-9a-f]+);/gi, (_, hexadecimal, digits) => {
        const codePoint = Number.parseInt(digits, hexadecimal ? 16 : 10);
        return Number.isFinite(codePoint) && codePoint >= 0 && codePoint <= 0x10ffff
          ? String.fromCodePoint(codePoint)
          : "";
      });
  }
  return output.replace(/\r/g, "").trim();
}

function bool(value) {
  return value === true || value === "true";
}

function uniqueText(values) {
  return [...new Set(values.map((value) => value.trim()).filter(Boolean))];
}
