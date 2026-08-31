import assert from "node:assert/strict";
import test from "node:test";

import { projectUiHierarchy } from "@pumpkinredbean/app-agent-android-ui";

test("projects deeply nested UiAutomator XML into a flat visual reading order", () => {
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node class="android.widget.FrameLayout" bounds="[0,0][1080,2400]" enabled="true">
        <node class="android.widget.LinearLayout" bounds="[0,0][1080,2400]" enabled="true">
          <node class="android.widget.TextView" text="Screen title" bounds="[40,80][800,140]" enabled="true" />
          <node class="android.widget.Button" text="Continue" resource-id="app:id/continue" clickable="true" bounds="[700,2100][1020,2250]" enabled="true" />
        </node>
      </node>
    </hierarchy>
  `);

  assert.equal(projection.metrics.rawNodeCount, 4);
  assert.equal(projection.metrics.maxRawDepth, 2);
  assert.deepEqual(projection.content.map((node) => node.text), ["Screen title"]);
  assert.deepEqual(projection.controls.map((node) => node.label), ["Continue"]);
  assert.deepEqual(projection.controls.map((node) => node.nodeId), ["c1"]);
  assert.equal(projection.controls[0].role, "button");
  assert.deepEqual(projection.controls[0].actions, ["tap"]);
  assert.ok([...projection.content, ...projection.controls]
    .every((node) => !("depth" in node) && !("parentId" in node)));
});

test("merges a non-actionable child label into its clickable parent", () => {
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node class="android.view.ViewGroup" clickable="true" bounds="[100,200][800,400]" enabled="true">
        <node class="android.widget.TextView" text="Approve transfer" bounds="[130,240][700,330]" enabled="true" />
      </node>
    </hierarchy>
  `);

  assert.equal(projection.content.length, 0);
  assert.equal(projection.controls.length, 1);
  assert.equal(projection.controls[0].label, "Approve transfer");
  assert.deepEqual(projection.controls[0].actions, ["tap"]);
});

test("keeps dialog semantics but removes layout-only containers", () => {
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node class="android.widget.FrameLayout" bounds="[28,785][1052,1621]" enabled="true">
        <node class="android.widget.LinearLayout" resource-id="android:id/parentPanel" bounds="[70,827][1010,1579]" enabled="true">
          <node class="android.widget.TextView" text="Approve app action?" resource-id="android:id/alertTitle" bounds="[133,874][947,945]" enabled="true" />
          <node class="android.widget.TextView" text="Submit this request" resource-id="android:id/message" bounds="[70,966][1010,1415]" enabled="true" />
          <node class="android.widget.Button" text="DENY" resource-id="android:id/button2" clickable="true" bounds="[582,1426][750,1568]" enabled="true" />
          <node class="android.widget.Button" text="APPROVE" resource-id="android:id/button1" clickable="true" bounds="[750,1426][978,1568]" enabled="true" />
        </node>
      </node>
    </hierarchy>
  `);

  assert.deepEqual(projection.content.map((node) => node.text), [
    "Approve app action?",
    "Submit this request",
  ]);
  assert.deepEqual(projection.controls.map((node) => node.label), ["DENY", "APPROVE"]);
  assert.equal(projection.metrics.semanticNodeCount, 4);
});

test("truncates the middle of long screen text while retaining recent tail content", () => {
  const longText = `old-${"x".repeat(900)}-latest-event`;
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node class="android.widget.TextView" text="${longText}" bounds="[0,0][1000,2200]" enabled="true" />
    </hierarchy>
  `, { maxNodeText: 120 });

  assert.equal(projection.content[0].textTruncated, true);
  assert.match(projection.content[0].text, /^old-/);
  assert.match(projection.content[0].text, /latest-event$/);
  assert.ok(projection.content[0].text.length <= 125);
});

test("never exposes password text from the hierarchy", () => {
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node class="android.widget.EditText" text="123456" password="true" focusable="true" bounds="[100,500][900,650]" enabled="true" />
    </hierarchy>
  `);

  assert.equal(projection.controls[0].role, "text_field");
  assert.equal(projection.controls[0].value, "••••••");
  assert.equal(projection.controls[0].state.password, true);
});

test("filters system windows by a runtime package allowlist rather than app-specific rules", () => {
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node package="example.target" class="android.widget.Button" text="Continue" clickable="true" bounds="[10,100][300,220]" enabled="true" />
      <node package="com.android.systemui" class="android.widget.Button" text="Home" clickable="true" bounds="[400,2200][680,2390]" enabled="true" />
    </hierarchy>
  `, {
    allowedPackages: ["example.target"],
    screenWidth: 1080,
    screenHeight: 2400,
  });

  assert.deepEqual(projection.controls.map((control) => control.label), ["Continue"]);
  assert.equal(projection.metrics.rawNodeCount, 2);
  assert.equal(projection.metrics.includedRawNodeCount, 1);
});

