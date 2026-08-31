# `@pumpkinredbean/app-agent-android-ui`

Provider-neutral desktop tools for observing and interacting with one Android application through the repository's UiAutomator2 companion driver.

```js
import { AndroidUiToolClient } from "@pumpkinredbean/app-agent-android-ui";

const client = new AndroidUiToolClient({
  baseUrl: "http://127.0.0.1:18766",
});

const manifest = await client.capabilities();
const observation = await client.call("mobile_observe", { scope: "all" });
```

The exported ToolSpecs contain only `name`, `description`, and `inputSchema`. `mobile_observe` converts raw UiAutomator XML into flat `content` and `controls` arrays. It has no application-specific package names, text, resource IDs, or layout selectors.

See [Android UI automation fallback](../../docs/sdk/android-ui-automation.md) for the complete contract, companion setup, screenshot policy, and security boundary.
