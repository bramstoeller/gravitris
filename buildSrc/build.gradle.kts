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
