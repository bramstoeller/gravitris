package gravitris

import gravitris.game.SimConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stack stability is the classic failure mode for position-based solvers, and
 * an unstable solver produces jitter that players read as bugs rather than as
 * physics (ADR 0003).
 *
 * These assert on **residual kinetic energy**, not on eyeballing a replay.
 * That is the same predicate the losing condition and lock detection will use
 * later (ADR 0005), so the stability bar and the gameplay bar are the same
 * number rather than two that can drift apart.
 */
class StabilityTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 10f, wellHeight = 20f)

    @Test
    fun `a settled pile stays settled`() {
        val config = config()
        val sim = TestScenes.pile(config, bodies = 24)

        TestScenes.run(sim, SETTLE_FRAMES)
        val settledEnergy = sim.state.kineticEnergy
        val settledHeight = TestScenes.stackHeight(sim.state)

        assertTrue(
            settledEnergy < config.quietKineticEnergy,
            "pile of 24 bodies should be quiet after $SETTLE_FRAMES frames, " +
                "kinetic energy was $settledEnergy (quiet threshold ${config.quietKineticEnergy})",
        )

        // Staying settled is the real claim. A pile that settles and then
        // slowly wakes itself up is the failure this catches — energy
        // injected by the solver rather than by the player.
        TestScenes.run(sim, HOLD_FRAMES)
        val heldEnergy = sim.state.kineticEnergy
        val heldHeight = TestScenes.stackHeight(sim.state)

        assertTrue(
            heldEnergy < config.quietKineticEnergy,
            "pile should still be quiet after a further $HOLD_FRAMES frames, " +
                "kinetic energy was $heldEnergy (was $settledEnergy)",
        )

        // Friction is not optional polish: without it a pile creeps outward
        // indefinitely rather than standing still (ADR 0003 §3). A pile that
        // is quiet but sinking or spreading would pass the energy assertion
        // alone, so height is checked too.
        val drift = kotlin.math.abs(heldHeight - settledHeight)
        assertTrue(
            drift < CREEP_TOLERANCE,
            "settled pile drifted $drift well units over $HOLD_FRAMES frames " +
                "($settledHeight -> $heldHeight); it should be standing still, not creeping",
        )
    }

    @Test
    fun `stability holds as pieces get heavier`() {
        // The late-game case that matters: the brief's difficulty ramp makes
        // pieces heavier as the game progresses, and ADR 0003 measured that
        // per-particle mass 1, 2, 4 and 8 all settle at 8 substeps. If that
        // stopped being true the ramp would have to be capped, so it is worth
        // an assertion rather than a citation.
        for (mass in floatArrayOf(1f, 2f, 4f, 8f)) {
            val config = config().copy(initialPieceMass = mass)
            val sim = TestScenes.pile(config, bodies = 16)
            TestScenes.run(sim, SETTLE_FRAMES)
            assertTrue(
                sim.state.kineticEnergy < config.quietKineticEnergy,
                "pile at per-particle mass $mass should settle, kinetic energy " +
                    "was ${sim.state.kineticEnergy}",
            )
        }
    }

    @Test
    fun `spongy and firm material both settle`() {
        // ADR 0001's headline finding is that "spongy" is not threatened by the
        // budget, and ADR 0003's is that 8 substeps is the lowest count that
        // settles *across the whole stiffness range*. If only the middle of the
        // range settled, stability would depend on a compliance dial the
        // designer expects to be free — which is the specific failure the
        // substep floor exists to prevent.
        for (compliance in floatArrayOf(1e-8f, 1e-6f, 1e-5f)) {
            val config = config().copy(
                distanceCompliance = compliance,
                areaCompliance = compliance,
            )
            val sim = TestScenes.pile(config, bodies = 16)
            TestScenes.run(sim, SETTLE_FRAMES)
            assertTrue(
                sim.state.kineticEnergy < config.quietKineticEnergy,
                "pile at compliance $compliance should settle, kinetic energy " +
                    "was ${sim.state.kineticEnergy}",
            )
        }
    }

    /**
     * A deep, narrow pile — far more compressive load per body than the real
     * well produces.
     *
     * ADR 0003 records as "unease worth recording" that its stability numbers
     * came from a tower rather than a filled well, and that the wide-well case
     * "has not been measured. It should be checked at Milestone 1." Both
     * shapes are measured here. The wide well turns out to be the *gentler*
     * of the two, so the ADR's conclusions are conservative in the direction
     * it hoped.
     */
    /**
     * The scene is defined by its **shape** — two columns, twenty rows — not by
     * the literal width, so the width has to follow the piece size.
     *
     * It was 5.0 while a piece measured 2.25 across. ADR 0011 made the material
     * extent the gameplay constant at 2.40, and at a seeding pitch of
     * `2.40 * 1.05 = 2.52` only *one* body fits per row in a 5.0-wide well.
     * That silently turns this into a single 40-body column 100 units tall in a
     * 60-unit well — bodies seeded far above the top, which is not a deep pile
     * and not a test of anything. Widening to 5.5 restores the two-by-twenty
     * tower the assertions were measured against.
     */
    private fun deepPileConfig() = SimConfig(lattice = 5, wellWidth = 5.5f, wellHeight = 60f)

    @Test
    fun `a deep pile under heavy compression settles`() {
        val config = deepPileConfig()
        val sim = TestScenes.pile(config, bodies = 40)
        TestScenes.run(sim, SETTLE_FRAMES)

        assertTrue(
            sim.state.kineticEnergy < config.quietKineticEnergy,
            "a 40-body tower should settle, kinetic energy was ${sim.state.kineticEnergy}",
        )
    }

    @Test
    fun `dropping below the substep floor destabilises a deep pile`() {
        // ADR 0003 rejects treating substeps as a runtime quality dial:
        // dropping them converts a framerate problem into a jitter problem,
        // and jitter reads as a bug. This is the regression guard for that —
        // if someone "optimises" substeps down, the deep pile stops being a
        // pile at all.
        //
        // NOTE: this measures the *direction* of ADR 0003's finding, not its
        // table. Measured here, the floor sits between 2 and 4 substeps, not
        // at 8: 4, 6, 8, 12 and 16 all settle in both scene shapes with no
        // trend, and only 2 fails. The shipped default stays at 8 because it
        // is pinned by docs/contracts.md and comfortably affordable, but the
        // disagreement with ADR 0003's table is flagged for the Architect in
        // handoff 0006 rather than quietly encoded here.
        val config = deepPileConfig()
        val settled = energyAfterSettling(config.copy(substeps = 8))
        val unsettled = energyAfterSettling(config.copy(substeps = 2))

        assertTrue(
            settled < config.quietKineticEnergy,
            "8 substeps must settle a deep pile, kinetic energy was $settled",
        )
        assertTrue(
            unsettled > UNSTABLE_ENERGY,
            "2 substeps should visibly fail on a deep pile — measured $unsettled. " +
                "If this now settles, the solver's stability characteristics have " +
                "changed and the substep floor needs re-measuring before it is trusted.",
        )
    }

    private fun energyAfterSettling(config: SimConfig): Float {
        val sim = TestScenes.pile(config, bodies = 40)
        TestScenes.run(sim, SETTLE_FRAMES)
        return sim.state.kineticEnergy
    }

    private companion object {
        /** Long enough for a freshly seeded pile to close its gaps and stop. */
        const val SETTLE_FRAMES = 900

        /** Ten seconds of doing nothing. Long enough for a slow creep to show. */
        const val HOLD_FRAMES = 600

        /** Well units. Generous: the assertion is "not creeping", not "frozen". */
        const val CREEP_TOLERANCE = 0.05f

        /**
         * Kinetic energy that is unambiguously a failed solve rather than a
         * noisy settle.
         *
         * Re-measured when the material was softened to distanceCompliance
         * 1e-4, exactly as the test below asks for. A deep pile at 2 substeps
         * now reaches ~89, where the rigid material reached ~1.4e5 — softer
         * material fails less violently, because a compliant constraint
         * transmits less of the correction it cannot make. The *direction* is
         * unchanged and still enormous: 2 substeps is ~89 against ~2e-4 at 4
         * substeps and beyond, a margin of five orders of magnitude. The bound
         * was lowered from 100 to sit inside that gap rather than on the edge
         * of the failing value.
         */
        const val UNSTABLE_ENERGY = 10f
    }
}
