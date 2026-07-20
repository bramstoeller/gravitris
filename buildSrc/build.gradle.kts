plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Needed to reference the Android Gradle Plugin's variant API
    // (AndroidComponentsExtension, SingleArtifact.MERGED_MANIFEST) from the
    // merged-manifest check task registered in app/build.gradle.kts.
    // Pin matches gradle/libs.versions.toml — buildSrc can't read the root
    // catalog, so this version is kept in sync by hand; see docs/operations.md.
    compileOnly("com.android.tools.build:gradle:8.9.3")
}

kotlin {
    jvmToolchain(21)
}

// R2 of docs/security/dependency-policy.md (chore/threat-model, pending
// merge): buildSrc is a separate build, so the root build.gradle.kts's
// `allprojects { dependencyLocking { ... } }` never reaches it — review
// finding S-3. buildSrc pulls the entire AGP 8.9.3 tree (transitively, a few
// hundred artifacts) and, until this, was pinned only by the direct
// coordinate plus checksum verification, not a locked graph. Update via
// `./gradlew :buildSrc:dependencies --write-locks`.
dependencyLocking {
    lockAllConfigurations()
}
