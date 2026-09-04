# TypeScript packages

The repository uses one npm workspace and builds publication-ready ESM-only packages for Node.js 22 or newer. TypeScript source is compiled with project references, strict checking, declaration maps, and `NodeNext` module resolution.

Both TypeScript packages currently use version `0.1.0` and are consumed from this repository workspace. Automated npm publication is not configured, and Kotlin `sdk-v*` tags do not publish npm packages.

## Reusable packages

### `@pumpkinredbean/app-agent-client`

The protocol client owns the reusable desktop-side contract:

| Path | Responsibility |
|---|---|
| `contracts.ts` | JSON values and `app-agent/1` envelope types |
| `decode.ts` | Unknown-to-protocol runtime decoders |
| `errors.ts` | Transport and `ToolResult` error types |
| `http-client.ts` | Loopback or relay HTTP client |
| `index.ts` | Reviewed public exports |

Network responses enter the package as `unknown` and are decoded before being returned. The package does not read environment variables. Consumers pass connection settings explicitly and can inject `fetch` and an ID factory for tests or alternative runtimes.

```ts
import { AppAgentClient } from "@pumpkinredbean/app-agent-client";

const client = new AppAgentClient({
  baseUrl: "http://127.0.0.1:18765",
  headers: { Authorization: `Bearer ${pairingToken}` },
});

const manifest = await client.capabilities();
const exchange = await client.call("get_account_balance", {});
```

Tool names and inputs stay application-defined. TypeScript does not attempt to infer arbitrary application tool arguments from runtime JSON Schema. The agent harness validates model-produced arguments against the discovered schema before dispatch, and the mobile runtime validates them again.

### `@pumpkinredbean/app-agent-android-ui`

The optional UI package depends on the protocol client and keeps its three responsibilities separate:

| Path | Responsibility |
|---|---|
| `bridge/` | Typed UiAutomator2 HTTP boundary |
| `hierarchy/` | XML parsing and flat semantic projection |
| `tools/` | ToolSpecs, snapshots, and action orchestration |
| `errors.ts` | Android UI transport and execution errors |
| `index.ts` | Reviewed public exports |

The bridge validates raw driver responses. The hierarchy layer is application-neutral and never applies sample-specific selectors. The tools layer owns short-lived snapshot IDs, stale-screen checks, and model-facing `mobile_*` ToolCalls.

## Example-only code

The Weather desktop project demonstrates one agent implementation but is deliberately not a public agent framework:

| Path | Responsibility |
|---|---|
| `agent/` | Event-driven loop, policy, redaction, and client composition |
| `providers/` | Codex, Copilot, and Claude adapters |
| `server/` | Local chat API and session state |
| `cli/` | Demo commands and terminal presentation |

Provider SDK types are contained behind the local `AgentProvider` interface. Provider adapters, prompts, chat sessions, and presentation logic remain in the example until a second independent application proves a reusable abstraction.

## Development

Run all TypeScript work from the repository root so every workspace uses the same lockfile:

```bash
npm ci
npm run typecheck
npm run lint
npm test
npm run pack:check
```

Source imports use `.js` specifiers because the emitted package is standard ESM; TypeScript resolves those specifiers to the corresponding `.ts` source under `NodeNext`.
