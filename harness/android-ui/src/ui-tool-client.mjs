import { randomUUID } from "node:crypto";

import { ToolCallError } from "./tool-call-error.mjs";
import { UiAutomatorBridgeClient } from "./bridge-client.mjs";
import { projectUiHierarchy } from "./hierarchy.mjs";

const PROTOCOL = "app-agent/1";
const MAX_SNAPSHOTS = 12;

export const ANDROID_UI_TOOL_SPECS = Object.freeze([
  {
    name: "mobile_observe",
    description: "Reads the current Android app screen through AndroidX UiAutomator2 as a compact flat semantic snapshot. It returns readable content and interactive controls separately without a screenshot. Use this before UI actions; request mobile_screenshot only when the semantic snapshot cannot identify the required visual target.",
    inputSchema: {
      type: "object",
      properties: {
        scope: {
          type: "string",
          enum: ["all", "content", "controls"],
          description: "all returns both sections; content or controls returns only that section",
        },
      },
      additionalProperties: false,
    },
  },
  {
    name: "mobile_screenshot",
    description: "Captures the Android display for visual reasoning. Use only after mobile_observe when UiAutomator content and controls are missing, ambiguous, or custom-rendered. The snapshotId must refer to the unchanged semantic screen.",
    inputSchema: objectSchema({ snapshotId: stringSchema("Snapshot returned by mobile_observe") }, ["snapshotId"]),
  },
  {
    name: "mobile_tap",
    description: "Taps one control or one screen coordinate on the current app. Prefer a control nodeId from mobile_observe; use x and y only for a target identified from mobile_screenshot.",
    inputSchema: {
      type: "object",
      properties: {
        snapshotId: stringSchema("Snapshot that the target belongs to"),
        nodeId: stringSchema("Control nodeId from the snapshot"),
        x: coordinateSchema("Horizontal pixel coordinate from the snapshot"),
        y: coordinateSchema("Vertical pixel coordinate from the snapshot"),
      },
      required: ["snapshotId"],
      oneOf: [
        { required: ["nodeId"], not: { anyOf: [{ required: ["x"] }, { required: ["y"] }] } },
        { required: ["x", "y"], not: { required: ["nodeId"] } },
      ],
      additionalProperties: false,
    },
  },
  {
    name: "mobile_long_press",
    description: "Long-presses one control or screen coordinate on the current app. Prefer a control nodeId whose actions include long_press; use coordinates only for a visual target found in mobile_screenshot.",
    inputSchema: {
      type: "object",
      properties: {
        snapshotId: stringSchema("Snapshot that the target belongs to"),
        nodeId: stringSchema("Control nodeId from the snapshot"),
        x: coordinateSchema("Horizontal pixel coordinate from the snapshot"),
        y: coordinateSchema("Vertical pixel coordinate from the snapshot"),
        durationMs: { type: "integer", minimum: 400, maximum: 3_000 },
      },
      required: ["snapshotId"],
      oneOf: [
        { required: ["nodeId"], not: { anyOf: [{ required: ["x"] }, { required: ["y"] }] } },
        { required: ["x", "y"], not: { required: ["nodeId"] } },
      ],
      additionalProperties: false,
    },
  },
  {
    name: "mobile_multi_tap",
    description: "Taps multiple different screen positions sequentially in the exact array order within one call. Use it for a coordinate sequence derived from one unchanged screenshot, such as a custom keypad. This is not a double-tap action.",
    inputSchema: objectSchema({
      snapshotId: stringSchema("Snapshot that every position belongs to"),
      positions: {
        type: "array",
        minItems: 2,
        maxItems: 64,
        uniqueItems: true,
        items: objectSchema({
          x: coordinateSchema("Horizontal pixel coordinate"),
          y: coordinateSchema("Vertical pixel coordinate"),
        }, ["x", "y"]),
      },
      intervalMs: { type: "integer", minimum: 0, maximum: 1_000 },
    }, ["snapshotId", "positions"]),
  },
  {
    name: "mobile_input_text",
    description: "Replaces the value of one editable UiAutomator2 control. Use a text_field nodeId from the current semantic snapshot; do not use coordinates or shell text injection.",
    inputSchema: objectSchema({
      snapshotId: stringSchema("Snapshot containing the text field"),
      nodeId: stringSchema("Editable control nodeId"),
      text: { type: "string", maxLength: 4_000 },
    }, ["snapshotId", "nodeId", "text"]),
  },
  {
    name: "mobile_swipe",
    description: "Performs one swipe between two pixel positions on the current app screen. Coordinates must come from the referenced semantic snapshot or screenshot.",
    inputSchema: objectSchema({
      snapshotId: stringSchema("Snapshot defining the coordinate space"),
      start: pointSchema("Swipe start"),
      end: pointSchema("Swipe end"),
      durationMs: { type: "integer", minimum: 50, maximum: 5_000 },
    }, ["snapshotId", "start", "end"]),
  },
  {
    name: "mobile_press",
    description: "Presses one restricted Android key while the target app is foregrounded. Only BACK and ENTER are available; device-wide Home, Recents, notifications, and arbitrary keycodes are not exposed.",
    inputSchema: objectSchema({
      snapshotId: stringSchema("Current semantic snapshot"),
      key: { type: "string", enum: ["BACK", "ENTER"] },
    }, ["snapshotId", "key"]),
  },
  {
    name: "mobile_wait",
    description: "Waits for readable content or a control matching a short text or resource-id query, then returns a fresh semantic snapshot. Use it after an action that loads or changes the app asynchronously.",
    inputSchema: objectSchema({
      snapshotId: stringSchema("Snapshot before waiting"),
      query: { type: "string", minLength: 1, maxLength: 200 },
      timeoutMs: { type: "integer", minimum: 100, maximum: 15_000 },
    }, ["snapshotId", "query"]),
  },
]);