test("decodes nested numeric entities emitted by UiAutomator hierarchy dumps", () => {
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node class="android.widget.TextView" text="First&amp;#10;Second" bounds="[10,10][500,100]" enabled="true" />
    </hierarchy>
  `);

  assert.equal(projection.content[0].text, "First\nSecond");
});

test("handles a settings-style scroll list without duplicating clickable row labels as content", () => {
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node package="sample.settings" class="android.widget.ScrollView" scrollable="true" bounds="[0,120][1080,2280]" enabled="true">
        <node package="sample.settings" class="android.widget.TextView" text="Network preferences" bounds="[48,150][900,220]" enabled="true" />
        <node package="sample.settings" class="android.view.ViewGroup" clickable="true" bounds="[24,280][1056,520]" enabled="true">
          <node package="sample.settings" class="android.widget.TextView" text="Private DNS" bounds="[64,320][720,390]" enabled="true" />
          <node package="sample.settings" class="android.widget.TextView" text="Automatic" bounds="[64,405][720,465]" enabled="true" />
        </node>
      </node>
    </hierarchy>
  `, { allowedPackages: ["sample.settings"] });

  assert.deepEqual(projection.content.map((node) => node.text), ["Network preferences"]);
  assert.equal(projection.controls.find((node) => node.role === "scroll_area").actions[0], "scroll");
  const row = projection.controls.find((node) => node.role === "button");
  assert.equal(row.label, "Private DNS");
  assert.equal(row.detail, "Automatic");
});

test("uses generic accessibility semantics for Compose-style views", () => {
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node package="sample.compose" class="android.view.View" content-desc="Share document" clickable="true" bounds="[820,80][1040,240]" enabled="true" />
      <node package="sample.compose" class="android.view.View" content-desc="Sync over cellular" checkable="true" checked="false" bounds="[40,300][1040,460]" enabled="true" />
    </hierarchy>
  `, { allowedPackages: ["sample.compose"] });

  assert.equal(projection.content.length, 0);
  assert.deepEqual(projection.controls.map((node) => node.label), ["Share document", "Sync over cellular"]);
  assert.equal(projection.controls[1].role, "checkbox");
  assert.equal(projection.controls[1].state.checked, false);
});

test("projects WebView accessibility nodes using the same generic format", () => {
  const projection = projectUiHierarchy(`
    <hierarchy rotation="0">
      <node package="sample.web" class="android.webkit.WebView" bounds="[0,100][1080,2300]" enabled="true">
        <node package="sample.web" class="android.widget.TextView" text="Sign in to continue" bounds="[60,240][900,320]" enabled="true" />
        <node package="sample.web" class="android.widget.EditText" text="person@example.com" hint="Email address" focusable="true" bounds="[60,400][1020,540]" enabled="true" />
        <node package="sample.web" class="android.widget.Button" text="Continue" clickable="true" bounds="[60,600][1020,740]" enabled="true" />
      </node>
    </hierarchy>
  `, { allowedPackages: ["sample.web"] });

  assert.deepEqual(projection.content.map((node) => node.text), ["Sign in to continue"]);
  assert.equal(projection.controls[0].role, "text_field");
  assert.equal(projection.controls[0].label, "Email address");
  assert.equal(projection.controls[0].value, "person@example.com");
  assert.equal(projection.controls[1].label, "Continue");
});

test("enforces semantic budgets while fingerprinting omitted state changes", () => {
  const xml = (tail) => `
    <hierarchy rotation="0">
      <node class="android.widget.TextView" text="First" bounds="[10,10][900,100]" enabled="true" />
      <node class="android.widget.TextView" text="Second" bounds="[10,120][900,210]" enabled="true" />
      <node class="android.widget.TextView" text="${tail}" bounds="[10,230][900,320]" enabled="true" />
    </hierarchy>`;
  const before = projectUiHierarchy(xml("Old tail"), { maxNodes: 2, maxTotalText: 100 });
  const after = projectUiHierarchy(xml("Changed tail"), { maxNodes: 2, maxTotalText: 100 });

  assert.equal(before.content.length, 2);
  assert.equal(before.metrics.omittedSemanticNodeCount, 1);
  assert.notEqual(before.fingerprint, after.fingerprint);
});
