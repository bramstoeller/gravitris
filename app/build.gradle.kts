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
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core-sim"))
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
