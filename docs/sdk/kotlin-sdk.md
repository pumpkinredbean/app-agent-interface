# App Agent Interface Kotlin SDK

A Kotlin runtime that lets an Android application expose its own service capabilities as model-callable tools. Desktop agents and future in-app agents can execute the same registered handlers through a single runtime.

This SDK is not an MCP implementation, an LLM provider SDK, an agent product, or a predefined tool catalog. It is the application-side runtime for registering, validating, approving, executing, and transporting application-defined tools through `app-agent/1`.

## Modules

| Module | Responsibility |
|---|---|
| `app-agent-runtime` | `ToolSpec`, registry, input validation, approval policy, execution, and JSON codec |
| `app-agent-transport-loopback` | Android/JVM loopback HTTP server and ADB-forwardable endpoints |
| `app-agent-ui-compose` | Optional FIFO approval host, Material 3 dialog, and background notification bridge |

The default local version is `0.2.0-SNAPSHOT`, and the JVM target is 17. Release versions are injected by the GitHub Actions workflow from a tag or manual input.

## Tool specification

The model-visible contract contains exactly three fields:

```kotlin
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)
```

Handlers, mobile approval, authentication state, transport settings, and call IDs are not part of `ToolSpec`.

## Install from GitHub Packages

Pushing a tag such as `sdk-v0.2.0` publishes all Kotlin artifacts to the repository's GitHub Packages Maven registry. Add the registry and credentials to the consuming project:

```kotlin
// consuming-project/settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/pumpkinredbean/app-agent-interface")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
            content { includeGroup("io.github.pumpkinredbean") }
        }
    }
}
```

```kotlin
// consuming-project/build.gradle.kts
dependencies {
    implementation("io.github.pumpkinredbean:app-agent-runtime:0.2.0")
    implementation("io.github.pumpkinredbean:app-agent-transport-loopback:0.2.0")
    // Optional:
    implementation("io.github.pumpkinredbean:app-agent-ui-compose:0.2.0")
}
```

GitHub Packages requires authentication even when the package is public. See [Distribution and installation](distribution.md) for the complete PAT and `read:packages` setup.

## Local development

Use a composite build when developing the SDK and application together:

```kotlin
// consuming-project/settings.gradle.kts
includeBuild("../app-agent-interface")
```

Alternatively, publish the artifacts to the local Maven repository:

```bash
./gradlew publishToMavenLocal
```

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
```

## Register a tool

Application credentials and repositories stay in the handler closure. They are never added to the ToolCall payload.

```kotlin
import io.github.pumpkinredbean.appagent.ToolApproval
import io.github.pumpkinredbean.appagent.ToolCall
import io.github.pumpkinredbean.appagent.ToolJson
import io.github.pumpkinredbean.appagent.ToolRuntime
import io.github.pumpkinredbean.appagent.ToolSpec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

val runtime = ToolRuntime()

