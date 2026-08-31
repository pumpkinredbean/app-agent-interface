import { existsSync, mkdtempSync, rmSync, symlinkSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { join } from "node:path";

import type { ReasoningEffort } from "ai-sdk-provider-codex-cli";

import { VercelAiAgentProvider } from "./vercel-ai.js";
import type { AgentProviderOptions } from "./types.js";

export async function createCodexAgentProvider(options: AgentProviderOptions = {}): Promise<VercelAiAgentProvider> {
  const { codexExec } = await import("ai-sdk-provider-codex-cli");
  const modelId = options.model ?? process.env.CODEX_MODEL ?? "gpt-5.6-luna";
  const isolatedHome = mkdtempSync(join(tmpdir(), "app-agent-interface-codex-home-"));
  const isolatedCwd = mkdtempSync(join(tmpdir(), "app-agent-interface-codex-cwd-"));
  const authSource = join(process.env.CODEX_HOME ?? join(homedir(), ".codex"), "auth.json");
  if (existsSync(authSource)) symlinkSync(authSource, join(isolatedHome, "auth.json"));

  const model = codexExec(modelId, {
    ...(process.env.CODEX_PATH ? { codexPath: process.env.CODEX_PATH } : {}),
    allowNpx: false,
    skipGitRepoCheck: true,
    approvalMode: "never",
    sandboxMode: "read-only",
    reasoningEffort: reasoningEffort(process.env.CODEX_REASONING_EFFORT),
    webSearch: false,
    configOverrides: { "tools.web_search": false },
    env: { CODEX_HOME: isolatedHome },
    logger: false,
    cwd: isolatedCwd,
  });

  return new VercelAiAgentProvider({
    providerName: "codex-cli",
    modelId,
    model,
    stepMode: "native-object",
    close: () => {
      rmSync(isolatedHome, { recursive: true, force: true });
      rmSync(isolatedCwd, { recursive: true, force: true });
    },
  });
}

function reasoningEffort(value: string | undefined): ReasoningEffort {
  if (value === "none" || value === "minimal" || value === "low" || value === "medium" || value === "high" || value === "xhigh") {
    return value;
  }
  return "low";
}
