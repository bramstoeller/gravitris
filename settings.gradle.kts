pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle auto-provision JDK 21 on a machine that doesn't already
    // have it, rather than the build silently depending on whatever `java`
    // happens to resolve to. See buildSrc's toolchain convention.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gravitris"

include(":core-sim")
include(":app")
