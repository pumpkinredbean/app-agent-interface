import {
  APP_AGENT_PROTOCOL,
  ToolCallError,
  type JsonObject,
  type ToolCall,
  type ToolCallOptions,
  type ToolFailure,
  type ToolSuccess,
  type ToolsList,
} from "@pumpkinredbean/app-agent-client";

import { UiAutomatorBridgeClient, UiAutomatorBridgeError } from "../bridge/client.js";
import type { UiActionResponse, UiHierarchyResponse } from "../bridge/contracts.js";
import { AndroidUiToolError, type AndroidUiToolErrorCode } from "../errors.js";
import { projectUiHierarchy } from "../hierarchy/projector.js";
import type { ProjectedControlNode, UiControlAction, UiHierarchyProjection } from "../hierarchy/types.js";
import { ANDROID_UI_TOOL_SPECS } from "./specs.js";
import type {
  AndroidUiToolClientOptions,
  AndroidUiToolExchange,
  AndroidUiToolInputMap,
  AndroidUiToolName,
  AndroidUiToolOutputMap,
  MobileInputTextInput,
  MobileLongPressInput,
  MobileMultiTapInput,
  MobilePressInput,
  MobileScreenshotInput,
  MobileSwipeInput,
  MobileTapInput,
  MobileWaitInput,
  ObserveScope,
  StoredUiSnapshot,
  UiActionOutput,
  UiObservation,
  UiScreenshot,
} from "./types.js";

const MAX_SNAPSHOTS = 12;

type AndroidUiOutput = AndroidUiToolOutputMap[AndroidUiToolName];

export class AndroidUiToolClient {
  public readonly toolNames: ReadonlySet<AndroidUiToolName>;
  readonly #bridge;
  readonly #extraAllowedPackages: readonly string[];
  readonly #idFactory: () => string;
  readonly #snapshots = new Map<string, StoredUiSnapshot>();

