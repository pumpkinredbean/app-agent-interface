# App Agent Interface Kotlin SDK

A Kotlin runtime that lets an Android application expose its own service capabilities as model-callable tools. Desktop agents and future in-app agents can execute the same registered handlers through a single runtime.

This SDK is not an MCP implementation, an LLM provider SDK, an agent product, or a predefined tool catalog. It is the application-side runtime for registering, validating, approving, executing, and transporting application-defined tools through `app-agent/1`.

## Modules

| Module | Responsibility |
|---|---|
| `app-agent-runtime` | `ToolSpec`, registry, input validation, approval policy, execution, and JSON codec |
| `app-agent-transport-loopback` | Android/JVM loopback HTTP server and ADB-forwardable endpoints |

The default local version is `0.1.0-SNAPSHOT`, and the JVM target is 17. Release versions are injected by the GitHub Actions workflow from a tag or manual input.

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

Pushing a tag such as `sdk-v0.1.0` publishes both modules to the repository's GitHub Packages Maven registry. Add the registry and credentials to the consuming project:

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
    implementation("io.github.pumpkinredbean:app-agent-runtime:0.1.0")
    implementation("io.github.pumpkinredbean:app-agent-transport-loopback:0.1.0")
}
```

GitHub Packages requires authentication even when the package is public. See [Distribution and installation](../docs/sdk/distribution.md) for the complete PAT and `read:packages` setup.

## Local development

Use a composite build when developing the SDK and application together:

```kotlin
// consuming-project/settings.gradle.kts
includeBuild("../app-agent-interface/sdk")
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
val runtime = ToolRuntime(
    approvalHandler = ToolApprovalHandler { request ->
        approvalCoordinator.awaitUserDecision(request)
    },
)

runtime.register(spec, approval = ToolApproval.MOBILE) { input, context ->
    sensitiveOperation(input)
}
```

If no approval handler is available, execution returns `APPROVAL_UNAVAILABLE`. If the user declines, execution returns `APPROVAL_DENIED` and the application handler is not invoked.

Java applications can adapt a blocking Activity coordinator without implementing a Kotlin suspending interface:

```java
ToolRuntime runtime = ToolRuntime.withBlockingApproval(
    request -> approvalCoordinator.awaitDecision(request)
);
```

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
- `app-agent/1` round trips;
- loopback binding, bearer authentication, and real HTTP calls;
- Java interoperability for existing Android applications.

## Documentation

- [Architecture](../docs/sdk/architecture.md)
- [Distribution and installation](../docs/sdk/distribution.md)
- [Tool authoring](../docs/sdk/tool-authoring.md)
- [Android integration](../docs/sdk/android-integration.md)
- [Protocol](../docs/sdk/protocol.md)
- [Optional Android UI fallback](../docs/sdk/android-ui-automation.md)
- [PoC migration](../docs/sdk/migration.md)
- [Runnable example](../examples/weather-agent/README.md)
