import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { Ajv2020, type AnySchema } from "ajv/dist/2020.js";

import {
  APP_AGENT_PROTOCOL,
  AppAgentClient,
  ProtocolDecodeError,
  ToolCallError,
  decodeToolsList,
} from "../src/index.js";

test("decodes a capability manifest into the public protocol types", () => {
  const manifest = decodeToolsList({
    protocol: APP_AGENT_PROTOCOL,
    type: "tools.list",
    tools: [{ name: "example_read", description: "Reads an example", inputSchema: { type: "object" } }],
  });

  assert.equal(manifest.tools[0]?.name, "example_read");
  assert.throws(
    () => decodeToolsList({ protocol: "other/1", type: "tools.list", tools: [] }),
    ProtocolDecodeError,
  );
});

test("dispatches a typed ToolCall and correlates its ToolResult", async () => {
  let requestBody: unknown;
  const client = new AppAgentClient({
    baseUrl: "http://runtime.test/",
    idFactory: () => "call-1",
    fetch: async (_input, init) => {
      requestBody = JSON.parse(String(init?.body)) as unknown;
      return Response.json({
        protocol: APP_AGENT_PROTOCOL,
        type: "tool.result",
        id: "call-1",
        ok: true,
        output: { value: 42 },
      });
    },
  });

  const exchange = await client.call("example_read", { id: "42" });
  assert.deepEqual(requestBody, exchange.call);
  assert.deepEqual(exchange.result.output, { value: 42 });
});

test("exposes application ToolResult failures without losing the envelope", async () => {
  const client = new AppAgentClient({
    idFactory: () => "call-2",
    fetch: async () => Response.json({
      protocol: APP_AGENT_PROTOCOL,
      type: "tool.result",
      id: "call-2",
      ok: false,
      error: { code: "NOT_FOUND", message: "Missing", retryable: false },
    }),
  });

  await assert.rejects(
    client.call("example_read"),
    (error: unknown) => error instanceof ToolCallError && error.code === "NOT_FOUND" && !error.retryable,
  );
});

test("rejects a ToolResult that belongs to a different call", async () => {
  const client = new AppAgentClient({
    idFactory: () => "expected",
    fetch: async () => Response.json({
      protocol: APP_AGENT_PROTOCOL,
      type: "tool.result",
      id: "different",
      ok: true,
      output: null,
    }),
  });

  await assert.rejects(client.call("example_read"), /does not match call id/);
});

test("canonical protocol schema accepts the TypeScript wire envelopes", async () => {
  const schemaUrl = new URL("../../../../protocol/app-agent-v1.schema.json", import.meta.url);
  const schema = JSON.parse(await readFile(schemaUrl, "utf8")) as AnySchema;
  const validate = new Ajv2020({ allErrors: true, strict: true }).compile(schema);

  const messages = [
    {
      protocol: APP_AGENT_PROTOCOL,
      type: "tools.list",
      tools: [{ name: "example_read", description: "Reads an example", inputSchema: { type: "object" } }],
    },
    {
      protocol: APP_AGENT_PROTOCOL,
      type: "tool.call",
      id: "call-schema-1",
      name: "example_read",
      input: {},
    },
    {
      protocol: APP_AGENT_PROTOCOL,
      type: "tool.result",
      id: "call-schema-1",
      ok: true,
      output: { value: 42 },
    },
    {
      protocol: APP_AGENT_PROTOCOL,
      type: "tool.result",
      id: "call-schema-2",
      ok: false,
      error: { code: "NOT_FOUND", message: "Missing", retryable: false },
    },
  ];

  for (const message of messages) {
    assert.equal(validate(message), true, JSON.stringify(validate.errors));
  }
});
