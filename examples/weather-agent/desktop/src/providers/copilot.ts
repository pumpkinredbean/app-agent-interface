import { mkdtempSync, rmSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { VercelAiAgentProvider } from "./vercel-ai.js";
import type { AgentProviderOptions } from "./types.js";

const require = createRequire(import.meta.url);

export async function createCopilotAgentProvider(options: AgentProviderOptions = {}): Promise<VercelAiAgentProvider> {
  const { createGitHubCopilot } = await import("ai-sdk-provider-github");
  const modelId = options.model ?? process.env.COPILOT_MODEL ?? "auto";
  const isolatedCwd = mkdtempSync(join(tmpdir(), "app-agent-interface-copilot-cwd-"));
  const clientOptions = {
    cwd: isolatedCwd,
    useStdio: true,
    useLoggedInUser: true,
    logLevel: "error" as const,
    cliPath: process.env.COPILOT_CLI_PATH ?? require.resolve("@github/copilot/npm-loader.js"),
    ...(process.env.COPILOT_GITHUB_TOKEN ? { gitHubToken: process.env.COPILOT_GITHUB_TOKEN } : {}),
  };
  const copilot = createGitHubCopilot({
    clientOptions,
    defaultSettings: { workingDirectory: clientOptions.cwd },
  });
  const client = copilot.getClient();
  const createSession = client.createSession.bind(client);
  client.createSession = (config) => createSession({
    ...config,
    availableTools: [],
    enableConfigDiscovery: false,
    onPermissionRequest: () => ({ kind: "user-not-available" }),
  });

  return new VercelAiAgentProvider({
    providerName: "github-copilot-sdk",
    modelId,
    model: copilot(modelId),
    // The community provider exposes LanguageModelV3 but not native structured output.
    stepMode: "prompt-json",
    close: async () => {
      try {
        if (client.getState() !== "disconnected") await client.stop();
      } finally {
        rmSync(isolatedCwd, { recursive: true, force: true });
      }
    },
  });
}
