export async function createAgentProvider(name = process.env.AGENT_PROVIDER ?? "codex", options = {}) {
  if (name === "codex") {
    const { createCodexAgentProvider } = await import("./providers/codex.mjs");
    return createCodexAgentProvider(options);
  }
  if (name === "copilot") {
    const { createCopilotAgentProvider } = await import("./providers/copilot.mjs");
    return createCopilotAgentProvider(options);
  }
  if (name === "claude") {
    const { createClaudeAgentProvider } = await import("./providers/claude-agent.mjs");
    return createClaudeAgentProvider(options);
  }
  throw new Error(`Unknown agent provider: ${name}. Expected codex, copilot, or claude.`);
}
