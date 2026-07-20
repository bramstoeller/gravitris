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
 *  - CHK-1: no `<uses-permission>` outside [ALLOWED_PERMISSIONS], and never
 *    `INTERNET` regardless of that list.
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

        // Every allowlist entry must carry a justification. This makes widening
        // the allowlist a deliberate, self-documenting act rather than a
        // one-word edit that reads as noise in a diff.
        val unjustified = ALLOWED_PERMISSIONS.filterValues { it.isBlank() }.keys
        if (unjustified.isNotEmpty()) {
            throw GradleException(
                "CHK-1 FAIL CLOSED: allowlist entries with no justification: " +
                    "${unjustified.joinToString(", ")}. Every permission in " +
                    "ALLOWED_PERMISSIONS must record why it is permitted — see " +
                    "docs/security/threat-model.md §5."
            )
        }

        // CHK-1: no permission outside the allowlist, from our manifest or any
        // dependency's, at any depth.
        //
        // Match EVERY element whose tag name starts with "uses-permission",
        // not just the literal <uses-permission>. Android has sibling elements
        // that request a permission just as effectively:
        //
        //   <uses-permission-sdk-23 android:name="android.permission.INTERNET"/>
        //
        // requests the permission on API 23+. minSdk is 29, so that is every
        // device this app supports — it is a total bypass, not a partial one.
        // The original CHK-1 spec said "zero <uses-permission> elements" and
        // this task implemented exactly that, so a dependency contributing the
        // sdk-23 form would have been granted INTERNET on 100% of the install
        // base while the build stayed green.
        //
        // Prefix-matching rather than enumerating the known variants is
        // deliberate: it is the fail-closed choice. A future platform version
        // adding another <uses-permission-*> form is caught by default instead
        // of being silently missed until someone remembers to update a list.
        val unexpected = mutableListOf<String>()
        var declaresInternet = false
        val allElements = manifestElement.getElementsByTagName("*")
        for (i in 0 until allElements.length) {
            val element = allElements.item(i) as Element
            if (!element.tagName.startsWith(PERMISSION_ELEMENT_PREFIX)) continue
            val name = element.getAttributeNS(androidNs, "name")
                .ifBlank { "(unnamed)" }
            if (name == INTERNET_PERMISSION) declaresInternet = true
            if (name !in ALLOWED_PERMISSIONS) {
                // Report the tag too: "<uses-permission-sdk-23> FOO" is a very
                // different thing to debug than "FOO", and the reader needs to
                // know which form slipped in.
                unexpected += "<${element.tagName}> $name"
            }
        }

        if (unexpected.isNotEmpty()) {
            failures += "CHK-1: merged manifest declares ${unexpected.size} " +
                "permission(s) outside the allowlist: ${unexpected.joinToString(", ")}. " +
                "The allowlist is ${ALLOWED_PERMISSIONS.keys.joinToString(", ")}. " +
                "Adding to it is a security-engineer decision recorded in " +
                "docs/security/threat-model.md, not a build fix."
        }

        // Belt-and-braces, and deliberately independent of the allowlist: even
        // if someone adds INTERNET to ALLOWED_PERMISSIONS, this still fails.
        // The no-network guarantee is the one the client asked to have enforced
        // rather than intended, so it does not get to depend on a list staying
        // correct.
        if (declaresInternet) {
            failures += "CHK-1: merged manifest declares $INTERNET_PERMISSION. " +
                "This is never permitted — the brief's no-telemetry guarantee is " +
                "kernel-enforced by the absence of this permission " +
                "(docs/security/threat-model.md §5)."
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

    private companion object {
        const val INTERNET_PERMISSION = "android.permission.INTERNET"

        /**
         * Matches `<uses-permission>` and every sibling form that requests a
         * permission — notably `<uses-permission-sdk-23>`. See the scan in
         * [check] for why this is a prefix match and not an exact one.
         */
        const val PERMISSION_ELEMENT_PREFIX = "uses-permission"

        /**
         * The complete set of permissions this app may declare, each mapped to
         * the reason it is allowed.
         *
         * CHK-1 originally asserted **zero** permissions, which was correct
         * when the app did nothing. Stage 1B needs `VIBRATE` for impact
         * haptics: `Vibrator.vibrate()` requires it, and the permission-free
         * alternative (`View.performHapticFeedback`) plays a fixed canned
         * effect with no amplitude control, discarding the energy ramp in
         * docs/ux/feel-feedback.md that makes the blocks read as heavy.
         *
         * An allowlist rather than deleting the check, because the property
         * worth protecting was never "zero permissions" — it was "no
         * permission arrives that nobody decided on". A dependency
         * contributing anything at all still turns the build red, which is the
         * behaviour the client asked for.
         *
         * **The value is a justification, not documentation.** The task fails
         * if any entry's justification is blank, so widening this list cannot
         * be a one-word edit — you must state why, in the same diff, and that
         * makes the change legible to a reviewer who is skimming.
         *
         * Be clear about what this does and does not do: it removes the
         * *silent* path, not the determined one. Nothing here can stop an
         * author who is willing to write a justification. The control that
         * would actually gate this is a CODEOWNERS entry covering the
         * `buildSrc` tree (note: not written with a glob here, because Kotlin
         * block comments nest and a stray comment-opener would silently
         * swallow the rest of this file) plus branch protection requiring
         * code-owner review — which only
         * bites once merges go through pull requests on origin rather than
         * locally. Recorded as the follow-up in
         * .team/reviews/security-chk1-allowlist.md.
         *
         * Adding an entry here is a security-engineer decision and belongs in
         * docs/security/threat-model.md §5, not in a build fix.
         */
        val ALLOWED_PERMISSIONS = mapOf(
            "android.permission.VIBRATE" to
                "Impact haptics scaled to mass and fall speed (docs/ux/" +
                "feel-feedback.md). Normal install-time permission: no runtime " +
                "prompt, no data access, no network. Approved by Security " +
                "Engineer, see .team/reviews/security-chk1-allowlist.md.",
        )
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
