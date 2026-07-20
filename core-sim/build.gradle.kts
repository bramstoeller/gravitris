import gravitris.buildlogic.CheckNoAndroidDependency

// :core-sim — pure Kotlin/JVM, framework-free (ADR 0008). Physics and game
// rules live here so they can be unit-tested deterministically on the JVM,
// with no Android device or emulator involved.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

// Structural guard, belt-and-braces alongside checkNoAndroidDependency below:
// this module must never gain an Android Gradle plugin. If one is ever
// applied — even transitively through a convention plugin — fail immediately
// rather than let checkNoAndroidDependency be the only line of defence.
listOf(
    "com.android.application",
    "com.android.library",
    "org.jetbrains.kotlin.android",
).forEach { pluginId ->
    plugins.withId(pluginId) {
        throw GradleException(
            "ADR 0008 violation: :core-sim must never apply the '$pluginId' " +
                "plugin. :core-sim is pure Kotlin/JVM — Android belongs in :app."
        )
    }
}

dependencies {
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// ADR 0008 / docs/build-order.md Stage 0: ":core-sim has no Android
// dependency ... write it now, or it is violated within a week."
val checkNoAndroidDependency = tasks.register<CheckNoAndroidDependency>("checkNoAndroidDependency") {
    group = "verification"
    description = "Fails if :core-sim acquires any Android dependency or import (ADR 0008)."
    moduleIdentifiers.set(
        provider {
            listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
                .flatMap { name ->
                    configurations.getByName(name).incoming.resolutionResult.allDependencies
                        .mapNotNull { result ->
                            (result as? org.gradle.api.artifacts.result.ResolvedDependencyResult)
                                ?.selected?.moduleVersion
                                ?.let { "${it.group}:${it.name}" }
                        }
                }
                .distinct()
                .sorted()
        }
    )
    sourceFiles.setFrom(
        fileTree("src/main/kotlin"),
        fileTree("src/test/kotlin"),
    )
}

tasks.named("check") {
    dependsOn(checkNoAndroidDependency)
}
