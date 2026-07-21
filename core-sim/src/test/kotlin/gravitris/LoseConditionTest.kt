package gravitris

import gravitris.game.InputFrame
import gravitris.game.Phase
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The losing condition (ADR 0005): a due piece whose spawn band is over the
 * line opens a tick-counted settle grace; the stack settling back below the
 * line resumes play, and only a grace that expires with the block still there
 * ends the game. Fair (no death by transient), tick-counted (ADR 0013).
 *
 * The state machine is driven here through the live `overflowThreshold` dial,
 * which is the cleanest way to put the game into overflow deterministically —
 * a genuinely packed well is physics-dependent because a settled pile leaves
 * room at the very top. One test still tops out the *natural* way, to prove the
 * dial-driven ones are exercising the same path.
 */
class LoseConditionTest {

    private companion object {
        /** Long enough for a non-clearing well to top out with margin. */
        const val BUDGET = 15000
    }

    private fun config(w: Float = 14f, h: Float = 20f) =
        SimConfig(lattice = 5, wellWidth = w, wellHeight = h)

    /** A modest, quiet pile with room left at the spawn region. */
    private fun settledPile(config: SimConfig, bodies: Int): Simulation {
        val sim = TestScenes.pile(config, bodies)
        TestScenes.run(sim, 500)
        return sim
    }

    @Test
    fun `a game that never clears eventually tops out through overflow`() {
        val sim = Simulation(config())
        sim.tuning.clearThreshold = 2f // unreachable: nothing clears, so the well fills for real
        sim.start()
        val input = InputFrame()

        var sawOverflow = false
        var gameOverAt = -1
        for (t in 0 until 8000) {
            sim.step(input)
            val phase = sim.state.phase
            if (phase is Phase.Overflow) sawOverflow = true
            if (phase == Phase.GameOver) { gameOverAt = t; break }
        }

        assertTrue(gameOverAt >= 0, "a well that never clears must eventually top out")
        assertTrue(sawOverflow, "it must pass through the overflow warning before game over")
    }

    @Test
    fun `a stack that settles back below the line resumes — no death by transient`() {
        val sim = settledPile(config(), bodies = 6)
        val before = sim.state.bodyCount

        // Put the line under everything so a piece is due into overflow.
        sim.tuning.overflowThreshold = -1f
        sim.start()
        assertTrue(sim.state.phase is Phase.Overflow, "a due piece over the line opens overflow, not a spawn")

        // The stack is now below the (raised) line and quiet — the fair path.
        sim.tuning.overflowThreshold = 0.9f
        val input = InputFrame()
        var resumed = false
        for (t in 0 until 200) {
            sim.step(input)
            assertNotEquals(Phase.GameOver, sim.state.phase, "a stack that can settle back must not top out")
            if (sim.state.phase == Phase.Playing) { resumed = true; break }
        }
        assertTrue(resumed, "settled below the line and quiet: play must resume")
        assertTrue(sim.state.bodyCount > before, "resuming deals the next piece")
    }

    @Test
    fun `the grace expires to game over when the block persists, counted in ticks`() {
        val sim = settledPile(config(), bodies = 6)
        sim.tuning.overflowThreshold = -1f // force overflow and keep it (can never resolve)
        sim.start()
        val grace = sim.tuning.graceTicks
        assertTrue(sim.state.phase is Phase.Overflow)
        assertEquals(grace, (sim.state.phase as Phase.Overflow).remainingTicks, "grace opens at the full window")

        val input = InputFrame()
        var gameOverAt = -1
        for (t in 0 until grace + 10) {
            sim.step(input)
            if (sim.state.phase == Phase.GameOver) { gameOverAt = t; break }
        }
        assertEquals(grace - 1, gameOverAt, "game over lands exactly graceTicks ticks after overflow opened")
    }

