import assert from "node:assert/strict";
import test from "node:test";

import { APP_AGENT_PROTOCOL, type ToolsList } from "@pumpkinredbean/app-agent-client";

import { CompositeAgentToolClient } from "../../src/agent/tool-client.js";

const appManifest = {
  protocol: APP_AGENT_PROTOCOL,
  type: "tools.list",
  tools: [{ name: "app_lookup", description: "Looks up app data", inputSchema: { type: "object" } }],
} satisfies ToolsList;
const uiManifest = {
  protocol: APP_AGENT_PROTOCOL,
  type: "tools.list",
  tools: [{ name: "mobile_observe", description: "Observes UI", inputSchema: { type: "object" } }],
} satisfies ToolsList;

test("composite catalog keeps application tools and optional harness UI tools distinct", async () => {
  const calls: string[][] = [];
  const client = new CompositeAgentToolClient({
    appClient: {
      capabilities: async () => appManifest,
      call: async (name) => calls.push(["app", name]),
    },
    uiClient: {
      toolNames: new Set(["mobile_observe"]),
      capabilities: async () => uiManifest,
      handles: (name) => name === "mobile_observe",
      call: async (name) => calls.push(["ui", name]),
    },
  });

  assert.deepEqual((await client.capabilities()).tools.map((tool) => tool.name), ["app_lookup", "mobile_observe"]);
  await client.call("app_lookup", {});
  await client.call("mobile_observe", {});
  assert.deepEqual(calls, [["app", "app_lookup"], ["ui", "mobile_observe"]]);
});

test("optional UI driver failure leaves semantic application tools available", async () => {
  const client = new CompositeAgentToolClient({
    appClient: { capabilities: async () => appManifest },
    uiClient: { capabilities: async () => { throw new Error("driver offline"); } },
    uiOptional: true,
  });

  assert.deepEqual(await client.capabilities(), appManifest);
});

test("composite catalog rejects a UI tool name collision with an application tool", async () => {
  const client = new CompositeAgentToolClient({
    appClient: { capabilities: async () => appManifest },
    uiClient: {
      capabilities: async () => ({ ...uiManifest, tools: [{ ...uiManifest.tools[0]!, name: "app_lookup" }] }),
    },
  });

  await assert.rejects(() => client.capabilities(), /collides with harness UI tool/);
});
