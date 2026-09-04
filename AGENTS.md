# Repository guidance

## Before changing code

- Read `README.md`, `CONTRIBUTING.md`, and the relevant document under `docs/sdk/`.
- Check the working tree first and preserve existing user changes.
- For an unsettled UX direction, validate a runnable preview before broad implementation.

## Architecture

- Keep reusable SDK packages provider-neutral and domain-neutral.
- Keep application-specific tools and UI renderers in applications or examples.
- Limit the model-visible `ToolSpec` to `name`, `description`, and `inputSchema`.
- Get explicit approval before making a breaking wire-protocol or public SDK API change.

## Documentation

- Write public source, documentation, examples, prompts, fixtures, and UI copy in English.
- Use Mermaid for conceptual diagrams.
- Update documentation and tests when public behavior changes.

## Git

- Do not change remotes, rewrite history, publish releases, or introduce a new branch convention unless explicitly requested.

## Verification

- For TypeScript changes, run the documentation check, lint, tests, and package checks defined in `package.json`.
- For Kotlin or Android changes, run the affected Gradle tests and builds documented in `CONTRIBUTING.md`.
- For UI changes, verify both compact and wide layouts.
