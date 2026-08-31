# `app-agent/1` protocol

The protocol is an application communication contract independent of any model provider. The canonical JSON Schema is [`sdk/protocol/app-agent-v1.schema.json`](../../sdk/protocol/app-agent-v1.schema.json).

## Tool manifest

```json
{
  "protocol": "app-agent/1",
  "type": "tools.list",
  "tools": [
    {
      "name": "get_account_balance",
      "description": "Returns the signed-in user's account balance. Use it when the user asks about their balance.",
      "inputSchema": {
        "type": "object",
        "properties": {},
        "additionalProperties": false
      }
    }
  ]
}
```

A ToolSpec contains no fields other than `name`, `description`, and `inputSchema`.

## Tool call

```json
{
  "protocol": "app-agent/1",
  "type": "tool.call",
  "id": "01JEXAMPLECALL",
  "name": "get_account_balance",
  "input": {}
}
```

| Field | Meaning |
|---|---|
| `id` | Opaque ID that correlates a call with its result |
| `name` | Exact registered tool name from the manifest |
| `input` | Object that must satisfy the tool's `inputSchema` |

Conversation session IDs, user IDs, provider names, and transport sources do not belong in the ToolCall payload. When needed, keep them in a trusted transport envelope or server-side connection state.

## Successful result

```json
{
  "protocol": "app-agent/1",
  "type": "tool.result",
  "id": "01JEXAMPLECALL",
  "ok": true,
  "output": {
    "availableAmount": 120000,
    "currency": "KRW"
  }
}
```

`output` may contain any JSON value; it is not restricted to an object.

## Failed result

```json
{
  "protocol": "app-agent/1",
  "type": "tool.result",
  "id": "01JEXAMPLECALL",
  "ok": false,
  "error": {
    "code": "APPROVAL_DENIED",
    "message": "The user declined this tool call",
    "retryable": false
  }
}
```

Core error codes:

| Code | Condition |
|---|---|
| `TOOL_NOT_FOUND` | No tool is registered under the requested name |
| `INVALID_INPUT` | Input fails JSON Schema validation |
| `APPROVAL_UNAVAILABLE` | No approval handler is available |
| `APPROVAL_FAILED` | Approval UI processing fails |
| `APPROVAL_DENIED` | The user declines the request |
| `TOOL_EXECUTION_FAILED` | The handler throws an unexpected exception |

Applications may add domain-specific error codes by throwing `ToolExecutionException`.

## HTTP binding

| Method | Path | Successful body |
|---|---|---|
| `GET` | `/healthz` | Health JSON |
| `GET` | `/v1/tools` | `tools.list` |
| `POST` | `/v1/tool-calls` | `tool.result` |

A malformed request returns HTTP 400 with `protocol.error`; failed authentication returns 401; an unknown route returns 404. Once a ToolCall has been parsed successfully, application execution failures still return HTTP 200 with `tool.result(ok=false)`.

## Approval flow

Approval is not included in the wire ToolSpec. The mobile runtime reads registration policy, suspends ToolCall processing, and sends an approval request to application UI. Approval continues to the handler. Rejection returns a failed result with the same call ID.

## Versioning

An incompatible field change increments the protocol value, for example to `app-agent/2`. The v1 decoder rejects any other protocol value. Optional transport metadata should be extended outside the protocol payload.
