package gravitris.app.haptics

import gravitris.app.Tunables
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Tests for the impact-haptic energy curve in
 * `docs/ux/feel-feedback.md` §"Impact haptics".
 *
 * The brief ranks this above any visual effect for conveying weight, so the
 * two endpoints of the ramp and the suppression floor are all pinned here —
 * they are design decisions with stated reasons, not implementation details
 * that may drift.
 */
class HapticCurveTest {

    @ParameterizedTest
    @ValueSource(floats = [0f, 0.05f, 0.1f, 0.1499f])
    fun `impacts below the floor are suppressed`(energy: Float) {
        // feel-feedback.md: without a floor, continuous small settling contacts
        // during a busy stack turn into a constant low buzz, which reads as
        // noise, not feedback.
        assertTrue(HapticCurve.isSuppressed(energy), "energy $energy must be silent")
    }

    @Test
    fun `the floor itself is not suppressed`() {
        assertFalse(HapticCurve.isSuppressed(Tunables.HAPTIC_ENERGY_FLOOR))
    }

    @Test
    fun `the lightest audible impact is ten milliseconds at amplitude sixty`() {
        val energy = Tunables.HAPTIC_ENERGY_FLOOR
        assertEquals(10L, HapticCurve.durationMs(energy))
        assertEquals(60, HapticCurve.amplitude(energy))
    }

    @Test
    fun `the heaviest impact is forty milliseconds at full amplitude`() {
        assertEquals(40L, HapticCurve.durationMs(1f))
        assertEquals(255, HapticCurve.amplitude(1f))
    }

    @Test
    fun `the midpoint sits halfway along both ramps`() {
        // Linear between the endpoints — feel-feedback.md is explicit that
        // linear is right here because there is no teaching job for this ramp,
        // just a felt-weight gradient.
        val midpoint = Tunables.HAPTIC_ENERGY_FLOOR + (1f - Tunables.HAPTIC_ENERGY_FLOOR) / 2f
        assertEquals(25L, HapticCurve.durationMs(midpoint))
        // 60 + 0.5 * (255 - 60) = 157.5, truncated. Allow one step either way
        // so float rounding at the midpoint cannot make this brittle.
        assertTrue(
            HapticCurve.amplitude(midpoint) in 156..158,
            "midpoint amplitude was ${HapticCurve.amplitude(midpoint)}, expected ~157",
        )
    }

    @Test
    fun `duration and amplitude both rise with energy`() {
        var previousDuration = 0L
        var previousAmplitude = 0
        var energy = Tunables.HAPTIC_ENERGY_FLOOR
        while (energy <= 1f) {
            val duration = HapticCurve.durationMs(energy)
            val amplitude = HapticCurve.amplitude(energy)
            assertTrue(duration >= previousDuration, "duration must not fall at $energy")
            assertTrue(amplitude >= previousAmplitude, "amplitude must not fall at $energy")
            previousDuration = duration
            previousAmplitude = amplitude
            energy += 0.01f
        }
    }

    @Test
    fun `energy above one is clamped rather than extrapolated`() {
        // The core normalises energy against the run's CURRENT min/max mass and
        // velocity range, which rises over a run — so a transient value just
        // over 1 is a plausible consequence of that range moving. It must not
        // ask the vibrator for an amplitude it cannot produce.
        assertEquals(255, HapticCurve.amplitude(1.4f))
        assertEquals(40L, HapticCurve.durationMs(1.4f))
    }

    @Test
    fun `amplitude never leaves the platform's one to two five five scale`() {
        var energy = Tunables.HAPTIC_ENERGY_FLOOR
        while (energy <= 2f) {
            val amplitude = HapticCurve.amplitude(energy)
            assertTrue(amplitude in 1..255, "amplitude $amplitude out of range at $energy")
            energy += 0.013f
        }
    }
}