  public constructor(options: AndroidUiToolClientOptions = {}) {
    this.#bridge = options.bridge ?? new UiAutomatorBridgeClient({
      ...(options.baseUrl === undefined ? {} : { baseUrl: options.baseUrl }),
      ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
    });
    this.#extraAllowedPackages = options.allowedPackages ?? [];
    this.#idFactory = options.idFactory ?? (() => globalThis.crypto.randomUUID());
    this.toolNames = new Set(ANDROID_UI_TOOL_SPECS.map((tool) => tool.name as AndroidUiToolName));
  }

  public health() {
    return this.#bridge.health();
  }

  public async capabilities(): Promise<ToolsList> {
    const health = await this.health();
    if (!health.ready) throw new Error("Android UI driver is not ready");
    return { protocol: APP_AGENT_PROTOCOL, type: "tools.list", tools: ANDROID_UI_TOOL_SPECS };
  }

  public handles(name: string): name is AndroidUiToolName {
    return this.toolNames.has(name as AndroidUiToolName);
  }

  public async call<Name extends AndroidUiToolName>(
    name: Name,
    input: AndroidUiToolInputMap[Name],
    options: ToolCallOptions = {},
  ): Promise<AndroidUiToolExchange<Name>> {
    if (!this.handles(name)) throw new AndroidUiToolError("UI_TOOL_NOT_FOUND", `Unknown Android UI tool: ${name}`);
    const call: ToolCall = {
      protocol: APP_AGENT_PROTOCOL,
      type: "tool.call",
      id: this.#idFactory(),
      name,
      input: input as JsonObject,
    };
    options.onDispatch?.(call);

    try {
      const output = await this.#execute(name, input);
      const result: ToolSuccess<AndroidUiToolOutputMap[Name]> = {
        protocol: APP_AGENT_PROTOCOL,
        type: "tool.result",
        id: call.id,
        ok: true,
        output: output as AndroidUiToolOutputMap[Name],
      };
      return { call, result };
    } catch (error) {
      const code = toolErrorCode(error);
      const envelope: ToolFailure = {
        protocol: APP_AGENT_PROTOCOL,
        type: "tool.result",
        id: call.id,
        ok: false,
        error: {
          code,
          message: errorMessage(error),
          retryable: code === "UI_DRIVER_UNAVAILABLE" || code === "STALE_UI_SNAPSHOT",
        },
      };
      throw new ToolCallError(envelope);
    }
  }

  async #execute<Name extends AndroidUiToolName>(
    name: Name,
    input: AndroidUiToolInputMap[Name],
  ): Promise<AndroidUiOutput> {
    switch (name) {
      case "mobile_observe": {
        const value = input as AndroidUiToolInputMap["mobile_observe"];
        return this.#observe(value.scope ?? "all");
      }
      case "mobile_screenshot": return this.#screenshot((input as MobileScreenshotInput).snapshotId);
      case "mobile_tap": return this.#tap(input as MobileTapInput);
      case "mobile_long_press": return this.#longPress(input as MobileLongPressInput);
      case "mobile_multi_tap": return this.#multiTap(input as MobileMultiTapInput);
      case "mobile_input_text": return this.#inputText(input as MobileInputTextInput);
      case "mobile_swipe": return this.#swipe(input as MobileSwipeInput);
      case "mobile_press": return this.#press(input as MobilePressInput);
      case "mobile_wait": return this.#wait(input as MobileWaitInput);
      default: throw new AndroidUiToolError("UI_TOOL_NOT_FOUND", `Unknown Android UI tool: ${String(name)}`);
    }
  }

  async #observe(scope: ObserveScope): Promise<UiObservation> {
    const state = await this.#bridge.hierarchy();
    const projection = this.#project(state);
    const snapshot = this.#saveSnapshot(state, projection);
    return presentSnapshot(snapshot, scope);
  }

  async #screenshot(snapshotId: string): Promise<UiScreenshot> {
    const snapshot = await this.#requireCurrentSnapshot(snapshotId);
    const image = await this.#bridge.screenshot();
    if (image.currentPackage !== snapshot.packageName) {
      throw new AndroidUiToolError(
        "OUTSIDE_TARGET_APP",
        `The foreground package changed to ${image.currentPackage || "unknown"}`,
      );
    }
    return {
      snapshotId,
      screen: { width: image.width, height: image.height, rotation: image.rotation },
      image: { mediaType: image.mediaType, data: image.data },
    };
  }

  async #tap(input: MobileTapInput): Promise<UiActionOutput> {
    const snapshot = await this.#requireCurrentSnapshot(input.snapshotId);
    const [x, y] = "nodeId" in input
      ? center(requireControl(snapshot, input.nodeId, "tap").raw.bounds)
      : [input.x, input.y];
    return this.#afterAction(await this.#bridge.action({ action: "tap", x, y }));
  }

  async #longPress(input: MobileLongPressInput): Promise<UiActionOutput> {
    const snapshot = await this.#requireCurrentSnapshot(input.snapshotId);
    const [x, y] = "nodeId" in input
      ? center(requireControl(snapshot, input.nodeId, "long_press").raw.bounds)
      : [input.x, input.y];
    return this.#afterAction(await this.#bridge.action({
      action: "long_press",
      x,
      y,
      durationMs: input.durationMs ?? 650,
    }));
  }

  async #multiTap(input: MobileMultiTapInput): Promise<UiActionOutput> {
    await this.#requireCurrentSnapshot(input.snapshotId);
    const action = await this.#bridge.action({
      action: "multi_tap",
      positions: input.positions,
      intervalMs: input.intervalMs ?? 80,
    });
    return this.#afterAction(action, {
      requested: input.positions.length,
      completed: action.completed ?? input.positions.length,
    });
  }

  async #inputText(input: MobileInputTextInput): Promise<UiActionOutput> {
    const snapshot = await this.#requireCurrentSnapshot(input.snapshotId);
    const node = requireControl(snapshot, input.nodeId, "set_text");
    const [x, y] = center(node.raw.bounds);
    const action = await this.#bridge.action({ action: "input_text", x, y, text: input.text });
    return this.#afterAction(action, { characterCount: input.text.length });
  }

  async #swipe(input: MobileSwipeInput): Promise<UiActionOutput> {
    await this.#requireCurrentSnapshot(input.snapshotId);
    const action = await this.#bridge.action({
      action: "swipe",
      startX: input.start.x,
      startY: input.start.y,
      endX: input.end.x,
      endY: input.end.y,
      durationMs: input.durationMs ?? 350,
    });
    return this.#afterAction(action);
  }

  async #press(input: MobilePressInput): Promise<UiActionOutput> {
    await this.#requireCurrentSnapshot(input.snapshotId);
    const action = await this.#bridge.action({ action: "press", key: input.key });
    return this.#afterAction(action, { key: input.key });
  }

  async #wait(input: MobileWaitInput): Promise<UiObservation> {
    this.#snapshot(input.snapshotId);
    const timeoutMs = input.timeoutMs ?? 5_000;
    const deadline = Date.now() + timeoutMs;
    const query = input.query.toLocaleLowerCase();
    while (true) {
      const output = await this.#observe("all");
      if (snapshotMatches(output, query)) return output;
      if (Date.now() >= deadline) {
        throw new AndroidUiToolError("UI_WAIT_TIMEOUT", `No UI content or control matched: ${input.query}`);
      }
      await delay(Math.min(350, Math.max(1, deadline - Date.now())));
    }
  }

  async #afterAction(
    action: UiActionResponse,
    detail: Omit<UiActionOutput, "performed" | "after"> = {},
  ): Promise<UiActionOutput> {
    const after = await this.#observe("all");
    return { performed: action.performed, ...detail, after };
  }

  async #requireCurrentSnapshot(snapshotId: string): Promise<StoredUiSnapshot> {
    const expected = this.#snapshot(snapshotId);
    const state = await this.#bridge.hierarchy();
    const projection = this.#project(state);
    if (projection.fingerprint !== expected.fingerprint) {
      throw new AndroidUiToolError(
        "STALE_UI_SNAPSHOT",
        "The Android screen changed; call mobile_observe again before acting",
      );
    }
    return expected;
  }

  #project(state: UiHierarchyResponse): UiHierarchyProjection {
    if (!state.targetPackage || state.currentPackage !== state.targetPackage) {
      throw new AndroidUiToolError(
        "OUTSIDE_TARGET_APP",
        `Expected foreground package ${state.targetPackage || "unknown"} but found ${state.currentPackage || "unknown"}`,
      );
    }
    return projectUiHierarchy(state.xml, {
      allowedPackages: [state.targetPackage, ...this.#extraAllowedPackages],
      screenWidth: state.width,
      screenHeight: state.height,
    });
  }

  #saveSnapshot(state: UiHierarchyResponse, projection: UiHierarchyProjection): StoredUiSnapshot {
    const packageName = state.currentPackage;
    if (packageName === null) throw new AndroidUiToolError("OUTSIDE_TARGET_APP", "The foreground package is unknown");
    const snapshot: StoredUiSnapshot = {
      id: `ui-${this.#idFactory()}`,
      createdAt: Date.now(),
      packageName,
      targetPackage: state.targetPackage,
      width: state.width,
      height: state.height,
      rotation: state.rotation,
      fingerprint: projection.fingerprint,
      content: projection.content,
      controls: projection.controls,
      nodeMap: projection.nodeMap,
      metrics: projection.metrics,
    };
    this.#snapshots.set(snapshot.id, snapshot);
    while (this.#snapshots.size > MAX_SNAPSHOTS) {
      const oldest = this.#snapshots.keys().next().value as string | undefined;
      if (oldest === undefined) break;
      this.#snapshots.delete(oldest);
    }
    return snapshot;
  }

  #snapshot(snapshotId: string): StoredUiSnapshot {
    const snapshot = this.#snapshots.get(snapshotId);
    if (snapshot === undefined) {
      throw new AndroidUiToolError(
        "UI_SNAPSHOT_NOT_FOUND",
        "The UI snapshot is unknown or expired; call mobile_observe again",
      );
    }
    return snapshot;
  }
}

