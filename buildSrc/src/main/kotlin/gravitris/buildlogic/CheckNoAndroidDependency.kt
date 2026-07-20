package gravitris.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * ADR 0008: ":core-sim has no Android dependency. Not 'minimal' — none."
 *
 * This is the single most valuable check in the Stage 0 build (see
 * docs/build-order.md and docs/adr/0008-module-boundaries.md) because the
 * violation it prevents is otherwise invisible until someone tries to run
 * physics tests on the JVM and discovers they need a device. It must fail
 * loudly and immediately, not be discovered a week in.
 *
 * Three independent checks, because they catch different mistakes:
 *
 * 1. **Resolved dependency check.** Fails if any artifact on :core-sim's
 *    compile or runtime classpath (main or test) belongs to a group that is
 *    Android platform code: `androidx.*`, `com.android.*`, or the legacy
 *    `android` group/module. This catches "someone added a library that
 *    happens to pull in AndroidX transitively" — the realistic failure mode,
 *    since :core-sim never applies an Android Gradle plugin and so cannot
 *    accidentally compile against the platform SDK itself.
 *
 * 2. **Unresolved dependency check.** A dependency that fails to resolve
 *    (e.g. an Android AAR added to a plain-JVM classpath, which has no
 *    JVM-consumable variant) must never be treated as a passing result —
 *    review finding S-1 caught exactly this: the previous version silently
 *    dropped `UnresolvedDependencyResult` via a safe cast, so adding
 *    `com.google.android.material:material` (an AAR, not itself a banned
 *    group) reported green even though it drags in `androidx.appcompat`/
 *    `androidx.core` transitively. The build still failed later at
 *    variant-matching, but this task's whole job is to say so *first* and
 *    *clearly*, not to be silent about a graph it couldn't verify. Any
 *    unresolved dependency now fails this task directly.
 *
 * 3. **Source import check.** Fails if any `.kt` file under the given source
 *    directories contains an `import android.` or `import androidx.` line.
 *    Belt-and-braces: it catches a stray import even in the (currently
 *    impossible, since no android jar is on the classpath) case that one
 *    becomes reachable, and it gives a precise file:line in the failure
 *    message rather than just a dependency coordinate.
 */
abstract class CheckNoAndroidDependency : DefaultTask() {

    @get:Input
    abstract val moduleIdentifiers: ListProperty<String>

    @get:Input
    abstract val unresolvedDependencies: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @TaskAction
    fun check() {
        val bannedPrefixes = listOf("androidx.", "com.android.")
        val bannedExact = setOf("android")

        val offendingDependencies = moduleIdentifiers.get().filter { id ->
            val group = id.substringBefore(":")
            bannedPrefixes.any { group.startsWith(it) } || group in bannedExact
        }

        val unresolved = unresolvedDependencies.get()

        val importRegex = Regex("""^\s*import\s+(android|androidx)\.""")
        val offendingImports = mutableListOf<String>()
        sourceFiles.filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (importRegex.containsMatchIn(line)) {
                    offendingImports += "${file.path}:${index + 1}: ${line.trim()}"
                }
            }
        }

        if (offendingDependencies.isNotEmpty() || unresolved.isNotEmpty() || offendingImports.isNotEmpty()) {
            val message = buildString {
                appendLine(
                    "ADR 0008 violation: :core-sim must have no Android dependency, " +
                        "none — see docs/adr/0008-module-boundaries.md."
                )
                if (offendingDependencies.isNotEmpty()) {
                    appendLine("Android dependencies found on :core-sim's classpath:")
                    offendingDependencies.forEach { appendLine("  - $it") }
                }
                if (unresolved.isNotEmpty()) {
                    appendLine(
                        "Dependencies that failed to resolve — this module boundary " +
                            "cannot be verified while any dependency is unresolved, so " +
                            "this counts as a failure rather than a silent pass (an " +
                            "Android dependency, such as an AAR with no JVM-consumable " +
                            "variant, can hide behind exactly this failure mode):"
                    )
                    unresolved.forEach { appendLine("  - $it") }
                }
                if (offendingImports.isNotEmpty()) {
                    appendLine("Android imports found in :core-sim sources:")
                    offendingImports.forEach { appendLine("  - $it") }
                }
                append(
                    "Remove the dependency/import, or move the code that needs it into :app."
                )
            }
            throw GradleException(message)
        }
    }
}