export class AndroidUiToolClient {
  constructor(options = {}) {
    this.bridge = options.bridge ?? new UiAutomatorBridgeClient(options.baseUrl);
    this.extraAllowedPackages = options.allowedPackages ?? [];
    this.snapshots = new Map();
    this.toolNames = new Set(ANDROID_UI_TOOL_SPECS.map((tool) => tool.name));
  }

  async health() {
    return this.bridge.health();
  }

  async capabilities() {
    const health = await this.health();
    if (!health.ready) throw new Error("Android UI driver is not ready");
    return { protocol: PROTOCOL, type: "tools.list", tools: ANDROID_UI_TOOL_SPECS };
  }

  handles(name) {
    return this.toolNames.has(name);
  }

  async call(name, input, options = {}) {
    if (!this.handles(name)) throw new Error(`Unknown Android UI tool: ${name}`);
    const call = {
      protocol: PROTOCOL,
      type: "tool.call",
      id: randomUUID(),
      name,
      input: input ?? {},
    };
    options.onDispatch?.(call);

    try {
      const output = await this.#execute(name, call.input);
      return {
        call,
        result: { protocol: PROTOCOL, type: "tool.result", id: call.id, ok: true, output },
      };
    } catch (error) {
      const envelope = {
        protocol: PROTOCOL,
        type: "tool.result",
        id: call.id,
        ok: false,
        error: {
          code: error.code ?? "UI_TOOL_FAILED",
          message: error.message,
          retryable: ["UI_DRIVER_UNAVAILABLE", "STALE_UI_SNAPSHOT"].includes(error.code),
        },
      };
      throw new ToolCallError(envelope);
    }
  }

