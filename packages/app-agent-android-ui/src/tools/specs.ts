import type { JsonObject, ToolSpec } from "@pumpkinredbean/app-agent-client";

export const ANDROID_UI_TOOL_SPECS: readonly ToolSpec[] = Object.freeze([
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

function objectSchema(properties: JsonObject, required: readonly string[] = []): JsonObject {
  return {
    type: "object",
    properties,
    ...(required.length > 0 ? { required: [...required] } : {}),
    additionalProperties: false,
  };
}

function stringSchema(description: string): JsonObject {
  return { type: "string", minLength: 1, description };
}

function coordinateSchema(description: string): JsonObject {
  return { type: "integer", minimum: 0, description };
}

function pointSchema(description: string): JsonObject {
  return {
    ...objectSchema({
      x: coordinateSchema("Horizontal pixel coordinate"),
      y: coordinateSchema("Vertical pixel coordinate"),
    }, ["x", "y"]),
    description,
  };
}
