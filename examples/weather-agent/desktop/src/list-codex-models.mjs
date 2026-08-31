import { listModels } from "ai-sdk-provider-codex-cli";

const result = await listModels({
  ...(process.env.CODEX_PATH ? { codexPath: process.env.CODEX_PATH } : {}),
});

for (const model of result.models) {
  const marker = model.id === result.defaultModel?.id ? "*" : " ";
  console.log(`${marker} ${model.id}${model.description ? ` — ${model.description}` : ""}`);
}
