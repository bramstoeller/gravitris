package gravitris.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * docs/security/threat-model.md CHK-1, CHK-3, CHK-4 (chore/threat-model,
 * pending merge — the checks themselves are DevOps's to implement per Stage 0
 * of docs/build-order.md).
 *
 * The critical subtlety, quoted directly because it drove this
 * implementation: "the manifest that ships is the *merged* one, and any
 * dependency at any depth can contribute INTERNET silently — so checking
 * app/src/main/AndroidManifest.xml proves nothing." So this task is wired
 * (in app/build.gradle.kts) to the Android Gradle Plugin's variant API
 * artifact for SingleArtifact.MERGED_MANIFEST, not to a hardcoded
 * intermediates path — the AGP-version-proof way to depend on "whichever
 * task produces the real merged manifest this version of AGP uses."
 *
 * **Fails closed.** If the merged manifest file is missing, empty, or fails
 * to parse as XML, this throws — it never silently passes because it found
 * nothing to inspect. That is the one behaviour the threat model calls out
 * by name as the most likely way this gate quietly stops working.
 *
 * Checks, all against the merged `<manifest>`:
 *  - CHK-1: zero `<uses-permission>` elements of any name.
 *  - CHK-3: `<application>` has `android:allowBackup="false"` and an
 *    `android:dataExtractionRules` attribute present.
 *  - CHK-4: no `<activity>`, `<service>`, `<receiver>` or `<provider>` is
 *    `android:exported="true"` other than the launcher activity.
 */
abstract class CheckMergedManifest : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val variantName: Property<String>

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @TaskAction
    fun check() {
        // Fail closed: RegularFileProperty.get() itself throws with a clear
        // message if the provider (the AGP artifact) could not be resolved,
        // e.g. because the merge task never ran or an AGP upgrade renamed the
        // artifact. We still guard existence explicitly so a stale/empty file
        // from a partial build is treated the same way — as a hard error, not
        // as "nothing to report."
        val file = mergedManifest.asFile.get()
        if (!file.exists() || file.length() == 0L) {
            throw GradleException(
                "CHK-1/3/4 FAIL CLOSED: merged manifest for variant " +
                    "'${variantName.get()}' is missing or empty at " +
                    "${file.absolutePath}. This must never be treated as " +
                    "'nothing to inspect, so pass' — see docs/security/" +
                    "threat-model.md §6. Likely cause: an AGP upgrade moved " +
                    "the merged-manifest task, or task ordering skipped it."
            )
        }

        val document = try {
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder().parse(file)
        } catch (e: Exception) {
            throw GradleException(
                "CHK-1/3/4 FAIL CLOSED: merged manifest at ${file.absolutePath} " +
                    "did not parse as XML: ${e.message}",
                e
            )
        }

        val manifestElement = document.documentElement
            ?: throw GradleException(
                "CHK-1/3/4 FAIL CLOSED: merged manifest at ${file.absolutePath} " +
                    "has no root element."
            )

        val failures = mutableListOf<String>()

        // CHK-1: zero <uses-permission>, from our manifest or any dependency.
        val permissions = manifestElement.getElementsByTagName("uses-permission")
        if (permissions.length > 0) {
            val names = (0 until permissions.length).joinToString(", ") { i ->
                (permissions.item(i) as Element).getAttributeNS(androidNs, "name")
                    .ifBlank { "(unnamed)" }
            }
            failures += "CHK-1: expected zero <uses-permission> elements in the " +
                "merged manifest, found ${permissions.length}: $names"
        }

        // CHK-3: allowBackup=false and dataExtractionRules present (S-1).
        val applicationNodes = manifestElement.getElementsByTagName("application")
        if (applicationNodes.length == 0) {
            failures += "CHK-3: merged manifest has no <application> element."
        } else {
            val application = applicationNodes.item(0) as Element
            val allowBackup = application.getAttributeNS(androidNs, "allowBackup")
            if (allowBackup != "false") {
                failures += "CHK-3: android:allowBackup must be \"false\", " +
                    "found ${if (allowBackup.isBlank()) "(absent, defaults to true)" else "\"$allowBackup\""}."
            }
            val dataExtractionRules =
                application.getAttributeNS(androidNs, "dataExtractionRules")
            if (dataExtractionRules.isBlank()) {
                failures += "CHK-3: android:dataExtractionRules must be set on " +
                    "<application> (belt-and-braces for API 31+, S-1)."
            }
        }

        // CHK-4: no exported component other than the launcher activity.
        val exportedTags = listOf("activity", "activity-alias", "service", "receiver", "provider")
        val exportedOthers = mutableListOf<String>()
        for (tag in exportedTags) {
            val nodes = manifestElement.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                val exported = element.getAttributeNS(androidNs, "exported")
                if (exported != "true") continue
                if (tag == "activity" && isLauncherActivity(element)) continue
                val name = element.getAttributeNS(androidNs, "name").ifBlank { "(unnamed)" }
                exportedOthers += "$tag $name"
            }
        }
        if (exportedOthers.isNotEmpty()) {
            failures += "CHK-4: expected only the launcher activity exported, " +
                "also found: ${exportedOthers.joinToString(", ")}"
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Merged-manifest checks failed for variant '${variantName.get()}' " +
                    "(docs/security/threat-model.md §6):\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }

    private fun isLauncherActivity(activity: Element): Boolean {
        val intentFilters = activity.getElementsByTagName("intent-filter")
        for (i in 0 until intentFilters.length) {
            val filter = intentFilters.item(i) as Element
            val categories = filter.getElementsByTagName("category")
            var hasLauncher = false
            for (j in 0 until categories.length) {
                val category = categories.item(j) as Element
                if (category.getAttributeNS(androidNs, "name") ==
                    "android.intent.category.LAUNCHER"
                ) {
                    hasLauncher = true
                }
            }
            if (hasLauncher) return true
        }
        return false
    }
}
