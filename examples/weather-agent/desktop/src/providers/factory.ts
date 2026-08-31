import type { AgentProvider } from "../agent/contracts.js";
import type { AgentProviderOptions } from "./types.js";

export async function createAgentProvider(
  name = process.env.AGENT_PROVIDER ?? "codex",
  options: AgentProviderOptions = {},
): Promise<AgentProvider> {
  if (name === "codex") {
    const { createCodexAgentProvider } = await import("./codex.js");
    return createCodexAgentProvider(options);
  }
  if (name === "copilot") {
    const { createCopilotAgentProvider } = await import("./copilot.js");
    return createCopilotAgentProvider(options);
  }
  if (name === "claude") {
    const { createClaudeAgentProvider } = await import("./claude-agent.js");
    return createClaudeAgentProvider(options);
  }
  throw new Error(`Unknown agent provider: ${name}. Expected codex, copilot, or claude.`);
}
