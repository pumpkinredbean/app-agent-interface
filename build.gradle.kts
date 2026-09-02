import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.2.10" apply false
    id("com.android.library") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("io.github.takahirom.roborazzi") version "1.73.0" apply false
}

val sdkGroup = providers.gradleProperty("sdkGroup").getOrElse("io.github.pumpkinredbean")
val sdkVersion = providers.gradleProperty("sdkVersion")
    .orElse(providers.environmentVariable("SDK_VERSION"))
    .getOrElse("0.2.0-SNAPSHOT")
val projectUrl = "https://github.com/pumpkinredbean/app-agent-interface"

require(sdkGroup.isNotBlank()) { "sdkGroup must not be blank" }
require(sdkVersion.isNotBlank()) { "sdkVersion must not be blank" }

allprojects {
    group = sdkGroup
    version = sdkVersion
}

subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            val publishedProject = project

            publications.withType(MavenPublication::class.java).configureEach {
                pom {
                    name.set(publishedProject.name)
                    description.set(publishedProject.description)
                    url.set(projectUrl)

                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("pumpkinredbean")
                            name.set("pumpkinredbean")
                            url.set("https://github.com/pumpkinredbean")
                        }
                    }

                    scm {
                        connection.set("scm:git:https://github.com/pumpkinredbean/app-agent-interface.git")
                        developerConnection.set("scm:git:ssh://git@github.com/pumpkinredbean/app-agent-interface.git")
                        url.set(projectUrl)
                    }
                }
            }

            val githubRepository = providers.gradleProperty("gpr.repository")
                .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
                .orNull

            if (!githubRepository.isNullOrBlank()) {
                val repositoryParts = githubRepository.split("/", limit = 2)
                require(repositoryParts.size == 2 && repositoryParts.all(String::isNotBlank)) {
                    "gpr.repository/GITHUB_REPOSITORY must have the form OWNER/REPOSITORY"
                }

                val owner = repositoryParts[0].lowercase()
                val repository = repositoryParts[1]

                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/$owner/$repository")
                        credentials {
                            username = providers.gradleProperty("gpr.user")
                                .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                                .orNull
                            password = providers.gradleProperty("gpr.key")
                                .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                                .orNull
                        }
                    }
                }
            }
        }
    }
}
