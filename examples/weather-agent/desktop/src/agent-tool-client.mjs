import { AndroidUiToolClient } from "@pumpkinredbean/app-agent-android-ui";
import { AppToolClient } from "./tool-client.mjs";

export class CompositeAgentToolClient {
  constructor({ appClient, uiClient = null, uiOptional = true } = {}) {
    this.appClient = appClient ?? new AppToolClient();
    this.uiClient = uiClient;
    this.uiOptional = uiOptional;
    this.lastUiHealth = null;
    this.lastUiError = null;
  }

  health() {
    return this.appClient.health();
  }

  appCapabilities() {
    return this.appClient.capabilities();
  }

  async uiStatus() {
    if (!this.uiClient) return { enabled: false, ready: false };
    try {
      const health = await this.uiClient.health();
      this.lastUiHealth = health;
      this.lastUiError = null;
      return {
        enabled: true,
        ready: Boolean(health.ready),
        toolCount: this.uiClient.toolNames?.size ?? 0,
        ...health,
      };
    } catch (error) {
      this.lastUiHealth = null;
      this.lastUiError = error;
      return { enabled: true, ready: false, toolCount: this.uiClient.toolNames?.size ?? 0, error: error.message };
    }
  }

  async capabilities() {
    const appManifest = await this.appClient.capabilities();
    if (!this.uiClient) return appManifest;

    let uiManifest;
    try {
      uiManifest = await this.uiClient.capabilities();
      this.lastUiError = null;
    } catch (error) {
      this.lastUiError = error;
      if (!this.uiOptional) throw error;
      return appManifest;
    }

    const names = new Set(appManifest.tools.map((tool) => tool.name));
    for (const tool of uiManifest.tools) {
      if (names.has(tool.name)) throw new Error(`Application tool collides with harness UI tool: ${tool.name}`);
      names.add(tool.name);
    }
    return {
      protocol: appManifest.protocol,
      type: "tools.list",
      tools: [...appManifest.tools, ...uiManifest.tools],
    };
  }

  call(name, input, options) {
    if (this.uiClient?.handles(name)) return this.uiClient.call(name, input, options);
    return this.appClient.call(name, input, options);
  }
}

export function createDefaultAgentToolClient(options = {}) {
  const appClient = new AppToolClient(options.baseUrl);
  const uiEnabled = options.uiEnabled ?? process.env.ANDROID_UI_TOOLS !== "0";
  const uiClient = uiEnabled
    ? new AndroidUiToolClient({
        baseUrl: options.uiBaseUrl,
        allowedPackages: options.allowedPackages,
      })
    : null;
  return new CompositeAgentToolClient({ appClient, uiClient, uiOptional: true });
}
