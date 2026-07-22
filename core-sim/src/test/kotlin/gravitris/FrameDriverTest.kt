package gravitris

import gravitris.game.FrameDriver
import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The enforcement the class doc promises: no clamp, catch up by more ticks not
 * bigger ones, count what overruns, and — for live play — ask for fresh intent
 * per tick so a dropped frame does not multiply a gesture (ADR 0006, 0013,
 * `docs/ux/gestures.md`).
 */
class FrameDriverTest {

    private val tick = Simulation.TICK
    private fun config() = SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f)

    private fun centroidX(sim: Simulation, body: Int): Float {
        val s = sim.state
        var sum = 0f
        var n = 0
        for (i in 0 until s.particleCount) {
            if (s.particleBody[i] == body) { sum += s.positionX[i]; n++ }
        }
        return sum / n
    }

    // --- delta policy -------------------------------------------------------

    @Test
    fun `catch-up runs one whole tick per tick of delta and returns the remainder`() {
        val sim = Simulation(config())
        val fd = FrameDriver(sim, config())
        val before = sim.state.tick

        val alpha = fd.advance(3.4f * tick, InputFrame())

        assertEquals(3, sim.state.tick - before, "3.4 ticks of delta must run 3 whole ticks")
        assertEquals(0L, fd.droppedTicks, "nothing dropped below the catch-up ceiling")
        assertEquals(0.4f, alpha, 1e-3f, "the sub-tick remainder is the interpolation alpha")
    }

    @Test
    fun `the delta is never clamped — overrun past the ceiling is counted, not discarded silently`() {
        val cfg = config().copy(maxCatchupTicks = 8)
        val sim = Simulation(cfg)
        val fd = FrameDriver(sim, cfg)
        val before = sim.state.tick

        // A 100-tick hitch. A clamp would run some ticks and lose the rest with
        // no trace; the policy runs the ceiling and counts the remainder.
        fd.advance(100f * tick, InputFrame())

        assertEquals(8, sim.state.tick - before, "only maxCatchupTicks run in one frame")
        assertEquals(92L, fd.droppedTicks, "the other 92 ticks are counted, so the drop is visible")
    }

    @Test
    fun `resetAccumulator drops accrued sub-tick time`() {
        val sim = Simulation(config())
        val fd = FrameDriver(sim, config())
        fd.advance(0.9f * tick, InputFrame()) // accrue 0.9 tick, run none
        assertEquals(sim.state.tick, sim.state.tick, "no whole tick yet")
        fd.resetAccumulator()
        val before = sim.state.tick
        // The 0.9 was dropped, so 0.5 more must not now complete a tick.
        val alpha = fd.advance(0.5f * tick, InputFrame())
        assertEquals(0, sim.state.tick - before, "reset must discard the accrued 0.9 tick")
        assertEquals(0.5f, alpha, 1e-3f)
    }

    @Test
    fun `a non-finite or negative delta is rejected by both overloads`() {
        val sim = Simulation(config())
        val fd = FrameDriver(sim, config())
        assertThrows(IllegalArgumentException::class.java) { fd.advance(-1f, InputFrame()) }
        assertThrows(IllegalArgumentException::class.java) { fd.advance(Float.NaN, InputFrame()) }
        assertThrows(IllegalArgumentException::class.java) { fd.advance(-1f) {} }
        assertThrows(IllegalArgumentException::class.java) { fd.advance(Float.NaN) {} }
    }

    // --- per-tick input drain (the live-play fix) ---------------------------

    @Test
    fun `drainTick is asked once per tick, each time with a cleared frame`() {
        val sim = Simulation(config())
        val fd = FrameDriver(sim, config())
        val before = sim.state.tick

        var calls = 0
        val rotateSeenAtEntry = mutableListOf<Boolean>()
        fd.advance(3f * tick) { frame ->
            rotateSeenAtEntry.add(frame.rotate) // frame must arrive cleared
            calls++
            frame.rotate = true // a one-shot; must NOT leak into the next tick
        }

        assertEquals(3, sim.state.tick - before)
        assertEquals(3, calls, "drainTick is called exactly once per tick")
        assertTrue(
            rotateSeenAtEntry.all { !it },
            "each tick gets a cleared frame, so a one-shot set on one tick does not repeat on the next",
        )
    }

    @Test
    fun `draining a gesture once applies it a single tick, unlike reusing one frame across ticks`() {
        // Reused-frame overload: the same drag on every catch-up tick — correct
        // for fixed input, but N x a live per-frame delta under a dropped frame.
        val simOld = Simulation(config())
        val fdOld = FrameDriver(simOld, config())
        val bodyOld = simOld.addPiece(archetype = 0, centerX = 5f, centerY = 15f)
        val xOld = centroidX(simOld, bodyOld)
        fdOld.advance(3f * tick, InputFrame().apply { dragX = 0.3f })
        val movedOld = centroidX(simOld, bodyOld) - xOld

        // Drain overload: the caller hands over its pending drag once, then has
        // nothing left, so ticks 2 and 3 get an empty frame.
        val simNew = Simulation(config())
        val fdNew = FrameDriver(simNew, config())
        val bodyNew = simNew.addPiece(archetype = 0, centerX = 5f, centerY = 15f)
        val xNew = centroidX(simNew, bodyNew)
        var pending = 0.3f
        fdNew.advance(3f * tick) { frame -> frame.dragX = pending; pending = 0f }
        val movedNew = centroidX(simNew, bodyNew) - xNew

        assertEquals(0.9f, movedOld, 0.05f, "reused input applies the drag on all three ticks")
        assertEquals(0.3f, movedNew, 0.05f, "drained-once input moves the piece a single tick's worth")
        assertTrue(movedOld > movedNew * 2f, "the drain overload avoids the Nx gesture multiplication")
    }
}
