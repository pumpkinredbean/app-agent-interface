# `@pumpkinredbean/app-agent-client`

Typed ESM client for discovering and calling application-scoped tools exposed through the App Agent Interface protocol.

```ts
import { AppAgentClient } from "@pumpkinredbean/app-agent-client";

const client = new AppAgentClient({ baseUrl: "http://127.0.0.1:18765" });
const manifest = await client.capabilities();
const exchange = await client.call("example_read", { id: "42" });
```

The client validates protocol envelopes at the network boundary. Tool inputs remain service-defined JSON objects described by each tool's standard JSON Schema.
