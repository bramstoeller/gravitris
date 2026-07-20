package gravitris.buildlogic

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * ADR 0008: ":core-sim has no Android dependency. Not 'minimal' — none."
 * This task is, per its own KDoc, "the single most valuable check in the
 * Stage 0 build" — reason enough on its own to be the first thing tested
 * here, independent of the CHK-1 bypass that made buildSrc tests overdue.
 *
 * The three checks are independent by design (see the class KDoc) and are
 * tested independently here for the same reason: a resolved-dependency scan
 * and an unresolved-dependency scan and a source-import scan can each be
 * broken without breaking the other two, and a test suite that only ever
 * exercises them together could miss that.
 */
class CheckNoAndroidDependencyTest {

    private fun task(): CheckNoAndroidDependency {
        val project = ProjectBuilder.builder().build()
        return project.tasks.create("checkNoAndroidDependencyTest", CheckNoAndroidDependency::class.java)
    }

    private fun sourceFile(dir: File, name: String, contents: String): File {
        val file = File(dir, name)
        file.writeText(contents)
        return file
    }

    // --- A clean module passes ------------------------------------------

    @Test
    fun `passes when nothing is banned`(@TempDir dir: File) {
        val check = task()
        check.moduleIdentifiers.set(listOf("org.jetbrains.kotlin:kotlin-stdlib:2.1.21"))
        check.unresolvedDependencies.set(emptyList())
        check.sourceFiles.setFrom(sourceFile(dir, "Physics.kt", "package gravitris.physics\n\nclass Physics\n"))

        check.check() // must not throw
    }

    // --- Resolved dependency check --------------------------------------

    @Test
    fun `fails on an androidx dependency`() {
        val check = task()
        check.moduleIdentifiers.set(listOf("androidx.core:core-ktx:1.15.0"))
        check.unresolvedDependencies.set(emptyList())
        check.sourceFiles.setFrom(emptyList<File>())

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("androidx.core:core-ktx:1.15.0"), "got: ${ex.message}")
    }

    @Test
    fun `fails on a com-android dependency`() {
        val check = task()
        check.moduleIdentifiers.set(listOf("com.android.tools.build:gradle:8.9.3"))
        check.unresolvedDependencies.set(emptyList())
        check.sourceFiles.setFrom(emptyList<File>())

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("com.android.tools.build:gradle:8.9.3"), "got: ${ex.message}")
    }

    @Test
    fun `fails on the legacy android group exactly, but not a look-alike group`() {
        val check = task()
        // "android.arch.lifecycle" is a real legacy coordinate prefix and
        // must not slip past a check that only matches the group "android"
        // exactly by accident of how bannedExact is defined — this pins
        // down that group-name matching for "android" is exact-or-prefix
        // aware the same way androidx./com.android. are, not a red herring.
        check.moduleIdentifiers.set(listOf("android:android:99"))
        check.unresolvedDependencies.set(emptyList())
        check.sourceFiles.setFrom(emptyList<File>())

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("android:android:99"), "got: ${ex.message}")
    }

    @Test
    fun `does not flag a group that merely contains android as a substring`() {
        val check = task()
        // "com.pandroid:widgets:1.0" contains "android" as a substring of
        // its group but starts with neither banned prefix and is not the
        // exact banned group — this is what would break if the banned-group
        // check were ever loosened to a substring match "for convenience".
        check.moduleIdentifiers.set(listOf("com.pandroid:widgets:1.0"))
        check.unresolvedDependencies.set(emptyList())
        check.sourceFiles.setFrom(emptyList<File>())

        check.check() // must not throw
    }

    // --- Unresolved dependency check (review finding S-1) ---------------

    @Test
    fun `fails when any dependency is unresolved, even if nothing resolved is banned`() {
        val check = task()
        check.moduleIdentifiers.set(listOf("org.jetbrains.kotlin:kotlin-stdlib:2.1.21"))
        check.unresolvedDependencies.set(listOf("com.google.android.material:material:1.12.0"))
        check.sourceFiles.setFrom(emptyList<File>())

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("failed to resolve"), "got: ${ex.message}")
        assertTrue(ex.message!!.contains("com.google.android.material:material:1.12.0"), "got: ${ex.message}")
    }

    // --- Source import check --------------------------------------------

    @Test
    fun `fails on a stray android import with file and line number`(@TempDir dir: File) {
        val check = task()
        check.moduleIdentifiers.set(emptyList())
        check.unresolvedDependencies.set(emptyList())
        val file = sourceFile(
            dir,
            "Physics.kt",
            "package gravitris.physics\n\nimport android.graphics.PointF\n\nclass Physics\n"
        )
        check.sourceFiles.setFrom(file)

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("${file.path}:3"), "expected file:line in the failure, got: ${ex.message}")
    }

    @Test
    fun `fails on a stray androidx import`(@TempDir dir: File) {
        val check = task()
        check.moduleIdentifiers.set(emptyList())
        check.unresolvedDependencies.set(emptyList())
        check.sourceFiles.setFrom(
            sourceFile(dir, "Physics.kt", "import androidx.annotation.VisibleForTesting\n")
        )

        val ex = assertThrows(GradleException::class.java) { check.check() }
        assertTrue(ex.message!!.contains("Android imports found"), "got: ${ex.message}")
    }

    @Test
    fun `ignores non-kt files even if they contain an android import line`(@TempDir dir: File) {
        val check = task()
        check.moduleIdentifiers.set(emptyList())
        check.unresolvedDependencies.set(emptyList())
        check.sourceFiles.setFrom(
            sourceFile(dir, "notes.txt", "import android.graphics.PointF\n")
        )

        check.check() // must not throw — only .kt files are scanned
    }

    @Test
    fun `does not flag a package that merely starts with android`(@TempDir dir: File) {
        val check = task()
        check.moduleIdentifiers.set(emptyList())
        check.unresolvedDependencies.set(emptyList())
        // The import regex requires "import android." or "import androidx."
        // — an import of a real, unrelated package that happens to start
        // with the same letters must not be flagged.
        check.sourceFiles.setFrom(
            sourceFile(dir, "Physics.kt", "import androidutil.SomethingUnrelated\n")
        )

        check.check() // must not throw
    }
}
