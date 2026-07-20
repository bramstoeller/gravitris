package gravitris.coresim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Proves the JVM test task actually executes something (ADR 0008's whole
 * point: physics tests need no device). Delete alongside
 * CoreSimBuildScaffold once real solver/game tests land in Stage 1.
 */
class CoreSimBuildScaffoldTest {
    @Test
    fun `module identifies itself`() {
        assertEquals("core-sim", CoreSimBuildScaffold.MODULE_NAME)
    }
}
