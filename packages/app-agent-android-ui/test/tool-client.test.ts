import assert from "node:assert/strict";
import test from "node:test";

import { Ajv } from "ajv";

import {
  ANDROID_UI_TOOL_SPECS,
  AndroidUiToolClient,
  ToolCallError,
  type UiActionResponse,
  type UiAutomatorBridge,
  type UiDriverAction,
  type UiDriverHealth,
  type UiHierarchyResponse,
  type UiScreenshotResponse,
} from "../src/index.js";

const packageName = "example.target";

test("UI base ToolSpecs contain only the canonical model-facing fields", () => {
  assert.ok(ANDROID_UI_TOOL_SPECS.length >= 8);
  for (const tool of ANDROID_UI_TOOL_SPECS) {
    assert.deepEqual(Object.keys(tool).sort(), ["description", "inputSchema", "name"]);
  }
});

test("UI ToolSpecs validate node targets, coordinate targets, and distinct multi-tap positions", () => {
  const ajv = new Ajv({ allErrors: true, strict: false });
  const schema = (name: string) => ANDROID_UI_TOOL_SPECS.find((tool) => tool.name === name)!.inputSchema;
  const tap = ajv.compile(schema("mobile_tap"));
  const multiTap = ajv.compile(schema("mobile_multi_tap"));
  assert.equal(tap({ snapshotId: "ui-1", nodeId: "c1" }), true);
  assert.equal(tap({ snapshotId: "ui-1", x: 120, y: 240 }), true);
  assert.equal(tap({ snapshotId: "ui-1", nodeId: "c1", x: 120, y: 240 }), false);
  assert.equal(multiTap({
    snapshotId: "ui-1",
    positions: [{ x: 120, y: 240 }, { x: 120, y: 240 }],
  }), false);
});

test("mobile_observe separates readable content and controls in one flat snapshot", async () => {
  const client = new AndroidUiToolClient({ bridge: fixtureBridge() });
  const exchange = await client.call("mobile_observe", { scope: "all" });
  const snapshot = exchange.result.output;

  assert.equal(snapshot.packageName, packageName);
  assert.deepEqual(snapshot.content!.map((node) => node.text), ["Account overview"]);
  assert.deepEqual(snapshot.controls!.map((node) => node.label), ["Continue"]);
  assert.equal(snapshot.controls![0]!.nodeId, "c1");
  assert.ok(!("depth" in snapshot.controls![0]!));
});

test("mobile_observe scope can omit content or controls without changing the canonical snapshot", async () => {
  const client = new AndroidUiToolClient({ bridge: fixtureBridge() });
  const content = (await client.call("mobile_observe", { scope: "content" })).result.output;
  const controls = (await client.call("mobile_observe", { scope: "controls" })).result.output;

  assert.ok(Array.isArray(content.content));
  assert.ok(!("controls" in content));
  assert.ok(Array.isArray(controls.controls));
  assert.ok(!("content" in controls));
});

test("mobile_tap resolves a nodeId against the same snapshot and returns a fresh semantic state", async () => {
  const bridge = fixtureBridge();
  const client = new AndroidUiToolClient({ bridge });
  const observed = (await client.call("mobile_observe", { scope: "all" })).result.output;
  const tapped = await client.call("mobile_tap", { snapshotId: observed.snapshotId, nodeId: "c1" });

  assert.deepEqual(bridge.actions[0], { action: "tap", x: 500, y: 650 });
  assert.equal(tapped.result.output.performed, true);
  assert.ok(tapped.result.output.after.snapshotId !== observed.snapshotId);
});

test("mobile_multi_tap preserves every different position and its declared order", async () => {
  const bridge = fixtureBridge();
  const client = new AndroidUiToolClient({ bridge });
  const observed = (await client.call("mobile_observe", { scope: "all" })).result.output;
  const positions = [{ x: 120, y: 900 }, { x: 500, y: 1200 }, { x: 880, y: 1500 }];

  const exchange = await client.call("mobile_multi_tap", {
    snapshotId: observed.snapshotId,
    positions,
    intervalMs: 60,
  });

  assert.deepEqual(bridge.actions[0], { action: "multi_tap", positions, intervalMs: 60 });
  assert.equal(exchange.result.output.requested, 3);
  assert.equal(exchange.result.output.completed, 3);
});

