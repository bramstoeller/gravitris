package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

    private fun config() = SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f)

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
            tick % 101 == 0 -> sim.slamActivePiece(9f) // impact-velocity probe (ADR 0016)
        }
        sim.step(input)
    }

    private fun runScripted(frames: Int): IntArray {
        val sim = TestScenes.pile(config(), bodies = 8)
        sim.addPiece(archetype = 3, centerX = 9f, centerY = TestScenes.stackHeight(sim.state) + 5f)
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
        sim.addPiece(archetype = 3, centerX = 9f, centerY = TestScenes.stackHeight(sim.state) + 5f)
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
        sim.addPiece(archetype = 3, centerX = 9f, centerY = TestScenes.stackHeight(sim.state) + 5f)
        for (tick in 0 until 900) driveTick(sim, InputFrame(), tick)

        assertArrayEquals(
            runScripted(900),
            TestScenes.fingerprint(sim.state),
            "the core must not depend on InputFrame identity or mutate it",
        )
    }

    /**
     * The physics tests above drive pieces in by hand. The *game* adds two
     * things that must be deterministic in their own right: the piece sequence
     * (a seeded PRNG) and a clear (which calls `removeBody`, rebasing every
     * particle and constraint array). A replay fixture is only a fixture if the
     * same seed deals the same pieces and removes the same bodies in the same
     * order, bit for bit.
     *
     * Tuning is left at its defaults on purpose: `MechanicTuning` warns that a
     * value changed mid-run makes the run unrepeatable, and a replay fixture is
     * exactly the thing that must not touch it. So this runs long enough for a
     * clear to occur naturally at the default threshold rather than forcing one
     * by lowering it.
     */
    private fun runStartedGame(frames: Int, seed: Long): Pair<IntArray, Boolean> {
        val sim = Simulation(config().copy(seed = seed))
        sim.start()
        val input = InputFrame()
        var prevBodies = sim.state.bodyCount
        // A clear is the only thing that drops the body count from one tick to
        // the next (spawns only add). Catch the drop as it happens rather than
        // comparing final-vs-peak: the game keeps dealing after a clear, so the
        // count climbs back to a new peak and a final-vs-peak test would miss it.
        var sawRemoval = false
        repeat(frames) {
            sim.step(input)
            val bodies = sim.state.bodyCount
            if (bodies < prevBodies) sawRemoval = true
            prevBodies = bodies
        }
        return TestScenes.fingerprint(sim.state) to sawRemoval
    }

    @Test
    fun `a running game is bit-identical across runs, clears included`() {
        val (a, aCleared) = runStartedGame(2600, seed = 20260720L)
        val (b, _) = runStartedGame(2600, seed = 20260720L)
        assertArrayEquals(a, b, "two runs of a seeded game must be bit-identical")
        assertTrue(
            aCleared,
            "2600 ticks did not reach a clear, so removeBody was not exercised — raise the budget",
        )
    }

    @Test
    fun `a running game does not depend on where the step loop is split`() {
        val sim = Simulation(config().copy(seed = 20260720L))
        sim.start()
        val input = InputFrame()
        repeat(1300) { sim.step(input) }
        repeat(1300) { sim.step(input) }

        val (whole, _) = runStartedGame(2600, seed = 20260720L)
        assertArrayEquals(
            whole,
            TestScenes.fingerprint(sim.state),
            "1300 + 1300 must equal 2600 for a running game too",
        )
    }

    /**
     * The control lifecycle (ADR 0017) applies steer *and* rotate to the active
     * piece for the whole descent — both are live on every tick a piece exists,
     * with no phase gate between them. None of the other determinism tests
     * exercise input through a *running* game (they use a harness piece or empty
     * input), so this one drives continuous steer-and-rotate intent against the
     * dealer and asserts the whole thing still replays bit for bit. If the input
     * path ever depended on anything outside (state, input), it shows here.
     */
    private fun runControlledGame(frames: Int, seed: Long): IntArray {
        val sim = Simulation(config().copy(seed = seed))
        sim.start()
        val input = InputFrame()
        for (tick in 0 until frames) {
            input.clear()
            input.dragX = if ((tick / 8) % 2 == 0) 0.18f else -0.14f
            if (tick % 17 == 0) input.rotate = true
            sim.step(input)
        }
        return TestScenes.fingerprint(sim.state)
    }

    @Test
    fun `a running game with continuous steer-and-rotate input is bit-identical across runs`() {
        assertArrayEquals(
            runControlledGame(1500, seed = 424242L),
            runControlledGame(1500, seed = 424242L),
            "steer-and-rotate for the whole descent must replay bit-identically",
        )
    }

    @Test
    fun `a different seed deals a different sequence of pieces`() {
        // At Stage 3 an archetype is a colour index only — every piece is the
        // same square lattice — so two seeds deal physically identical piles
        // and the *positions* cannot tell them apart. What the seed changes is
        // the archetype order, which is the thing that must actually be seeded.
        assertFalse(
            dealtArchetypes(1200, seed = 1L).contentEquals(dealtArchetypes(1200, seed = 2L)),
            "two seeds dealt the same archetype sequence — the piece bag is not seeded",
        )
        assertArrayEquals(
            dealtArchetypes(1200, seed = 7L),
            dealtArchetypes(1200, seed = 7L),
            "the same seed must deal the same archetype sequence",
        )
    }

    /**
     * The archetype of every body present after [frames] ticks. Run short
     * enough (< first clear) that no body has been removed, so this is the deal
     * order intact.
     */
    private fun dealtArchetypes(frames: Int, seed: Long): IntArray {
        val sim = Simulation(config().copy(seed = seed))
        sim.start()
        val input = InputFrame()
        repeat(frames) { sim.step(input) }
        val n = sim.state.bodyCount
        return IntArray(n) { sim.state.bodyArchetype[it] }
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
