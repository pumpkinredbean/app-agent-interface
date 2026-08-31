# SDK distribution and installation

## Current registry: GitHub Packages

The publishing workflow uploads both modules to the Maven registry associated with the GitHub repository that contains the SDK source:

```text
io.github.pumpkinredbean:app-agent-runtime:<version>
io.github.pumpkinredbean:app-agent-transport-loopback:<version>
```

Gradle publishing is configured in the repository-root `build.gradle.kts`. Automation lives in `.github/workflows/publish-sdk.yml`.

On GitHub Actions, the build reads `GITHUB_REPOSITORY`, `GITHUB_ACTOR`, and the automatically issued `GITHUB_TOKEN`. No separate publishing PAT is required. The workflow grants `contents: read` and `packages: write`, as required by the [official Gradle publishing guide](https://docs.github.com/en/actions/tutorials/publish-packages/publish-java-packages-with-gradle).

## Publish the first release

Push the repository to GitHub, then push a version tag:

```bash
git tag sdk-v0.1.0
git push origin sdk-v0.1.0
```

The value after `sdk-v` becomes the Maven version. You can also run the `Publish Kotlin SDK` workflow manually and enter a version such as `0.1.0`.

Do not republish an existing release version. Use a new version such as `0.1.1` for every changed release.

## Publish directly from a workstation

When publishing without GitHub Actions, put the following values in the user's `~/.gradle/gradle.properties`. Never create or commit this file inside the project repository.

```properties
gpr.repository=pumpkinredbean/app-agent-interface
gpr.user=GITHUB_USERNAME
gpr.key=CLASSIC_PERSONAL_ACCESS_TOKEN
```

The classic PAT needs `write:packages`, and its account must be able to access the target repository. An organization using SAML SSO may also require explicit organization authorization for the token.

```bash
./gradlew -PsdkVersion=0.1.0 clean test publish
```

## Install in an Android project

GitHub Packages requires authentication for downloads, including public packages. Consumers need a classic PAT with at least `read:packages`. See the [official GitHub Gradle registry authentication guide](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry).

Consumer `~/.gradle/gradle.properties`:

```properties
gpr.user=GITHUB_USERNAME
gpr.key=CLASSIC_PERSONAL_ACCESS_TOKEN
```

Consumer `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "AppAgentInterfaceGitHubPackages"
            url = uri("https://maven.pkg.github.com/pumpkinredbean/app-agent-interface")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
            content {
                includeGroup("io.github.pumpkinredbean")
            }
        }
    }
}
```

Application module `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.pumpkinredbean:app-agent-runtime:0.1.0")
    implementation("io.github.pumpkinredbean:app-agent-transport-loopback:0.1.0")
}
```

When another GitHub Actions repository consumes the SDK, choose one of these options:

1. grant that repository read access in the SDK package settings and use its workflow `GITHUB_TOKEN`;
2. store a classic PAT with `read:packages` as a secret in the consuming repository.

## Local development

A composite build is the fastest option when changing the SDK and application together:

```kotlin
// consuming-project/settings.gradle.kts
includeBuild("../app-agent-interface")
```

The weather agent example in this repository uses this approach.

## GitHub Packages or Maven Central

| Audience | Recommended registry | Reason |
|---|---|---|
| Internal PoC or restricted partners | GitHub Packages | Fast setup using existing GitHub access and Actions |
| Early open-source users with GitHub accounts | GitHub Packages | Simple publishing, but PAT setup is required for downloads |
| Unrestricted public Android developers | Maven Central | Standard unauthenticated dependency installation |

GitHub Packages is appropriate for the current stage. If public adoption grows, add artifact signing and namespace verification and publish the same modules to Maven Central.
