package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * ADR 0006: the simulation is deterministic, and that is a load-bearing
 * property rather than a nice-to-have. It is what lets QA record an input
 * sequence and replay it as a regression test on the JVM with no device and no
 * renderer, and it is why a physics bug can be reproduced from a seed and an
 * input log instead of from "it jittered once".
 *
 * The assertion is bit-identical, not approximately equal.
 */
class DeterminismTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 10f, wellHeight = 20f)

    /**
     * A scripted input sequence, so the replay exercises drag, rotation and
     * hard drop rather than only free fall. Deliberately a pure function of
     * the tick index: the same schedule can be reproduced by both runs without
     * sharing mutable state between them.
     */
    private fun driveTick(sim: Simulation, input: InputFrame, tick: Int) {
        input.clear()
        when {
            tick % 47 == 0 -> input.rotate = true
            tick % 13 == 0 -> input.dragX = if ((tick / 13) % 2 == 0) 0.22f else -0.17f
            tick % 101 == 0 -> {
                input.hardDrop = true
                input.hardDropVelocity = 9f
            }
        }
        sim.step(input)
    }

    private fun runScripted(frames: Int): IntArray {
        val sim = TestScenes.pile(config(), bodies = 8)
        sim.addPiece(archetype = 3, centerX = 5f, centerY = 17f)
        val input = InputFrame()
        for (tick in 0 until frames) driveTick(sim, input, tick)
        return TestScenes.fingerprint(sim.state)
    }

    @Test
    fun `same construction and same input sequence give bit-identical state`() {
        assertArrayEquals(
            runScripted(900),
            runScripted(900),
            "900 ticks of an identical scene and input schedule must be bit-identical",
        )
    }

    @Test
    fun `stepping is stateless across calls`() {
        // 450 + 450 must equal 900. If a tick ever depended on anything
        // outside (state, input) — a wall clock, an iteration count, a lazily
        // initialised buffer — this is where it would show.
        val sim = TestScenes.pile(config(), bodies = 8)
        sim.addPiece(archetype = 3, centerX = 5f, centerY = 17f)
        val input = InputFrame()
        for (tick in 0 until 450) driveTick(sim, input, tick)
        for (tick in 450 until 900) driveTick(sim, input, tick)

        assertArrayEquals(
            runScripted(900),
            TestScenes.fingerprint(sim.state),
            "splitting a run into two step loops must not change the result",
        )
    }

    @Test
    fun `a fresh InputFrame instance does not change the result`() {
        // `:app` reuses one InputFrame; a replay harness may build a fresh one
        // per tick. The core must not care, which is only true because `step`
        // never writes to its argument. If the core consumed the one-shot
        // flags in place, a recorded sequence would behave differently the
        // second time it was replayed.
        val sim = TestScenes.pile(config(), bodies = 8)
        sim.addPiece(archetype = 3, centerX = 5f, centerY = 17f)
        for (tick in 0 until 900) driveTick(sim, InputFrame(), tick)

        assertArrayEquals(
            runScripted(900),
            TestScenes.fingerprint(sim.state),
            "the core must not depend on InputFrame identity or mutate it",
        )
    }

    @Test
    fun `substep count is part of the simulation contract`() {
        // Not a curiosity: it is why ADR 0006 pins substeps as configuration
        // rather than a tunable, and why ADR 0003's floor of 8 had to be
        // settled before QA invested in replay fixtures. Changing it
        // invalidates every recorded replay.
        val eight = TestScenes.pile(config().copy(substeps = 8), bodies = 8)
        val twelve = TestScenes.pile(config().copy(substeps = 12), bodies = 8)
        TestScenes.run(eight, 300)
        TestScenes.run(twelve, 300)

        assertFalse(
            TestScenes.fingerprint(eight.state).contentEquals(TestScenes.fingerprint(twelve.state)),
            "changing substep count must change results — if it did not, the " +
                "substep loop would not be doing what ADR 0001 says it does",
        )
    }
}
