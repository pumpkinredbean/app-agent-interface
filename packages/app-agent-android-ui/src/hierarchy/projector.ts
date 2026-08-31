import { createHash } from "node:crypto";

import { parseRawNodes } from "./parser.js";
import { controlRole, roleFor, shortClassName } from "./roles.js";
import type {
  Bounds,
  ProjectedControlNode,
  RawUiNode,
  UiCandidate,
  UiContent,
  UiControl,
  UiControlAction,
  UiControlState,
  UiHierarchyOptions,
  UiHierarchyProjection,
} from "./types.js";

const DEFAULT_MAX_NODES = 120;
const DEFAULT_MAX_NODE_TEXT = 560;
const DEFAULT_MAX_TOTAL_TEXT = 12_000;
const MAX_XML_LENGTH = 8 * 1024 * 1024;
const DEFAULT_MAX_RAW_NODES = 20_000;

export function projectUiHierarchy(xml: string, options: UiHierarchyOptions = {}): UiHierarchyProjection {
  if (!xml.includes("<hierarchy")) throw new Error("UiAutomator returned an invalid hierarchy");
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
    .map((node) => toCandidate(node, maxNodeText))
    .filter((candidate): candidate is UiCandidate => candidate !== null);

  const consumedLabels = new Set<number>();
  for (const candidate of candidates) {
    if (!candidate.labelOwner) continue;
    const labels = descendantLabels(candidate.raw, rawNodes, maxNodeText);
    candidate.descendantText = labels.text;
    candidate.descendantDescriptions = labels.descriptions;
    candidate.textTruncated ||= labels.truncated;
    for (const index of labels.consumed) consumedLabels.add(index);
  }

  const deduplicated: UiCandidate[] = [];
  const seen = new Set<string>();
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
  const content = contentCandidates.map(presentContent);
  const nodeMap = new Map<string, ProjectedControlNode>();
  for (const [index, control] of controls.entries()) {
    const candidate = controlCandidates[index];
    if (candidate !== undefined) nodeMap.set(control.nodeId, { ...candidate, presented: control });
  }

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

function toCandidate(node: RawUiNode, maxNodeText: number): UiCandidate | null {
  const role = roleFor(node.className);
  const editable = role === "text_field";
  const actions: UiControlAction[] = [];
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

function descendantLabels(node: RawUiNode, rawNodes: readonly RawUiNode[], maxNodeText: number): {
  text: string[];
  descriptions: string[];
  consumed: number[];
  truncated: boolean;
} {
  const queue = [...node.children];
  const text: string[] = [];
  const descriptions: string[] = [];
  const consumed: number[] = [];
  let truncated = false;
  while (queue.length > 0) {
    const index = queue.shift();
    if (index === undefined) continue;
    const child = rawNodes[index];
    if (child === undefined || !child.included || !contains(node.bounds, child.bounds)) continue;
    const childRole = roleFor(child.className);
    const childActionable = child.clickable
      || child.longClickable
      || child.scrollable
      || child.checkable
      || childRole === "text_field";
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
  return { text: uniqueText(text), descriptions: uniqueText(descriptions), consumed, truncated };
}

function presentControl(candidate: UiCandidate, nodeId: string): UiControl {
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
    ? candidate.description
      || candidate.hint
      || candidate.descendantDescriptions[0]
      || candidate.descendantText[0]
      || ""
    : labels[0] ?? "";
  const label = truncateMiddle(preferredLabel, Math.min(candidate.maxNodeText, 240));
  const details = labels.filter((value) => value !== preferredLabel && (!candidate.editable || value !== candidate.text));
  const detail = truncateMiddle(details.join(" · "), Math.min(candidate.maxNodeText, 360));
  const state: UiControlState = {
    ...(!raw.enabled ? { enabled: false as const } : {}),
    ...(raw.checkable ? { checked: raw.checked } : {}),
    ...(raw.selected ? { selected: true as const } : {}),
    ...(raw.focused ? { focused: true as const } : {}),
    ...(raw.password ? { password: true as const } : {}),
  };
  return {
    nodeId,
    role,
    ...(label.value ? { label: label.value } : {}),
    ...(candidate.editable && candidate.text ? { value: candidate.text } : {}),
    ...(detail.value ? { detail: detail.value } : {}),
    ...(candidate.textTruncated || candidate.descriptionTruncated || label.truncated || detail.truncated
      ? { textTruncated: true as const }
      : {}),
    ...(raw.resourceId ? { resourceId: raw.resourceId } : {}),
    ...(role === "control" && raw.className ? { className: shortClassName(raw.className) } : {}),
    bounds: raw.bounds,
    actions: candidate.actions,
    ...(Object.keys(state).length > 0 ? { state } : {}),
  };
}

function presentContent(candidate: UiCandidate): UiContent {
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
    ...(candidate.textTruncated || candidate.descriptionTruncated ? { textTruncated: true as const } : {}),
    ...(raw.resourceId ? { resourceId: raw.resourceId } : {}),
    bounds: raw.bounds,
    ...(!raw.enabled ? { state: { enabled: false as const } } : {}),
  };
}

function enforceBudget(candidates: readonly UiCandidate[], maxNodes: number, maxTotalText: number): {
  nodes: UiCandidate[];
  omittedCount: number;
} {
  const ranked = [...candidates].sort((a, b) => b.priority - a.priority || readingOrder(a, b));
  let total = 0;
  const output: UiCandidate[] = [];
  for (const candidate of ranked) {
    if (output.length >= maxNodes) break;
    const size = candidateTextSize(candidate);
    if (total + size <= maxTotalText || output.length === 0) {
      output.push(candidate);
      total += size;
    }
  }
  output.sort(readingOrder);
  return { nodes: output, omittedCount: Math.max(0, candidates.length - output.length) };
}

function candidateTextSize(candidate: UiCandidate): number {
  return uniqueText([
    candidate.text,
    candidate.hint,
    candidate.description,
    ...candidate.descendantText,
    ...candidate.descendantDescriptions,
  ]).reduce((total, value) => total + value.length, 0);
}

function fingerprintCandidate(candidate: UiCandidate): object {
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

function candidateKey(candidate: UiCandidate): string {
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

function readingOrder(a: UiCandidate, b: UiCandidate): number {
  const [aLeft, aTop, aRight, aBottom] = a.raw.bounds;
  const [bLeft, bTop, bRight, bBottom] = b.raw.bounds;
  return aTop - bTop || aLeft - bLeft || aBottom - bBottom || aRight - bRight || a.raw.index - b.raw.index;
}

function isVisible(node: RawUiNode, screenWidth?: number, screenHeight?: number): boolean {
  const [left, top, right, bottom] = node.bounds;
  if (!(right > left && bottom > top && right > 0 && bottom > 0)) return false;
  if (screenWidth !== undefined && Number.isFinite(screenWidth) && left >= screenWidth) return false;
  if (screenHeight !== undefined && Number.isFinite(screenHeight) && top >= screenHeight) return false;
  return true;
}

function contains(outer: Bounds, inner: Bounds): boolean {
  return outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3];
}

function truncateMiddle(value: string, maxLength: number): { value: string; truncated: boolean } {
  if (!value || value.length <= maxLength) return { value, truncated: false };
  const edge = Math.max(1, Math.floor((maxLength - 5) / 2));
  return { value: `${value.slice(0, edge)}\n…\n${value.slice(-edge)}`, truncated: true };
}

function maskPassword(value: string): string {
  if (!value) return "";
  return "•".repeat(Math.min(8, Math.max(1, value.length)));
}

function uniqueText(values: readonly string[]): string[] {
  return [...new Set(values.map((value) => value.trim()).filter(Boolean))];
}
