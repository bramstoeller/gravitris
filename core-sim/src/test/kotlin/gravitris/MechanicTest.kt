package gravitris

import gravitris.game.InputFrame
import gravitris.game.Phase
import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stage 3A: the mechanic. `docs/build-order.md` names the pieces —
 *
 * > Piece spawning + sequence, lock detection via kinetic energy, coverage
 * > bands (ADR 0004), the clear rule, stack drop and re-settle.
 *
 * — and this is the behavioural coverage of all of them. Determinism and
 * zero-allocation of the same path are held by `DeterminismTest` and
 * `AllocationTest`; this file is about *what happens*, not about how it is
 * represented.
 *
 * Every scene here seeds bodies with a gap ([TestScenes]) for the reason the
 * spike learned the hard way, and drives whole ticks through the public
 * [Simulation.step] rather than reaching inside — a test that reads private
 * state would pass while the contract `:app` sees is broken.
 */
class MechanicTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 10f, wellHeight = 20f)

    // --- spawning -----------------------------------------------------------

    @Test
    fun `pieces are dealt one at a time, never in a batch`() {
        // A tall, empty well so the game keeps dealing without filling, and a
        // threshold no fill can reach so a clear never removes a body and
        // confounds the count.
        val sim = Simulation(config().copy(wellHeight = 44f))
        sim.tuning.clearThreshold = 1.01f
        sim.start()
        val input = InputFrame()

        // A lock and the next spawn happen inside the same tick — the game
        // never idles with no piece — so the observable "one at a time" is on
        // the body count: it rises by at most one per tick (one new piece) and,
        // with clears suppressed, never falls.
        var prevBodies = sim.state.bodyCount
        var maxJumpPerTick = 0
        var everFell = false
        var activeAlwaysValid = true

        repeat(1500) {
            sim.step(input)
            val bodies = sim.state.bodyCount
            val active = sim.state.activePieceBody
            if (active !in -1 until bodies) activeAlwaysValid = false
            val jump = bodies - prevBodies
            if (jump > maxJumpPerTick) maxJumpPerTick = jump
            if (jump < 0) everFell = true
            prevBodies = bodies
        }

        assertTrue(activeAlwaysValid, "activePieceBody left the valid range -1..bodyCount-1")
        assertEquals(1, maxJumpPerTick, "more than one piece was dealt in a single tick")
        assertFalse(everFell, "a clear removed material despite an unreachable threshold")
        assertTrue(sim.state.bodyCount >= 4, "the game stopped dealing pieces")
    }

    @Test
    fun `a new piece spawns into the band the losing condition watches`() {
        // ADR 0005 builds overflow on the fill of the spawn band; that only
        // means anything if the spawn region is one of the coverage bands.
        val config = config()
        val sim = Simulation(config)
        val idx = sim.state.spawnBandIndex
        assertTrue(
            idx in 0 until config.bandCount,
            "spawnBandIndex $idx is not a coverage band (0 until ${config.bandCount})",
        )
        // And it is near the top, where a piece enters — not the floor.
        assertTrue(idx >= config.bandCount / 2, "spawn band $idx is not in the upper well")
    }

    // --- lock detection -----------------------------------------------------

    @Test
    fun `a settling piece locks by kinetic energy, not by the timeout`() {
        // Set the timeout ceiling unreachably high, so a lock inside a sane
        // number of ticks can only be the energy path firing — a clean drop
        // into an empty well goes still and locks on its own. (The mirror case,
        // where only the timeout can lock, is the next test.)
        val sim = Simulation(config().copy(lockTimeoutTicks = 100_000))
        assertTrue(
            locksWithin(sim, 300),
            "a piece dropped into an empty well must go still and lock via kinetic energy",
        )
    }

    @Test
    fun `a piece that never goes still is locked by the timeout ceiling`() {
        // The settled-pile drift is real: a pile in this solver keeps creeping,
        // so a piece resting in it can be nudged above any energy threshold
        // forever. The timeout is the deliberate bound on that unbounded wait.
        //
        // Modelled at its extreme: an impossible energy threshold of 0, which a
        // live pile can never satisfy. With a short timeout the piece must lock
        // anyway; with an unreachable timeout it must NOT — which proves the
        // timeout, not the energy test, is what frees it.
        val floorTicks = 200

        val bounded = Simulation(config().copy(lockKineticEnergy = 0f, lockTimeoutTicks = 30))
        assertTrue(
            locksWithin(bounded, floorTicks),
            "with a 30-tick ceiling the piece must lock even though its energy never falls to 0",
        )

        val unbounded = Simulation(config().copy(lockKineticEnergy = 0f, lockTimeoutTicks = 100_000))
        assertFalse(
            locksWithin(unbounded, floorTicks),
            "with no reachable ceiling and an impossible energy test the piece must never lock — " +
                "if it did, the energy path is locking it and this test proves nothing",
        )
    }

    /** Whether [sim]'s first dealt piece releases control within [ticks]. */
    private fun locksWithin(sim: Simulation, ticks: Int): Boolean {
        sim.start()
        val first = sim.state.activePieceBody
        val input = InputFrame()
        repeat(ticks) {
            sim.step(input)
            if (sim.state.activePieceBody != first) return true
        }
        return false
    }

    // --- coverage bands -----------------------------------------------------

    @Test
    fun `coverage bands fill from the floor up as material settles`() {
        val config = config()
        val sim = TestScenes.pile(config, bodies = 9) // three rows
        TestScenes.run(sim, 400)
        val fill = sim.state.bandFill

        // The floor band carries material; a band high above the three-row pile
        // carries none. Between them is the pile, which is the point of the
        // measurement — coverage is a property of height, read per band.
        assertTrue(fill[0] > 0.4f, "floor band barely filled: ${fill[0]}")
        assertTrue(
            fill[config.bandCount - 1] < 0.05f,
            "top band shows material over an empty region: ${fill[config.bandCount - 1]}",
        )
        assertTrue(fill.max() > fill.last(), "fill must fall off above the pile")
    }

    // --- the clear rule + runtime tunability --------------------------------

    @Test
    fun `the clear threshold is tunable at runtime and decides whether a band clears`() {
        // Same physical scene, same seed, two thresholds. A guess of ~90% is in
        // the brief precisely because nobody has played it, so the threshold is
        // a live dial (MechanicTuning) and this is the behaviour that makes it
        // one: turning it changes what clears, on an already-built simulation.
        val reachable = settledMaxFill()
        assertTrue(reachable >= 0.5f, "test scene does not fill a band enough to clear: $reachable")

        // Unreachable threshold: the piece still locks, but nothing clears and
        // nothing is ever removed.
        val high = startedOnSettledPile(threshold = 1.01f)
        val highBefore = high.state.bodyCount
        val highResult = play(high, 400)
        assertFalse(highResult.sawClearing, "a clear fired against an unreachable threshold")
        assertTrue(
            highResult.minBodyCount >= highBefore,
            "material was removed with no clear (fell to ${highResult.minBodyCount} from $highBefore)",
        )

        // Reachable threshold on the identical scene: a band clears and material
        // leaves. Removal lands 24 ticks after the clear starts, so this looks
        // at the whole window, not the starting instant.
        val low = startedOnSettledPile(threshold = reachable * 0.7f)
        val lowBefore = low.state.bodyCount
        val lowResult = play(low, 400)
        assertTrue(lowResult.sawClearing, "a reachable threshold did not clear a filled band")
        assertTrue(
            lowResult.minBodyCount < lowBefore,
            "a clear removed no material (bodyCount held at $lowBefore)",
        )
    }

    private class PlayResult(val sawClearing: Boolean, val minBodyCount: Int)

    /** Plays [ticks] ticks, recording whether a clear ran and the low-water body count. */
    private fun play(sim: Simulation, ticks: Int): PlayResult {
        val input = InputFrame()
        var sawClearing = false
        var minBodyCount = sim.state.bodyCount
        repeat(ticks) {
            sim.step(input)
            if (sim.state.phase is Phase.Clearing) sawClearing = true
            if (sim.state.bodyCount < minBodyCount) minBodyCount = sim.state.bodyCount
        }
        return PlayResult(sawClearing, minBodyCount)
    }

    @Test
    fun `a clear ignites, removes material after the envelope, then holds the watch window`() {
        val sim = startedOnSettledPile(threshold = settledMaxFill() * 0.7f)
        val tuning = sim.tuning
        val bodiesBefore = sim.state.bodyCount
        val input = InputFrame()

        var clearStart = -1
        var removalStep = -1
        var clearingSteps = 0
        var sawProgressDuringEnvelope = false
        var progressAllResetAfter = true

        var step = 0
        val limit = 400
        while (step < limit) {
            sim.step(input)
            val clearing = sim.state.phase is Phase.Clearing
            if (clearing && clearStart < 0) clearStart = step
            if (clearStart >= 0) {
                if (clearing) {
                    clearingSteps++
                    // Within the ignition/hold/dissolve envelope a band drives
                    // the flash through bandClearProgress, not through fill.
                    if (step - clearStart < tuning.clearEnvelopeTicks &&
                        sim.state.bandClearProgress.any { it >= 0f }
                    ) {
                        sawProgressDuringEnvelope = true
                    }
                }
                if (removalStep < 0 && sim.state.bodyCount < bodiesBefore) removalStep = step
                // Once play has resumed, every band must be back to -1.
                if (!clearing && clearStart >= 0 && clearingSteps > 0) {
                    if (sim.state.bandClearProgress.any { it >= 0f }) progressAllResetAfter = false
                    break
                }
            }
            step++
        }

        assertTrue(clearStart >= 0, "no clear occurred")
        assertTrue(sawProgressDuringEnvelope, "bandClearProgress never drove the ignition envelope")
        // Material is removed exactly at the end of the ignition-hold-dissolve
        // envelope — not before (the flash needs something to flash on) and not
        // teleported away instantly.
        assertEquals(
            tuning.clearEnvelopeTicks,
            removalStep - clearStart,
            "material must be removed at the envelope end, not earlier or later",
        )
        // The payoff moment is watched, not rushed: play is held for at least
        // the minimum window and never past the maximum (feel-feedback.md,
        // tick-counted per ADR 0013).
        assertTrue(
            clearingSteps in tuning.clearMinTicks..tuning.clearMaxTicks,
            "clear held play for $clearingSteps ticks, outside " +
                "[${tuning.clearMinTicks}, ${tuning.clearMaxTicks}]",
        )
        assertTrue(progressAllResetAfter, "bandClearProgress must return to -1 once play resumes")
    }

    @Test
    fun `the stack stays finite and inside the well across clears and re-settles`() {
        // The re-settle drops whole bodies under the same solver as everything
        // else. If removeBody left a dangling constraint or a stale index, the
        // classic failure is an energy explosion — particles flung out of the
        // well or to NaN. A drop is allowed to be violent; leaving the world is
        // not.
        val config = config()
        val sim = Simulation(config)
        sim.tuning.clearThreshold = 0.6f // reachable, so clears actually happen
        sim.start()
        val input = InputFrame()

        var clears = 0
        var wasClearing = false
        repeat(4000) {
            sim.step(input)
            val clearing = sim.state.phase is Phase.Clearing
            if (clearing && !wasClearing) clears++
            wasClearing = clearing
            assertWithinWorld(sim.state, config)
        }
        assertTrue(clears > 0, "the run never exercised a clear")
    }

    private fun assertWithinWorld(s: SimState, config: SimConfig) {
        val n = s.particleCount
        // Generous bounds: a hard drop and squash legitimately push material a
        // little past the walls, but not by well-lengths, and never to NaN.
        val margin = 5f
        for (i in 0 until n) {
            val x = s.positionX[i]
            val y = s.positionY[i]
            assertTrue(x.isFinite() && y.isFinite()) {
                "particle $i left the reals at ($x, $y) — removeBody likely corrupted state"
            }
            assertTrue(
                x > -margin && x < config.wellWidth + margin &&
                    y > -margin && y < config.wellHeight + margin,
            ) { "particle $i escaped the well at ($x, $y)" }
        }
    }

    // --- shared scene helpers ----------------------------------------------

    private fun settledMaxFill(): Float {
        val sim = TestScenes.pile(config(), bodies = 9)
        TestScenes.run(sim, 400)
        return sim.state.bandFill.max()
    }

    /**
     * A three-row settled pile with the game running and [threshold] set, so
     * the next lock evaluates the clear rule against a well-filled floor.
     */
    private fun startedOnSettledPile(threshold: Float): Simulation {
        val sim = TestScenes.pile(config(), bodies = 9)
        TestScenes.run(sim, 400)
        sim.tuning.clearThreshold = threshold
        sim.start()
        return sim
    }
}
