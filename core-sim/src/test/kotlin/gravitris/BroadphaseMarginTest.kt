package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contacts are **rigid** (ADR 0003 §2): bodies are soft because their interior
 * is compliant, but two bodies must never visibly sink into each other — the
 * code's own comment says a soft contact "reads as a rendering bug rather than
 * as softness". Separately, the ADR 0009 quality tier (lattice 4 | 5 | 6)
 * changes only render detail: "an accessibility or performance setting must
 * never change what happens in the game" (`XpbdSolver.MAX_SPEED` comment).
 *
 * This test asserts both at once: the same hard drop must sink one body no
 * further into another at any quality tier. It is the regression guard for a
 * fixed defect, and it exists because the high-detail tier had no guard at all.
 *
 * ### The defect this guards against (fixed on `main`)
 *
 * QA pinned this test against `feat/core-sim`, where the broadphase was rebuilt
 * **once per frame** and the narrowphase centred a 3×3 stencil on each
 * particle's frame-start cell, tolerating about one cell of intra-frame drift.
 * Cell size is one particle diameter, and the particle radius shrinks as the
 * lattice grows:
 *
 * | lattice | diameter (cell) | drift at `MAX_SPEED` = 30 |
 * | ------- | --------------- | ------------------------- |
 * | 4       | 0.600           | 0.83 cells/frame          |
 * | 5       | 0.450           | 1.11 cells/frame          |
 * | 6       | 0.360           | **1.39 cells/frame**      |
 *
 * At lattice 6 a piece at terminal speed drifts 1.39 cells *per frame* — past
 * the stencil's reach — so on the frame it arrived its contact against a
 * resting body was **missed entirely**, it penetrated, and the next rebuild
 * resolved the deep overlap violently. Measured then, a hard drop onto a
 * settled pile sank one body ~44% of a particle diameter into another at
 * lattice 6, against 0% at lattice 4 and 5.
 *
 * The fix landed independently on `main` before this test was un-parked:
 * `XpbdSolver` now rebuilds the broadphase **once per substep** (commit
 * `0571697`, `fix(core-sim): rebuild the broadphase every substep`), which is
 * exactly QA's first suggested direction. Per substep the drift bound holds
 * with margin at every tier — `MAX_SPEED * h` = 0.0625 units, closing 0.125
 * against a 0.36-unit lattice-6 cell, a 2.9x margin — so the missed pair can no
 * longer happen. Re-measured on this scene, lattice 6 sinks 0.90% of a diameter
 * (lattice 4: 0.40%, lattice 5: 0.86%), all far under the 15% budget. Reverting
 * `XpbdSolver` to a per-frame rebuild reproduces 39% at lattice 6 and turns this
 * test red — that is the regression it now catches.
 *
 * The existing `SolverBehaviourTest` non-tunnelling test only exercises lattice
 * 5, so before this test nothing guarded the high-detail tier — the same
 * "inspection passed, execution failed" pattern the Architect recorded
 * (handoff 0008).
 *
 * `MAX_SPEED` (30) is reachable directly: `slamActivePiece` clamps up to it
 * (ADR 0016), so this is a full-speed drop, not a contrived speed.
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
            sim.slamActivePiece(30f) // impact-velocity probe (ADR 0016), replaces the old hard drop
            sim.step(input)

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
