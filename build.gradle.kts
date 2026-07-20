// Root build. Declares plugins once (centralizes version resolution via the
// version catalog) but applies them per-module. See gradle/libs.versions.toml
// for every pinned version — R1 of the dependency policy.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// R2 of docs/security/dependency-policy.md (chore/threat-model, pending
// merge): lock the full resolved dependency graph, not just the direct,
// pinned versions in the catalog. A lockfile change is a reviewable diff.
// Run `./gradlew dependencies --write-locks` to update after a deliberate
// version bump.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
