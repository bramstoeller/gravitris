package gravitris.app.gl

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The screen-wide luminance beat's intensity envelope (`visual-direction.md`
 * §7.1). Pure maths, so it is pinned here rather than left to eyes on a
 * software emulator: a symmetric triangle that peaks mid-window and is zero at
 * both ends and outside the window.
 */
class ClearFlashEnvelopeTest {

    private val duration = 120_000_000L // 120ms in nanos, GameRenderer's constant

    @Test
    fun `the beat starts and ends at zero and peaks in the middle`() {
        assertEquals(0f, clearFlashEnvelope(0L, duration), 1e-6f)
        assertEquals(1f, clearFlashEnvelope(duration / 2, duration), 1e-3f)
        // At the very end the window is over — treated as no beat.
        assertEquals(0f, clearFlashEnvelope(duration, duration), 1e-6f)
    }

    @Test
    fun `outside the window there is no beat`() {
        assertEquals(0f, clearFlashEnvelope(-1L, duration), 1e-6f)
        assertEquals(0f, clearFlashEnvelope(duration + 1_000_000L, duration), 1e-6f)
    }

    @Test
    fun `intensity is symmetric about the midpoint and never exceeds one`() {
        val quarter = clearFlashEnvelope(duration / 4, duration)
        val threeQuarter = clearFlashEnvelope(duration * 3 / 4, duration)
        assertEquals(quarter, threeQuarter, 1e-3f, "the rise and fall are symmetric")

        var t = 0L
        while (t < duration) {
            assertTrue(clearFlashEnvelope(t, duration) in 0f..1f, "intensity out of range at $t")
            t += duration / 20
        }
    }
}
