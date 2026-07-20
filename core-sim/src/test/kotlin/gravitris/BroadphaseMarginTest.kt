package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Contacts are **rigid** (ADR 0003 §2): bodies are soft because their interior
 * is compliant, but two bodies must never visibly sink into each other — the
 * code's own comment says a soft contact "reads as a rendering bug rather than
 * as softness". Separately, the ADR 0009 quality tier (lattice 4 | 5 | 6)
 * changes only render detail: "an accessibility or performance setting must
 * never change what happens in the game" (`XpbdSolver.MAX_SPEED` comment).
 *
 * Both guarantees are violated at the highest quality tier under the game's own
 * hard drop.
 *
 * ### The defect (handed to the Backend Engineer)
 *
 * The broadphase is rebuilt **once per frame** and the narrowphase centres a
 * 3×3 stencil on each particle's frame-start cell, which tolerates about one
 * cell of intra-frame drift (ADR 0003 §1, Amendment 3). Cell size is one
 * particle diameter, and the particle radius shrinks as the lattice grows:
 *
 * | lattice | diameter (cell) | drift at `MAX_SPEED` = 30 |
 * | ------- | --------------- | ------------------------- |
 * | 4       | 0.600           | 0.83 cells/frame          |
 * | 5       | 0.450           | 1.11 cells/frame          |
 * | 6       | 0.360           | **1.39 cells/frame**      |
 *
 * At lattice 6 a piece at terminal speed drifts 1.39 cells in a frame — past
 * the stencil's reach — so on the frame it arrives, its contact against a
 * resting body is **missed entirely**, and it penetrates before the next
 * rebuild resolves the deep overlap violently. Measured, a hard drop onto a
 * settled pile sinks one body **44% of a particle diameter** into another at
 * lattice 6, against **0%** at lattice 4 and 5 — a clean cliff, bit-identical
 * across runs, not a noisy boundary. Amendment 3 flagged this exact coupling
 * ("shrinking particle radius eats that margin, and each should re-trigger the
 * non-tunnelling test"); this is that test, and it fails.
 *
 * The existing `SolverBehaviourTest` non-tunnelling test only exercises lattice
 * 5, so nothing guarded the high-detail tier — the same "inspection passed,
 * execution failed" pattern the Architect recorded (handoff 0008).
 *
 * `MAX_SPEED` (30) is reachable directly: `hardDropVelocity` is clamped up to
 * it, so this is the ordinary hard drop, not a contrived speed.
 */
class BroadphaseMarginTest {

    /**
     * Deepest overlap between two particles of *different* bodies, as a
     * fraction of a particle diameter, at the frame boundary (after the tick's
     * final contact solve). A rigid, detected contact resolves to ~0 here; a
     * broadphase miss leaves the full uncorrected penetration visible.
     */
    private fun deepestInterBodyPenetrationFraction(sim: Simulation, radius: Float): Float {
        val s = sim.state
        val diameter = 2f * radius
        var worst = 0f
        for (i in 0 until s.particleCount) {
            for (j in i + 1 until s.particleCount) {
                if (s.particleBody[i] == s.particleBody[j]) continue
                val dx = s.positionX[i] - s.positionX[j]
                val dy = s.positionY[i] - s.positionY[j]
                val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val overlap = diameter - d
                if (overlap > worst) worst = overlap
            }
        }
        return worst / diameter
    }

    private fun particleRadius(lattice: Int): Float =
        SimConfig.PIECE_WIDTH / (lattice - 1) * 0.5f

    @Disabled(
        "PINNED DEFECT for the Backend Engineer (owner of :core-sim). This test FAILS " +
            "on the current solver: a hard drop at lattice 6 sinks one body 43.7% of a " +
            "particle diameter into another, against 0% at lattice 4 and 5. Parked @Disabled " +
            "rather than left red so it does not poison feat/core-sim and the branches that " +
            "descend from it. Remove this annotation to reproduce (red), then restore the " +
            "broadphase margin at the high-detail tier to turn it green. See QA review " +
            "reviews/qa-broadphase-margin.md and handoff 0020.",
    )
    @Test
    fun `a hard drop stays rigid at every quality tier`() {
        // The identical physical scene at each ADR 0009 quality tier. Because a
        // tier changes only render detail, the contact behaviour — how far one
        // body sinks into another — must be the same at all three.
        for (lattice in intArrayOf(4, 5, 6)) {
            val config = SimConfig(lattice = lattice, wellWidth = 6f, wellHeight = 30f)
            val radius = particleRadius(lattice)

            val sim = TestScenes.pile(config, bodies = 6)
            TestScenes.run(sim, SETTLE_FRAMES)

            val top = TestScenes.stackHeight(sim.state)
            sim.addPiece(archetype = 1, centerX = 3f, centerY = top + 6f)
            val input = InputFrame()
            input.hardDrop = true
            input.hardDropVelocity = 30f
            sim.step(input)
            input.clear()

            var worst = 0f
            repeat(120) {
                sim.step(input)
                val f = deepestInterBodyPenetrationFraction(sim, radius)
                if (f > worst) worst = f
            }

            assertTrue(
                worst < RIGID_PENETRATION_FRACTION,
                "lattice $lattice: a hard drop sank one body ${"%.1f".format(worst * 100)}% of a " +
                    "particle diameter into another (rigid-contact budget " +
                    "${"%.0f".format(RIGID_PENETRATION_FRACTION * 100)}%). At lattice 4 and 5 this is 0%; " +
                    "at lattice 6 the per-frame broadphase drift (1.39 cells) outruns the 3×3 stencil, " +
                    "the arriving piece's contact is missed for a frame, and it visibly penetrates. " +
                    "Contacts must be rigid regardless of quality tier (ADR 0003 §2, ADR 0009).",
            )
        }
    }

    private companion object {
        /** Long enough for the seeded pile to close its gaps and stop. */
        const val SETTLE_FRAMES = 900

        /**
         * A rigid contact resolves the same frame it is detected, so measured
         * at frame boundaries an honest contact shows ~0 residual overlap
         * (lattice 4 and 5 both measure exactly 0). This bound sits far below
         * the lattice-6 failure (44%) and far above the noise floor, so it
         * asserts the cliff, not a boundary — it will not flake, and it passes
         * the moment the broadphase margin is restored at every tier.
         */
        const val RIGID_PENETRATION_FRACTION = 0.15f
    }
}
