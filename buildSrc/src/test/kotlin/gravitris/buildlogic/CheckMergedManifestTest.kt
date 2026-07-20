package gravitris.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * docs/security/threat-model.md CHK-1/3/4, and the reason this file exists
 * at all: PR #3's review (.team/reviews/security-chk1-allowlist.md) found a
 * High-severity total bypass of CHK-1 (`<uses-permission-sdk-23>` matched
 * nothing, because the scan looked for the exact tag name
 * `uses-permission`) that had never been caught, because this task had zero
 * tests and had only ever been verified by *reading* it. Reading it is
 * exactly what missed the bypass.
 *
 * The same lesson recurred for CHK-4: PR #1's review
 * (.team/reviews/review-build-foundation.md) found `isLauncherActivity`
 * exempted *every* exported activity carrying category LAUNCHER, not the
 * app's single launcher — so a merged-in second launcher activity (ad/
 * analytics SDKs contribute these) shipped a foreign exported entry point
 * green. That gap had no test either. The CHK-4 cases below pin down the
 * tightened rule: exempt at most one launcher (MAIN + LAUNCHER together),
 * fail closed on more than one, and never exempt on a lone LAUNCHER category
 * or an activity-alias.
 */
class CheckMergedManifestTest {

    private fun task(variant: String = "debug"): CheckMergedManifest {
        val project = ProjectBuilder.builder().build()
        val check = project.tasks.create("checkMergedManifestTest$variant", CheckMergedManifest::class.java)
        check.variantName.set(variant)
        return check
    }

    private fun manifestFile(dir: File, contents: String): File {
        val file = File(dir, "AndroidManifest.xml")
        file.writeText(contents)
        return file
    }

    // --- Fails closed -------------------------------------------------

