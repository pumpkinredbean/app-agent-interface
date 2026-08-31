import { AndroidUiToolClient } from "@pumpkinredbean/app-agent-android-ui";
import {
  AppAgentClient,
  type JsonObject,
  type JsonValue,
  type SuccessfulToolExchange,
  type ToolCallOptions,
  type ToolsList,
} from "@pumpkinredbean/app-agent-client";

interface AppClientLike {
  health?(): Promise<JsonObject>;
  capabilities(): Promise<ToolsList>;
  call?(name: string, input: JsonObject, options?: ToolCallOptions): Promise<unknown>;
}

interface UiClientLike {
  readonly toolNames?: ReadonlySet<string>;
  health?(): Promise<object>;
  capabilities(): Promise<ToolsList>;
  handles?(name: string): boolean;
  call?(name: string, input: JsonObject, options?: ToolCallOptions): Promise<unknown>;
}

export interface CompositeAgentToolClientOptions {
  readonly appClient?: AppClientLike;
  readonly uiClient?: UiClientLike | null;
  readonly uiOptional?: boolean;
}

export interface UiDriverStatus extends Record<string, unknown> {
  readonly enabled: boolean;
  readonly ready: boolean;
  readonly toolCount?: number;
  readonly error?: string;
}

export class CompositeAgentToolClient {
  readonly #appClient: AppClientLike;
  readonly #uiClient: UiClientLike | null;
  readonly #uiOptional: boolean;
  public lastUiHealth: Record<string, unknown> | null = null;
  public lastUiError: unknown = null;

  public constructor({
    appClient = new AppAgentClient(),
    uiClient = null,
    uiOptional = true,
  }: CompositeAgentToolClientOptions = {}) {
    this.#appClient = appClient;
    this.#uiClient = uiClient;
    this.#uiOptional = uiOptional;
  }

  public health(): Promise<JsonObject> {
    if (this.#appClient.health === undefined) throw new Error("Application client does not expose health status");
    return this.#appClient.health();
  }

  public appCapabilities(): Promise<ToolsList> {
    return this.#appClient.capabilities();
  }

  public async uiStatus(): Promise<UiDriverStatus> {
    if (this.#uiClient === null) return { enabled: false, ready: false };
    try {
      if (this.#uiClient.health === undefined) throw new Error("UI client does not expose health status");
      const health = await this.#uiClient.health() as Record<string, unknown>;
      this.lastUiHealth = health;
      this.lastUiError = null;
      return {
        ...health,
        enabled: true,
        ready: Boolean(health.ready),
        toolCount: this.#uiClient.toolNames?.size ?? 0,
      };
    } catch (error) {
      this.lastUiHealth = null;
      this.lastUiError = error;
      return {
        enabled: true,
        ready: false,
        toolCount: this.#uiClient.toolNames?.size ?? 0,
        error: errorMessage(error),
      };
    }
  }

  public async capabilities(): Promise<ToolsList> {
    const appManifest = await this.#appClient.capabilities();
    if (this.#uiClient === null) return appManifest;

    let uiManifest: ToolsList;
    try {
      uiManifest = await this.#uiClient.capabilities();
      this.lastUiError = null;
    } catch (error) {
      this.lastUiError = error;
      if (!this.#uiOptional) throw error;
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

  public async call(
    name: string,
    input: JsonObject,
    options: ToolCallOptions = {},
  ): Promise<SuccessfulToolExchange<JsonValue>> {
    const client = this.#uiClient?.handles?.(name) ? this.#uiClient : this.#appClient;
    if (client.call === undefined) throw new Error(`Selected tool client cannot execute ${name}`);
    return await client.call(name, input, options) as SuccessfulToolExchange<JsonValue>;
  }
}

export interface DefaultAgentToolClientOptions {
  readonly allowedPackages?: readonly string[];
  readonly baseUrl?: string;
  readonly uiBaseUrl?: string;
  readonly uiEnabled?: boolean;
}

export function createDefaultAgentToolClient(options: DefaultAgentToolClientOptions = {}): CompositeAgentToolClient {
  const appClient = new AppAgentClient({
    baseUrl: options.baseUrl ?? process.env.APP_TOOL_BASE_URL ?? "http://127.0.0.1:18765",
  });
  const uiEnabled = options.uiEnabled ?? process.env.ANDROID_UI_TOOLS !== "0";
  const uiClient = uiEnabled
    ? new AndroidUiToolClient({
        baseUrl: options.uiBaseUrl ?? process.env.ANDROID_UI_BASE_URL ?? "http://127.0.0.1:18766",
        ...(options.allowedPackages === undefined ? {} : { allowedPackages: options.allowedPackages }),
      })
    : null;
  return new CompositeAgentToolClient({ appClient, uiClient, uiOptional: true });
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
