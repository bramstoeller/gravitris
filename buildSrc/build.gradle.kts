plugins {
    // Pulls in `java-gradle-plugin` under the hood, which is where the
    // build-time "No valid plugin descriptors were found in
    // META-INF/gradle-plugins" message comes from (:buildSrc:jar). It is
    // expected and harmless here: this buildSrc exposes plain Task classes
    // (CheckMergedManifest, CheckNoAndroidDependency) referenced directly by
    // app/build.gradle.kts, not registered Gradle plugins with an id, so
    // there is nothing for `java-gradle-plugin`'s plugin-descriptor
    // machinery to find. `kotlin-dsl` is applied for the type-safe Kotlin
    // DSL accessors and `gradleApi()`/`gradleKotlinDsl()`, not to publish a
    // plugin.
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

    // CheckMergedManifest and CheckNoAndroidDependency are security/ADR-0008
    // guard tasks (docs/security/threat-model.md CHK-1/3/4) that had zero
    // tests until now, verified only by reading — which is exactly how the
    // <uses-permission-sdk-23> total bypass of CHK-1 survived a review (PR
    // #3, .team/reviews/security-chk1-allowlist.md). Same JUnit version as
    // :core-sim/:app; buildSrc can't read the root version catalog (see the
    // compileOnly comment above), so this is pinned by hand.
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
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