  async #execute(name, input) {
    switch (name) {
      case "mobile_observe":
        return this.#observe(input.scope ?? "all");
      case "mobile_screenshot":
        return this.#screenshot(input.snapshotId);
      case "mobile_tap":
        return this.#tap(input);
      case "mobile_long_press":
        return this.#longPress(input);
      case "mobile_multi_tap":
        return this.#multiTap(input);
      case "mobile_input_text":
        return this.#inputText(input);
      case "mobile_swipe":
        return this.#swipe(input);
      case "mobile_press":
        return this.#press(input);
      case "mobile_wait":
        return this.#wait(input);
      default:
        throw uiError("UI_TOOL_NOT_FOUND", `Unknown Android UI tool: ${name}`);
    }
  }

  async #observe(scope = "all") {
    const state = await this.bridge.hierarchy();
    const projection = this.#project(state);
    const snapshot = this.#saveSnapshot(state, projection);
    return presentSnapshot(snapshot, scope);
  }

  async #screenshot(snapshotId) {
    const snapshot = await this.#requireCurrentSnapshot(snapshotId);
    const image = await this.bridge.screenshot();
    if (image.currentPackage !== snapshot.packageName) {
      throw uiError("OUTSIDE_TARGET_APP", `The foreground package changed to ${image.currentPackage || "unknown"}`);
    }
    return {
      snapshotId,
      screen: { width: image.width, height: image.height, rotation: image.rotation },
      image: { mediaType: image.mediaType, data: image.data },
    };
  }

  async #tap(input) {
    const snapshot = await this.#requireCurrentSnapshot(input.snapshotId);
    let x = input.x;
    let y = input.y;
    if (input.nodeId) {
      const node = requireControl(snapshot, input.nodeId, "tap");
      [x, y] = center(node.raw.bounds);
    }
    const action = await this.bridge.action({ action: "tap", x, y });
    return this.#afterAction(action);
  }

  async #longPress(input) {
    const snapshot = await this.#requireCurrentSnapshot(input.snapshotId);
    let x = input.x;
    let y = input.y;
    if (input.nodeId) {
      const node = requireControl(snapshot, input.nodeId, "long_press");
      [x, y] = center(node.raw.bounds);
    }
    const action = await this.bridge.action({
      action: "long_press",
      x,
      y,
      durationMs: input.durationMs ?? 650,
    });
    return this.#afterAction(action);
  }

  async #multiTap(input) {
    await this.#requireCurrentSnapshot(input.snapshotId);
    const action = await this.bridge.action({
      action: "multi_tap",
      positions: input.positions,
      intervalMs: input.intervalMs ?? 80,
    });
    return this.#afterAction(action, {
      requested: input.positions.length,
      completed: action.completed ?? input.positions.length,
    });
  }

  async #inputText(input) {
    const snapshot = await this.#requireCurrentSnapshot(input.snapshotId);
    const node = requireControl(snapshot, input.nodeId, "set_text");
    const [x, y] = center(node.raw.bounds);
    const action = await this.bridge.action({ action: "input_text", x, y, text: input.text });
    return this.#afterAction(action, { characterCount: input.text.length });
  }

  async #swipe(input) {
    await this.#requireCurrentSnapshot(input.snapshotId);
    const action = await this.bridge.action({
      action: "swipe",
      startX: input.start.x,
      startY: input.start.y,
      endX: input.end.x,
      endY: input.end.y,
      durationMs: input.durationMs ?? 350,
    });
    return this.#afterAction(action);
  }

  async #press(input) {
    await this.#requireCurrentSnapshot(input.snapshotId);
    const action = await this.bridge.action({ action: "press", key: input.key });
    return this.#afterAction(action, { key: input.key });
  }

  async #wait(input) {
    this.#snapshot(input.snapshotId);
    const timeoutMs = input.timeoutMs ?? 5_000;
    const deadline = Date.now() + timeoutMs;
    const query = input.query.toLocaleLowerCase();
    while (true) {
      const output = await this.#observe("all");
      if (snapshotMatches(output, query)) return output;
      if (Date.now() >= deadline) throw uiError("UI_WAIT_TIMEOUT", `No UI content or control matched: ${input.query}`);
      await delay(Math.min(350, Math.max(1, deadline - Date.now())));
    }
  }

  async #afterAction(action, detail = {}) {
    const after = await this.#observe("all");
    return { performed: Boolean(action.performed), ...detail, after };
  }

  async #requireCurrentSnapshot(snapshotId) {
    const expected = this.#snapshot(snapshotId);
    const state = await this.bridge.hierarchy();
    const projection = this.#project(state);
    if (projection.fingerprint !== expected.fingerprint) {
      throw uiError("STALE_UI_SNAPSHOT", "The Android screen changed; call mobile_observe again before acting");
    }
    return expected;
  }

  #project(state) {
    if (!state.targetPackage || state.currentPackage !== state.targetPackage) {
      throw uiError(
        "OUTSIDE_TARGET_APP",
        `Expected foreground package ${state.targetPackage || "unknown"} but found ${state.currentPackage || "unknown"}`,
      );
    }
    return projectUiHierarchy(state.xml, {
      allowedPackages: [state.targetPackage, ...this.extraAllowedPackages],
      screenWidth: state.width,
      screenHeight: state.height,
    });
  }

  #saveSnapshot(state, projection) {
    const snapshot = {
      id: `ui-${randomUUID()}`,
      createdAt: Date.now(),
      packageName: state.currentPackage,
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
    this.snapshots.set(snapshot.id, snapshot);
    while (this.snapshots.size > MAX_SNAPSHOTS) this.snapshots.delete(this.snapshots.keys().next().value);
    return snapshot;
  }

  #snapshot(snapshotId) {
    const snapshot = this.snapshots.get(snapshotId);
    if (!snapshot) throw uiError("UI_SNAPSHOT_NOT_FOUND", "The UI snapshot is unknown or expired; call mobile_observe again");
    return snapshot;
  }
}

