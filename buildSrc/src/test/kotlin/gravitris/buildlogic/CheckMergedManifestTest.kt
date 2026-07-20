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
 * These tests exercise the version of the check in *this* branch
 * (chore/build-foundation) — the sdk-23 prefix-matching fix lives on
 * feat/app-shell (PR #3) and is this branch's descendant, not yet merged
 * here. Whoever merges that fix should extend this file with a case for it
 * rather than let it land untested again.
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
