# Tool authoring guide

## Required contract

Every service defines exactly these three model-visible values:

```kotlin
ToolSpec(
    name = "...",
    description = "...",
    inputSchema = ...,
)
```

The SDK has no predefined domain tools.

## Name

For portability across providers, names must match `^[A-Za-z0-9_-]{1,64}$`.

Recommended:

```text
get_account_balance
search_companies
create_support_ticket
```

Avoid:

```text
account.get_balance   # Some providers reject dots
get balance           # Spaces are not allowed
doEverything          # The scope is ambiguous
```

### Distinguish factual results from interfaces

Tool names remain application-defined, but a consistent verb helps the model distinguish a data lookup from an intentional interface transition.

| Verb | Expected behavior |
|---|---|
| `get_*` | Return concise model-readable data without opening a UI frame |
| `visualize_*` | Return an application-defined UI frame when the user asks to view or explore something |
| `prepare_*` | Build a non-executing review or draft, which may include a UI frame |
| `create_*`, `submit_*`, `update_*`, `cancel_*` | Perform the stated mutation after application validation and any required approval |

For example, `get_project_status` can answer a factual question inside the conversation, while `visualize_project_status` can open an interactive status view. Do not use a visual tool merely because one exists. The tool description must still explain the distinction because a prefix is a naming convention, not execution policy.

## Description

Do not add separate `useWhen` or `avoidWhen` fields. Write one natural-language `description` that explains:

1. what the tool returns or performs;
2. which user requests should trigger it;
3. when a similar tool should be used instead.

Weak:

```text
Account lookup
```

Strong:

```text
Returns the signed-in user's account balance and available amount. Use it when the user asks about their balance or available funds. Do not use it to place an order or transfer funds.
```

Do not include implementation class names, endpoint paths, token formats, or other details that do not help the model choose a tool.

## Input schema

The root must always be a JSON Schema object. Describe every property precisely enough for the model to generate the correct value.

```json
{
  "type": "object",
  "properties": {
    "companyId": {
      "type": "string",
      "minLength": 1,
      "description": "Internal service ID of the company to retrieve"
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 50,
      "description": "Maximum number of items to return"
    }
  },
  "required": ["companyId"],
  "additionalProperties": false
}
```

The default validator implements this portable subset:

- `type`: `object`, `array`, `string`, `integer`, `number`, `boolean`, `null`;
- `properties`, `required`, `additionalProperties: false`;
- `items`, `minItems`, `maxItems`, `uniqueItems`;
- `enum`, `const`;
- `minLength`, `maxLength`, `pattern`;
- `minimum`, `maximum`.

Other annotations may be forwarded to a provider but are ignored by the default validator. Replace `ToolInputValidator` if the application requires a complete JSON Schema implementation.

### Never model secure input

Do not define PIN, OTP, password, access-token, session-cookie, biometric, or private-key fields. Masking a field in UI does not undo its exposure to the model, ToolCall transport, or traces. Define only the non-secret action intent, then collect any required secret through the application's existing secure UI after on-device approval.

## Approval

Approval is not model schema:

```kotlin
runtime.register(spec, approval = ToolApproval.MOBILE) { input, context ->
    // This runs only after approval.
}
```

Do not assume that every read-only tool can skip approval; application policy should consider whether it exposes sensitive personal data. Conversely, do not put a security classification in `description` and delegate policy enforcement to the model.

## Results

A handler returns a JSON value. The SDK does not define a common `outputSchema`.

- Return only fields required for the final user response.
- Do not expose raw internal API responses.
- Remove tokens, cookies, certificates, and unnecessary internal identifiers.
- Convert known failures to `ToolExecutionException(code, message, retryable)`.
- Unexpected exceptions are normalized to `TOOL_EXECUTION_FAILED`.

## Review checklist

- Can a model distinguish this tool from similar tools using only its description?
- Does the schema avoid encouraging the model to invent optional inputs?
- Can `additionalProperties: false` be used?
- Does the handler also enforce domain-level validation?
- Is the result free of credentials and unnecessary personal data?
- Are all secrets excluded from the schema, ToolCall, logs, and result?
- Is mobile approval determined by application policy?