function presentSnapshot(snapshot: StoredUiSnapshot, scope: ObserveScope): UiObservation {
  return {
    snapshotId: snapshot.id,
    packageName: snapshot.packageName,
    screen: { width: snapshot.width, height: snapshot.height, rotation: snapshot.rotation },
    ...(scope !== "controls" ? { content: snapshot.content } : {}),
    ...(scope !== "content" ? { controls: snapshot.controls } : {}),
    ...(snapshot.metrics.truncatedTextNodeCount > 0 || snapshot.metrics.omittedSemanticNodeCount > 0
      ? { truncated: true as const }
      : {}),
  };
}

function requireControl(
  snapshot: StoredUiSnapshot,
  nodeId: string,
  action: UiControlAction,
): ProjectedControlNode {
  const node = snapshot.nodeMap.get(nodeId);
  if (node === undefined) {
    throw new AndroidUiToolError(
      "UI_CONTROL_NOT_FOUND",
      `Control ${nodeId} does not exist in snapshot ${snapshot.id}`,
    );
  }
  if (!node.actions.includes(action)) {
    throw new AndroidUiToolError("UI_ACTION_UNSUPPORTED", `Control ${nodeId} does not support ${action}`);
  }
  if (!node.raw.enabled) throw new AndroidUiToolError("UI_CONTROL_DISABLED", `Control ${nodeId} is disabled`);
  return node;
}

function snapshotMatches(snapshot: UiObservation, query: string): boolean {
  const values = [
    ...(snapshot.content ?? []).flatMap((node) => [node.text, node.contentDescription, node.resourceId]),
    ...(snapshot.controls ?? []).flatMap((node) => [node.label, node.detail, node.value, node.resourceId, node.role]),
  ];
  return values.some((value) => typeof value === "string" && value.toLocaleLowerCase().includes(query));
}

function center(bounds: readonly [number, number, number, number]): [number, number] {
  const [left, top, right, bottom] = bounds;
  return [Math.floor((left + right) / 2), Math.floor((top + bottom) / 2)];
}

function toolErrorCode(error: unknown): AndroidUiToolErrorCode | string {
  if (error instanceof AndroidUiToolError || error instanceof UiAutomatorBridgeError) return error.code;
  return "UI_TOOL_FAILED";
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