test("mobile_long_press targets only controls that expose the long_press action", async () => {
  const bridge = fixtureBridge();
  bridge.xml = `
    <hierarchy rotation="0">
      <node package="${packageName}" class="android.view.View" content-desc="Open item menu" long-clickable="true" bounds="[100,600][900,700]" enabled="true" />
    </hierarchy>`;
  const client = new AndroidUiToolClient({ bridge });
  const observed = (await client.call("mobile_observe", { scope: "all" })).result.output;

  await client.call("mobile_long_press", {
    snapshotId: observed.snapshotId,
    nodeId: "c1",
    durationMs: 800,
  });

  assert.deepEqual(bridge.actions[0], { action: "long_press", x: 500, y: 650, durationMs: 800 });
});

test("actions reject a stale semantic snapshot before injecting input", async () => {
  const bridge = fixtureBridge();
  const client = new AndroidUiToolClient({ bridge });
  const observed = (await client.call("mobile_observe", { scope: "all" })).result.output;
  bridge.xml = hierarchyXml("Changed screen", "Continue");

  await assert.rejects(
    () => client.call("mobile_tap", { snapshotId: observed.snapshotId, nodeId: "c1" }),
    (error: unknown) => error instanceof ToolCallError && error.code === "STALE_UI_SNAPSHOT",
  );
  assert.equal(bridge.actions.length, 0);
});

test("mobile_screenshot remains a separate fallback and returns image content", async () => {
  const bridge = fixtureBridge();
  const client = new AndroidUiToolClient({ bridge });
  const observed = (await client.call("mobile_observe", { scope: "all" })).result.output;
  const exchange = await client.call("mobile_screenshot", { snapshotId: observed.snapshotId });

  assert.equal(exchange.result.output.image.mediaType, "image/png");
  assert.equal(Buffer.from(exchange.result.output.image.data, "base64").toString(), "png");
});

function fixtureBridge(): FixtureBridge {
  return new FixtureBridge();
}

class FixtureBridge implements UiAutomatorBridge {
  public readonly actions: UiDriverAction[] = [];
  public xml = hierarchyXml("Account overview", "Continue");

  public async health(): Promise<UiDriverHealth> {
    return {
      ready: true,
      runtime: "androidx-uiautomator2",
      targetPackage: packageName,
      currentPackage: packageName,
      width: 1080,
      height: 2400,
      rotation: 0,
    };
  }

  public async hierarchy(): Promise<UiHierarchyResponse> {
    return {
      targetPackage: packageName,
      currentPackage: packageName,
      width: 1080,
      height: 2400,
      rotation: 0,
      xml: this.xml,
    };
  }

  public async screenshot(): Promise<UiScreenshotResponse> {
    return {
      targetPackage: packageName,
      currentPackage: packageName,
      width: 1080,
      height: 2400,
      rotation: 0,
      mediaType: "image/png",
      data: Buffer.from("png").toString("base64"),
    };
  }

  public async action(action: UiDriverAction): Promise<UiActionResponse> {
    this.actions.push(action);
    return {
      performed: true,
      ...(action.action === "multi_tap" ? { completed: action.positions.length } : {}),
      targetPackage: packageName,
      currentPackage: packageName,
    };
  }
}

function hierarchyXml(content: string, control: string): string {
  return `
    <hierarchy rotation="0">
      <node package="${packageName}" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true">
        <node package="${packageName}" class="android.widget.TextView" text="${content}" bounds="[40,100][800,200]" enabled="true" />
        <node package="${packageName}" class="android.widget.Button" text="${control}" clickable="true" bounds="[100,600][900,700]" enabled="true" />
      </node>
    </hierarchy>
  `;
}