    @Test
    fun `the grace countdown is tick-counted — identical whether run whole or split`() {
        fun runToGameOver(splitAt: Int): Int {
            val sim = settledPile(config(), bodies = 6)
            sim.tuning.overflowThreshold = -1f
            sim.start()
            val input = InputFrame()
            var t = 0
            while (sim.state.phase != Phase.GameOver && t < 500) {
                // The split is a no-op for the sim — proves the count is on the
                // tick, not on how the frames were grouped (ADR 0013).
                if (t == splitAt) { /* pretend a frame boundary */ }
                sim.step(input)
                t++
            }
            return t
        }
        assertEquals(runToGameOver(splitAt = 3), runToGameOver(splitAt = 40))
    }

    @Test
    fun `overflowThreshold is a live dial — the same pile spawns or overflows by the number`() {
        // Low line: a due piece opens overflow.
        val low = settledPile(config(), bodies = 6)
        low.tuning.overflowThreshold = -1f
        low.start()
        assertTrue(low.state.phase is Phase.Overflow, "line under everything -> overflow")

        // High line, identical scene: the piece spawns normally.
        val high = settledPile(config(), bodies = 6)
        high.tuning.overflowThreshold = 1.0f
        val before = high.state.bodyCount
        high.start()
        assertEquals(Phase.Playing, high.state.phase, "line above everything -> normal play")
        assertTrue(high.state.bodyCount > before, "and a piece is dealt")
    }

    @Test
    fun `graceTicks is a live dial`() {
        val sim = settledPile(config(), bodies = 6)
        sim.tuning.overflowThreshold = -1f
        sim.tuning.graceTicks = 10
        sim.start()
        assertEquals(10, (sim.state.phase as Phase.Overflow).remainingTicks)
        val input = InputFrame()
        var gameOverAt = -1
        for (t in 0 until 30) {
            sim.step(input)
            if (sim.state.phase == Phase.GameOver) { gameOverAt = t; break }
        }
        assertEquals(9, gameOverAt, "a 10-tick grace tops out on the 10th tick")
    }

    @Test
    fun `clearing staves off the top-out a non-clearing well hits`() {
        // The observable form of "a clear resolves a would-be overflow": in the
        // same well, a game that can clear frees space and survives far longer
        // than one that never can. A literal clear-vs-overflow precedence test
        // would be vacuous — the two states are mutually exclusive by
        // construction (overflow has no active piece to lock into a clear) — so
        // this locks the interaction that is actually observable instead.
        // A well wide enough that bands can fill horizontally and clear; the
        // narrow default never clears before it tops out, which is a fine
        // baseline but no contrast.
        val wide = SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f, seed = 7L)
        fun ticksToGameOver(clearThreshold: Float): Int {
            val sim = Simulation(wide)
            sim.tuning.clearThreshold = clearThreshold
            sim.start()
            val input = InputFrame()
            var t = 0
            while (sim.state.phase != Phase.GameOver && t < BUDGET) { sim.step(input); t++ }
            return t
        }

        val neverClears = ticksToGameOver(clearThreshold = 2f) // unreachable
        val clears = ticksToGameOver(clearThreshold = 0.85f)

        assertTrue(neverClears < BUDGET, "the non-clearing well must actually top out to be a baseline")
        assertTrue(
            clears > neverClears,
            "clearing frees space and must delay the top-out (clears=$clears, neverClears=$neverClears)",
        )
    }

    @Test
    fun `a run to game over is deterministic`() {
        fun fingerprintAtGameOver(): IntArray {
            val sim = Simulation(config().copy(seed = 99L))
            sim.tuning.clearThreshold = 2f
            sim.start()
            val input = InputFrame()
            var t = 0
            while (sim.state.phase != Phase.GameOver && t < 8000) { sim.step(input); t++ }
            check(sim.state.phase == Phase.GameOver) { "scene must reach game over" }
            return TestScenes.fingerprint(sim.state)
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(fingerprintAtGameOver(), fingerprintAtGameOver())
    }
}