function presentSnapshot(snapshot, scope) {
  return {
    snapshotId: snapshot.id,
    packageName: snapshot.packageName,
    screen: { width: snapshot.width, height: snapshot.height, rotation: snapshot.rotation },
    ...(scope !== "controls" ? { content: snapshot.content } : {}),
    ...(scope !== "content" ? { controls: snapshot.controls } : {}),
    ...(snapshot.metrics.truncatedTextNodeCount > 0 || snapshot.metrics.omittedSemanticNodeCount > 0
      ? { truncated: true }
      : {}),
  };
}

function requireControl(snapshot, nodeId, action) {
  const node = snapshot.nodeMap.get(nodeId);
  if (!node) throw uiError("UI_CONTROL_NOT_FOUND", `Control ${nodeId} does not exist in snapshot ${snapshot.id}`);
  if (!node.actions.includes(action)) {
    throw uiError("UI_ACTION_UNSUPPORTED", `Control ${nodeId} does not support ${action}`);
  }
  if (!node.raw.enabled) throw uiError("UI_CONTROL_DISABLED", `Control ${nodeId} is disabled`);
  return node;
}

function snapshotMatches(snapshot, query) {
  const values = [
    ...(snapshot.content ?? []).flatMap((node) => [node.text, node.contentDescription, node.resourceId]),
    ...(snapshot.controls ?? []).flatMap((node) => [node.label, node.detail, node.value, node.resourceId, node.role]),
  ];
  return values.some((value) => typeof value === "string" && value.toLocaleLowerCase().includes(query));
}

function center([left, top, right, bottom]) {
  return [Math.floor((left + right) / 2), Math.floor((top + bottom) / 2)];
}

function uiError(code, message) {
  const error = new Error(message);
  error.code = code;
  return error;
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function objectSchema(properties, required = []) {
  return { type: "object", properties, ...(required.length > 0 ? { required } : {}), additionalProperties: false };
}

function stringSchema(description) {
  return { type: "string", minLength: 1, description };
}

function coordinateSchema(description) {
  return { type: "integer", minimum: 0, description };
}

function pointSchema(description) {
  return {
    ...objectSchema({
      x: coordinateSchema("Horizontal pixel coordinate"),
      y: coordinateSchema("Vertical pixel coordinate"),
    }, ["x", "y"]),
    description,
  };
}
