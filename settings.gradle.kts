pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "app-agent-interface"

include(":app-agent-runtime")
include(":app-agent-transport-loopback")
include(":app-agent-ui-compose")

project(":app-agent-runtime").projectDir = file("packages/app-agent-runtime")
project(":app-agent-transport-loopback").projectDir = file("packages/app-agent-transport-loopback")
project(":app-agent-ui-compose").projectDir = file("packages/app-agent-ui-compose")
