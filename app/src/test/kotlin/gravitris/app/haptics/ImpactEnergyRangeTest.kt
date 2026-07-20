package gravitris.app.haptics

import gravitris.app.Tunables
import gravitris.app.toy.SquishToy
import gravitris.game.InputFrame
import gravitris.game.SimConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Keeps the haptic curve honest against the energies the solver actually
 * produces, the way [gravitris.app.CompressionRangeTest] does for the
 * darkening gain.
 *
 * This exists because of a specific failure. Milestone 1 came back from the
 * client's device reading `haptics:fixed`, and "the solver is handing us zero
 * or NaN, so the scaling degenerates" was one of several candidate
 * explanations that could not be told apart from a screenshot. A curve tuned
 * in the abstract against a 0..1 energy that nothing ever measured is exactly
 * how a project ends up with a constant-feeling pulse and no way to find out
 * why.
 *
 * The Stage 1 darkening term was already wrong by more than 3x for precisely
 * this reason — tuned against a harness rather than against the real
 * distribution. The same mistake was available here and this test closes it.
 */
class ImpactEnergyRangeTest {

    private fun config() = SimConfig(
        lattice = Tunables.TOY_LATTICE,
        wellWidth = 10f,
        wellHeight = 20f,
    )

    /** Every impact energy emitted over [frames] of the given scenario. */
    private fun energies(frames: Int, hardDrop: Boolean): List<Float> {
        val toy = SquishToy(config(), maxBodies = Tunables.TOY_MAX_BODIES)
        val input = InputFrame()
        val seen = mutableListOf<Float>()

        toy.step(input)
        if (hardDrop) {
            input.hardDrop = true
            input.hardDropVelocity = 30f
            toy.step(input)
            input.hardDrop = false
            input.hardDropVelocity = 0f
        }

        repeat(frames) {
            toy.step(input)
            val impacts = toy.state.impacts
            for (i in 0 until impacts.count) seen += impacts.strength[i]
        }
        return seen
    }

    @Test
    fun `impact energies are finite and inside the curve's domain`() {
        val all = energies(frames = 2000, hardDrop = false)

        assertTrue(all.isNotEmpty(), "the solver emitted no impacts at all over 2000 frames")
        for (e in all) {
            assertTrue(
                e.isFinite(),
                "the solver emitted a non-finite impact energy ($e). HapticCurve would " +
                    "silently degenerate to the weakest pulse rather than fail, which is " +
                    "indistinguishable on a device from having no amplitude control.",
            )
            assertTrue(
                e in 0f..1f,
                "impact energy $e is outside the 0..1 the curve is defined over",
            )
        }
    }

    /**
     * The floor exists to silence settling contacts. If it silenced *everything*
     * the readout would still say `haptics:scaled` on capable hardware while
     * nothing ever fired — a failure mode with no on-screen symptom at all.
     */
    @Test
    fun `an ordinary landing clears the suppression floor`() {
        val all = energies(frames = 2000, hardDrop = false)
        val audible = all.count { !HapticCurve.isSuppressed(it) }

        assertTrue(
            audible > 0,
            "not one of ${all.size} impacts over 2000 frames reached the " +
                "${Tunables.HAPTIC_ENERGY_FLOOR} energy floor (strongest was " +
                "${all.maxOrNull()}). Every haptic in the game would be suppressed.",
        )
    }

    /**
     * The entire argument for the `VIBRATE` permission was that the pulse is
     * *scaled*. That is only true if the energies actually spread across the
     * ramp — if every landing saturates at 1.0 the amplitude is a constant and
     * we are paying a permission for a fixed pulse by another route.
     */
    @Test
    fun `impact energies spread across the ramp rather than saturating`() {
        val audible = energies(frames = 3000, hardDrop = false)
            .filter { !HapticCurve.isSuppressed(it) }

        assertTrue(audible.size >= 5, "too few audible impacts (${audible.size}) to judge spread")

        val amplitudes = audible.map { HapticCurve.amplitude(it) }
        val distinct = amplitudes.distinct().size
        val span = amplitudes.max() - amplitudes.min()

        assertTrue(
            distinct >= 3 && span >= 30,
            "audible impacts produced $distinct distinct amplitudes spanning $span steps of " +
                "255 (min ${amplitudes.min()}, max ${amplitudes.max()}). The pulse would feel " +
                "constant even on hardware with full amplitude control, which is the thing " +
                "the permission was justified by.",
        )
    }

    /** A hard drop must land harder than a free fall, or the ramp carries nothing. */
    @Test
    fun `a hard drop produces a stronger impact than a free fall`() {
        val gentle = energies(frames = 300, hardDrop = false).maxOrNull() ?: 0f
        val hard = energies(frames = 300, hardDrop = true).maxOrNull() ?: 0f

        assertTrue(
            hard > gentle,
            "hard drop peaked at $hard, free fall at $gentle — the haptic ramp is not " +
                "distinguishing landing energy",
        )
    }
}