    @Test
    fun `fails closed when merged manifest file does not exist`(@TempDir dir: File) {
        val check = task()
        check.mergedManifest.set(File(dir, "does-not-exist.xml"))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("FAIL CLOSED"), "expected a fail-closed message, got: ${ex.message}")
    }

    @Test
    fun `fails closed when merged manifest file is empty`(@TempDir dir: File) {
        val check = task()
        val file = manifestFile(dir, "")
        check.mergedManifest.set(file)

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("FAIL CLOSED"), "expected a fail-closed message, got: ${ex.message}")
    }

    @Test
    fun `fails closed when merged manifest does not parse as XML`(@TempDir dir: File) {
        val check = task()
        val file = manifestFile(dir, "this is not xml at all <<<")
        check.mergedManifest.set(file)

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("did not parse as XML"), "got: ${ex.message}")
    }

    // --- A clean manifest passes ---------------------------------------

    private val cleanManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules">
                <activity android:name=".MainActivity" android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN"/>
                        <category android:name="android.intent.category.LAUNCHER"/>
                    </intent-filter>
                </activity>
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `a clean manifest passes all three checks`(@TempDir dir: File) {
        val check = task()
        check.mergedManifest.set(manifestFile(dir, cleanManifest))

        check.check() // must not throw
    }

    // --- CHK-1: zero uses-permission ------------------------------------

    @Test
    fun `CHK-1 fails when a uses-permission element is present`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET"/>
                <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules"/>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-1"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("android.permission.INTERNET"), "expected the offending permission named in the failure, got: ${ex.message}")
    }

    @Test
    fun `CHK-1 names every offending permission, not just the first`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET"/>
                <uses-permission android:name="android.permission.CAMERA"/>
                <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules"/>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("android.permission.INTERNET"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("android.permission.CAMERA"), "got: ${ex.message}")
    }

    // --- CHK-3: allowBackup + dataExtractionRules -----------------------

    @Test
    fun `CHK-3 fails when allowBackup is missing (defaults to true)`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:dataExtractionRules="@xml/data_extraction_rules"/>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-3"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("allowBackup"), "got: ${ex.message}")
    }

    @Test
    fun `CHK-3 fails when allowBackup is explicitly true`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:allowBackup="true" android:dataExtractionRules="@xml/data_extraction_rules"/>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-3"), "got: ${ex.message}")
    }

    @Test
    fun `CHK-3 fails when dataExtractionRules is missing`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:allowBackup="false"/>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-3"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("dataExtractionRules"), "got: ${ex.message}")
    }

    @Test
    fun `CHK-3 fails when there is no application element at all`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"/>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("no <application> element"), "got: ${ex.message}")
    }

    // --- CHK-4: no exported component other than the launcher activity --

    @Test
    fun `CHK-4 fails when a non-launcher activity is exported`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                    <activity android:name=".ShareTargetActivity" android:exported="true"/>
                </application>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-4"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains(".ShareTargetActivity"), "got: ${ex.message}")
    }

    @Test
    fun `CHK-4 fails when a receiver is exported`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules">
                    <receiver android:name=".BootReceiver" android:exported="true"/>
                </application>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-4"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("receiver .BootReceiver"), "got: ${ex.message}")
    }

    @Test
    fun `CHK-4 does not flag the launcher activity itself`(@TempDir dir: File) {
        // The clean manifest already exports exactly the launcher activity;
        // this pins down that CHK-4's launcher exemption actually fires,
        // not just that a manifest with no other components happens to pass.
        val check = task()
        check.mergedManifest.set(manifestFile(dir, cleanManifest))

        check.check() // must not throw
    }

    @Test
    fun `CHK-4 fails when a second exported launcher activity is present`(@TempDir dir: File) {
        // The exact escalated gap (PR #1 review): a dependency contributes a
        // second exported activity with a MAIN/LAUNCHER intent-filter — ad and
        // analytics SDKs do this on manifest merge. The old code exempted EVERY
        // launcher, so this foreign entry point shipped green. It must now fail,
        // and BOTH launchers must be named so the ambiguity is visible.
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules">
                    <activity android:name="gravitris.app.MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                    <activity android:name="com.ads.sdk.OfferwallActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-4"), "got: ${ex.message}")
        assertTrue(
            ex.message!!.contains("exactly one exported launcher"),
            "expected the multi-launcher ambiguity to be called out, got: ${ex.message}"
        )
        assertTrue(ex.message!!.contains("gravitris.app.MainActivity"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("com.ads.sdk.OfferwallActivity"), "got: ${ex.message}")
    }

    @Test
    fun `CHK-4 exempts the app launcher even though its package differs from applicationId`(@TempDir dir: File) {
        // The app's launcher class (gravitris.app.MainActivity) deliberately
        // sits under a different package than the applicationId
        // (nl.brainbuilders.gravitris) — see app/src/main/AndroidManifest.xml.
        // The exemption keys off the MAIN/LAUNCHER intent-filter, NOT a name or
        // namespace match, precisely so the real app passes. This is the trap a
        // "component name must match the namespace" fix would have fallen into.
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="nl.brainbuilders.gravitris">
                <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules">
                    <activity android:name="gravitris.app.MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        check.check() // must not throw
    }

    @Test
    fun `CHK-4 does not exempt an exported activity with LAUNCHER category but no MAIN action`(@TempDir dir: File) {
        // A lone category LAUNCHER without action MAIN is not a real launcher
        // entry point. The tightened exemption requires both in one filter, so
        // such an exported activity is reported like any other exported
        // component rather than silently exempted.
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules">
                    <activity android:name=".NotReallyALauncher" android:exported="true">
                        <intent-filter>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-4"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains(".NotReallyALauncher"), "got: ${ex.message}")
    }

    @Test
    fun `CHK-4 does not exempt an exported launcher activity-alias`(@TempDir dir: File) {
        // The exemption is for a plain <activity>. An exported <activity-alias>
        // carrying a MAIN/LAUNCHER filter (a dependency could add one) is never
        // exempt — it is a distinct exported entry point.
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules">
                    <activity android:name="gravitris.app.MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                    <activity-alias android:name=".AliasEntry" android:exported="true" android:targetActivity="gravitris.app.MainActivity">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity-alias>
                </application>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-4"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("activity-alias .AliasEntry"), "got: ${ex.message}")
    }

    @Test
    fun `all applicable failures are reported together, not just the first`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET"/>
                <application android:allowBackup="true">
                    <receiver android:name=".BootReceiver" android:exported="true"/>
                </application>
            </manifest>
        """.trimIndent()
        val check = task()
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("CHK-1"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("CHK-3"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("CHK-4"), "got: ${ex.message}")
    }

    @Test
    fun `failure message names the variant`(@TempDir dir: File) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.INTERNET"/>
            </manifest>
        """.trimIndent()
        val check = task(variant = "release")
        check.mergedManifest.set(manifestFile(dir, manifest))

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("'release'"), "got: ${ex.message}")
    }
}