runtime.register(
    spec = ToolSpec(
        name = "get_account_balance",
        description =
            "Returns the signed-in user's account balance and available amount. " +
            "Use it when the user asks about their balance or available funds. " +
            "Do not use it to place an order or transfer funds.",
        inputSchema = ToolJson.parseObject(
            """
            {
              "type": "object",
              "properties": {
                "accountId": {
                  "type": "string",
                  "description": "Internal account ID. Omit it to use the default account."
                }
              },
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
    ),
    approval = ToolApproval.NONE,
) { input, _ ->
    val accountId = input["accountId"]?.jsonPrimitive?.content
    val balance = accountRepository.getBalance(accountId)
    buildJsonObject {
        put("availableAmount", balance.availableAmount)
        put("currency", balance.currency)
    }
}
```

## Expose the loopback transport

For a USB-connected device or emulator, start the loopback server and forward it through ADB:

```kotlin
import io.github.pumpkinredbean.appagent.transport.loopback.LoopbackToolServer

val server = LoopbackToolServer(
    runtime = runtime,
    port = 8765,
    bearerToken = pairingSessionToken,
)
server.start()

// Android Service.onDestroy()
server.close()
```

```bash
adb forward tcp:18765 tcp:8765
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:18765/v1/tools
```

An in-app agent bypasses transport and calls the same runtime directly:

```kotlin
val result = runtime.execute(
    ToolCall(
        id = UUID.randomUUID().toString(),
        name = "get_account_balance",
        input = buildJsonObject {},
    ),
    attributes = mapOf("caller" to "in-app-agent"),
)
```

## Mobile approval

Approval is registration policy, not model-visible schema:

```kotlin
val approvals = ToolApprovalController(
    ToolApprovalConfiguration(
        decisionTimeoutMillis = 60_000L,
        maxPendingRequests = 16,
    ),
)

val runtime = ToolRuntime(
    approvalDecisionHandler = approvals,
)

runtime.register(spec, approval = ToolApproval.MOBILE) { input, context ->
    sensitiveOperation(input)
}
```

The application or dependency-injection graph owns one controller for the runtime. It serializes concurrent requests into a bounded FIFO queue. Queued requests do not consume decision time; the default 60-second deadline starts when the current request is visibly presented.

If no approval handler is available, execution returns `APPROVAL_UNAVAILABLE`. A decline returns `APPROVAL_DENIED`; an expired presented request returns retryable `APPROVAL_TIMEOUT`. In every failure case, the application handler is not invoked.

The earlier Boolean callback remains available for applications with an existing coordinator:

```java
ToolRuntime runtime = ToolRuntime.withBlockingApproval(
    request -> approvalCoordinator.awaitDecision(request)
);
```

Compose applications install one lifecycle-aware host with the optional UI artifact:

```kotlin
ToolApprovalHost(controller = approvals)
```

The same dialog advances through later requests, shows the current countdown and later-request count, and treats dismissal as denial. Static approval labels and button text are SDK-owned Android string resources. Dynamic content is derived only from the app-registered `ToolSpec.description` and the actual `ToolCall.name` and `ToolCall.input`; the dialog does not accept model-authored explanatory prose or a separate presentation payload.

Secrets such as PINs, OTPs, passwords, and access tokens must not be fields in a tool schema or ToolCall. After approving the non-secret intent, collect required secrets in the application's existing secure UI. See [On-device tool approval](on-device-approval.md) and [Compose approval UI](compose-approval-ui.md).

## Endpoints

`app-agent-transport-loopback` binds only to `127.0.0.1`.

| Method | Path | Response |
|---|---|---|
| `GET` | `/healthz` | Runtime health |
| `GET` | `/v1/tools` | `tools.list` manifest |
| `POST` | `/v1/tool-calls` | `tool.result` |

`/v1/capabilities` is an early PoC compatibility alias. New consumers should use `/v1/tools`.

## Verification

```bash
./gradlew test
```

The test suite covers:

- exact three-field ToolSpec serialization;
- registration, validation, execution, and controlled errors;
- approval denial without handler execution;
- FIFO approval concurrency, presentation-only timeout, cancellation, and fail-closed shutdown;
- privacy-safe approval notification content;
- `app-agent/1` round trips;
- loopback binding, bearer authentication, and real HTTP calls;
- Java interoperability for existing Android applications.

## Documentation

- [Architecture](architecture.md)
- [Distribution and installation](distribution.md)
- [Tool authoring](tool-authoring.md)
- [Android integration](android-integration.md)
- [On-device approval](on-device-approval.md)
- [Compose approval UI](compose-approval-ui.md)
- [Approval testing](approval-testing.md)
- [Approval troubleshooting](approval-troubleshooting.md)
- [Protocol](protocol.md)
- [Optional Android UI fallback](android-ui-automation.md)
- [PoC migration](migration.md)
- [Runnable example](../../examples/weather-agent/README.md)
