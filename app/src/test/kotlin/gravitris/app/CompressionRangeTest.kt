package gravitris.app

import gravitris.app.toy.SquishToy
import gravitris.game.InputFrame
import gravitris.game.SimConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Keeps [Tunables.COMPRESSION_GAIN] honest against the solver that actually
 * produces the numbers it maps.
 *
 * Stage 1 tuned this constant against a kinematic harness whose squash spanned
 * 0.57..1.16 and flagged, correctly, that the real distribution would differ
 * and that either failure mode would look like "the shading is wrong" rather
 * than like one mis-set number. It differed: the solver's area constraints are
 * stiff, the real range is roughly 0.89..1.0, and the Stage 1 gain of 1.2 would
 * have shipped a term that darkened the hardest impact in the game by 13%.
 *
 * The lesson is that the constant is only meaningful relative to a measured
 * distribution, so the measurement lives here as a test rather than in a
 * handoff as a number someone has to remember to re-take.
 */
class CompressionRangeTest {

    private fun config() = SimConfig(
        lattice = Tunables.TOY_LATTICE,
        wellWidth = 10f,
        wellHeight = 20f,
    )

    /** Deepest compression seen anywhere while a hard-dropped piece lands. */
    private fun peakCompressionOnHardDrop(): Float {
        val toy = SquishToy(config(), maxBodies = Tunables.TOY_MAX_BODIES)
        val input = InputFrame()

        toy.step(input)
        toy.slamActivePiece(30f)
        toy.step(input)

        var deepest = Float.MAX_VALUE
        repeat(300) {
            toy.step(input)
            for (i in 0 until toy.state.particleCount) {
                if (toy.state.particleCompression[i] < deepest) {
                    deepest = toy.state.particleCompression[i]
                }
            }
        }
        return deepest
    }

    private fun darkening(compression: Float): Float =
        minOf((1f - compression) * Tunables.COMPRESSION_GAIN, Tunables.COMPRESSION_MAX_DARKEN)

    @Test
    fun `the hardest impact darkens enough to be visible`() {
        val deepest = peakCompressionOnHardDrop()
        val darken = darkening(deepest)

        assertTrue(
            darken >= 0.30f,
            "the deepest compression this solver produces ($deepest) maps to only " +
                "${(darken * 100).toInt()}% darkening at gain ${Tunables.COMPRESSION_GAIN}. " +
                "The weight cue would be invisible and someone would conclude the shading " +
                "term does not work.",
        )
    }

    @Test
    fun `the hardest impact does not slam into the cap`() {
        val deepest = peakCompressionOnHardDrop()
        val uncapped = (1f - deepest) * Tunables.COMPRESSION_GAIN

        assertTrue(
            uncapped <= Tunables.COMPRESSION_MAX_DARKEN,
            "the deepest compression ($deepest) wants ${(uncapped * 100).toInt()}% darkening, " +
                "past the ${(Tunables.COMPRESSION_MAX_DARKEN * 100).toInt()}% cap. Material " +
                "would sit clamped at the ceiling and read as uniformly dark mud, and hue — " +
                "the primary piece-identity cue — would stop separating the pieces.",
        )
    }

    /**
     * A settled pile should read as its own flat colour. The darkening is meant
     * to be an *event* at the moment of impact, not a permanent bruise on
     * everything at the bottom of the well.
     */
    @Test
    fun `settled material stays close to its own colour`() {
        val toy = SquishToy(config(), maxBodies = Tunables.TOY_MAX_BODIES)
        repeat(3000) { toy.step(InputFrame()) }

        var deepest = Float.MAX_VALUE
        for (i in 0 until toy.state.particleCount) {
            if (toy.state.particleCompression[i] < deepest) deepest = toy.state.particleCompression[i]
        }

        assertTrue(toy.state.bodyCount >= 3, "expected a pile to have formed")
        assertTrue(
            darkening(deepest) < 0.30f,
            "resting material in a ${toy.state.bodyCount}-body pile is compressed to " +
                "$deepest, darkening it by ${(darkening(deepest) * 100).toInt()}% — the " +
                "impact cue cannot read against a stack that is already dark",
        )
    }

    /**
     * The term must respond to *how hard* the landing was, not merely to
     * whether one happened. If these were equal the darkening would be a
     * constant and would carry no weight information at all.
     */
    @Test
    fun `a harder landing compresses the material more`() {
        val toy = SquishToy(config(), maxBodies = Tunables.TOY_MAX_BODIES)
        val input = InputFrame()
        var gentle = Float.MAX_VALUE
        repeat(300) {
            toy.step(input)
            for (i in 0 until toy.state.particleCount) {
                if (toy.state.particleCompression[i] < gentle) gentle = toy.state.particleCompression[i]
            }
        }

        val hard = peakCompressionOnHardDrop()

        assertTrue(
            hard < gentle,
            "a hard drop compressed to $hard, a free fall to $gentle — the shading term is " +
                "not distinguishing landing energy",
        )
    }
}
