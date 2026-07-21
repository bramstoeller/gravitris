package gravitris.app.gl

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The clear-burst ember motion (`visual-direction.md` §7.2). The GL buffer
 * upload cannot run in this container, but the analytic trajectory and the fade
 * are pure maths and are exactly the part with branches to get wrong — so they
 * live in [EmberTrajectory] and are pinned here.
 */
class EmberTrajectoryTest {

    @Test
    fun `an ember is invisible at birth, brightens, then fades to nothing`() {
        // At t=0 the fade-in is 0, so the ember starts from nothing rather than
        // popping in at full brightness.
        assertEquals(0f, EmberTrajectory.alpha(0f), 1e-6f)

        // It peaks at the end of the 40ms fade-in — by which point the linear
        // fade-out has already begun, so the peak sits just below 1, not at it.
        val peak = EmberTrajectory.alpha(0.04f)
        assertTrue(peak > EmberTrajectory.alpha(0.01f), "still brightening before the peak")
        assertTrue(peak > EmberTrajectory.alpha(0.20f), "fading after the peak")
        assertTrue(peak in 0.8f..1f, "peak opacity $peak should be near full")

        // It is fully gone by the end of its life, and stays gone after.
        assertEquals(0f, EmberTrajectory.alpha(EmberTrajectory.LIFE_SECONDS), 1e-6f)
        assertEquals(0f, EmberTrajectory.alpha(EmberTrajectory.LIFE_SECONDS + 0.1f), 1e-6f)
        assertEquals(0f, EmberTrajectory.alpha(-0.01f), 1e-6f)
    }

    @Test
    fun `alpha never leaves the zero-to-one range across the whole life`() {
        var t = 0f
        while (t <= EmberTrajectory.LIFE_SECONDS) {
            val a = EmberTrajectory.alpha(t)
            assertTrue(a in 0f..1f, "alpha $a out of range at t=$t")
            t += 0.005f
        }
    }

    @Test
    fun `the vertical arc rises then falls under the analytic gravity`() {
        // A representative upward throw. Apex is where velocity + g·t = 0.
        val v0 = 9f
        val apex = -v0 / EmberTrajectory.GRAVITY // g is negative, so this is positive
        val yStart = EmberTrajectory.y(0f, v0, 0f)
        val yApex = EmberTrajectory.y(0f, v0, apex)
        val yLater = EmberTrajectory.y(0f, v0, apex + 0.1f)

        assertEquals(0f, yStart, 1e-6f, "starts at its origin")
        assertTrue(yApex > yStart, "rises above the origin before the apex")
        assertTrue(yLater < yApex, "falls back after the apex")
    }

    @Test
    fun `horizontal motion is linear from the origin`() {
        assertEquals(5f + 4f * 0.1f, EmberTrajectory.x(5f, 4f, 0.1f), 1e-5f)
    }

    @Test
    fun `an ember shrinks as it ages`() {
        val young = EmberTrajectory.size(1f, 0f)
        val old = EmberTrajectory.size(1f, EmberTrajectory.LIFE_SECONDS * 0.9f)
        assertTrue(old < young, "an ember should shrink toward zero as it fades")
        assertTrue(young > 0f && old > 0f, "size stays positive over the life")
    }
}
