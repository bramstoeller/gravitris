import com.android.build.api.artifact.SingleArtifact
import gravitris.buildlogic.CheckMergedManifest

// :app — the Android shell (ADR 0008). Depends on :core-sim, owns everything
// platform-specific: the GL renderer, input, haptics, settings, lifecycle.
// Stage 0 ships only a placeholder Activity — see MainActivity.kt — so the
// module compiles, installs and produces the debug APK this stage is
// responsible for. The real shell is Stage 1B (docs/build-order.md).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "nl.brainbuilders.gravitris"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "nl.brainbuilders.gravitris"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1-stage0"
    }

    buildTypes {
        release {
            // No release signing in Stage 0 (Stage 5) and no keystore may
            // ever enter this container — the release build type exists so
            // the merged-manifest check can also run against it (CHK-1/3/4
            // apply to both variants), not to produce a shippable artifact
            // yet. `isMinifyEnabled` stays off until there is real code
            // worth shrinking; flip it on in Stage 5 alongside signing.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.all {
            // Same engine as :core-sim, so `make test` runs one kind of test.
            it.useJUnitPlatform()
        }
        // The gesture, frame-stats and haptic-curve logic is written as plain
        // Kotlin taking coordinates and timestamps rather than MotionEvent, so
        // it needs no Android runtime. This flag covers the remaining
        // incidental android.jar references and keeps the suite runnable in a
        // container with no device and no emulator — which is the only kind of
        // container this project has (docs/operations.md).
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core-sim"))

    // JUnit 5, matching :core-sim. No Robolectric and no AndroidX Test: the
    // logic worth testing here (gesture recognition, the frame-time
    // statistics, the haptic energy curve) is deliberately written free of
    // Android types so it can be tested on the plain JVM. Adding an Android
    // test runtime to reach it would be adding a dependency to work around a
    // design choice made specifically to avoid needing one — and
    // docs/security/dependency-policy.md R5 sets a high bar for new
    // dependencies.
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// docs/security/threat-model.md CHK-1, CHK-3, CHK-4 — merged-manifest checks,
// wired via the AGP variant API so the artifact path survives an AGP upgrade
// (see the kdoc on CheckMergedManifest for why this matters).
androidComponents {
    onVariants { variant ->
        val checkTask = tasks.register<CheckMergedManifest>(
            "check${variant.name.replaceFirstChar { it.uppercase() }}MergedManifest"
        ) {
            group = "verification"
            description = "Asserts the merged manifest for '${variant.name}' has no " +
                "permissions, allowBackup=false, and no unexpected exported components " +
                "(threat-model.md CHK-1/3/4)."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            variantName.set(variant.name)
        }
        tasks.named("check") {
            dependsOn(checkTask)
        }
    }
}
