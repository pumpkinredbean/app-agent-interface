# SDK distribution and installation

## Current registry: GitHub Packages

The current Kotlin/Maven release is `0.4.0`.

The publishing workflow uploads the runtime, transport, optional UI modules,
and debug Inspector pair to the Maven registry associated with the GitHub
repository that contains the SDK source:

```text
io.github.pumpkinredbean:app-agent-runtime:<version>
io.github.pumpkinredbean:app-agent-transport-loopback:<version>
io.github.pumpkinredbean:app-agent-ui:<version>
io.github.pumpkinredbean:app-agent-ui-compose:<version>
io.github.pumpkinredbean:app-agent-inspector:<version>
io.github.pumpkinredbean:app-agent-inspector-noop:<version>
```

Gradle publishing is configured in the repository-root `build.gradle.kts`. Automation lives in `.github/workflows/publish-sdk.yml`.

The `sdk-v*` workflow publishes only the six Kotlin/Maven artifacts above. The
TypeScript workspace packages currently remain at `0.1.0` and are not published
to npm by this workflow.

On GitHub Actions, the build reads `GITHUB_REPOSITORY`, `GITHUB_ACTOR`, and the automatically issued `GITHUB_TOKEN`. No separate publishing PAT is required. The workflow grants `contents: read` and `packages: write`, as required by the [official Gradle publishing guide](https://docs.github.com/en/actions/tutorials/publish-packages/publish-java-packages-with-gradle).

## Publish a release

Start from a clean, CI-green `main`, choose a version that has never been
published, and push an annotated tag:

```bash
VERSION=X.Y.Z
git tag -a "sdk-v${VERSION}" -m "App Agent Interface SDK ${VERSION}"
git push origin "sdk-v${VERSION}"
```

The value after `sdk-v` becomes the Maven version. You can also run the `Publish Kotlin SDK` workflow manually and enter the version.

Do not move a published tag or republish an existing release version. After a
successful release, advance the repository's default `-SNAPSHOT` version and
the composite-build example coordinates to the next intended version.

### Release checklist

1. Move completed Changelog entries from `Unreleased` to the dated version.
2. Run `npm run docs:check`, the Kotlin test/assembly tasks, TypeScript checks,
   and both Android example builds.
3. Merge the release documentation and wait for `main` CI to pass.
4. Tag that exact `main` commit and wait for `Publish Kotlin SDK` to succeed.
5. Resolve all six artifacts from a clean consumer project using the released
   version and GitHub Packages credentials.
6. Bump the local development line to the next `-SNAPSHOT` version.

## Publish directly from a workstation

When publishing without GitHub Actions, put the following values in the user's `~/.gradle/gradle.properties`. Never create or commit this file inside the project repository.

```properties
gpr.repository=pumpkinredbean/app-agent-interface
gpr.user=GITHUB_USERNAME
gpr.key=CLASSIC_PERSONAL_ACCESS_TOKEN
```

The classic PAT needs `write:packages`, and its account must be able to access the target repository. An organization using SAML SSO may also require explicit organization authorization for the token.

```bash
VERSION=X.Y.Z
./gradlew -PsdkVersion="$VERSION" clean test publish
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
    implementation("io.github.pumpkinredbean:app-agent-runtime:0.4.0")
    implementation("io.github.pumpkinredbean:app-agent-transport-loopback:0.4.0")
    // Optional:
    implementation("io.github.pumpkinredbean:app-agent-ui:0.4.0")
    implementation("io.github.pumpkinredbean:app-agent-ui-compose:0.4.0")

    // Optional debug audit UI with a release-safe API:
    debugImplementation("io.github.pumpkinredbean:app-agent-inspector:0.4.0")
    releaseImplementation("io.github.pumpkinredbean:app-agent-inspector-noop:0.4.0")
}
```

Do not place both Inspector artifacts in the same variant. They intentionally
provide the same public `AppAgentInspector` API.

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
