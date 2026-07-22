package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The single controlled descent (ADR 0017). A piece is either the active piece —
 * falling under real gravity and fully steerable, from spawn until it settles —
 * or it is not. There is no positioning window, no weightless hover, and no
 * phase that takes control away.
 *
 * These pin the behaviours the collapse of POSITIONING+FALLING introduced, over
 * and above what `MechanicTest` (lock rule) and `SolverBehaviourTest` (drag /
 * rotate mechanics) already hold: that gravity is on from the first tick, that
 * steer and rotate both apply mid-descent, that a gravity-from-spawn piece
 * cannot lock in mid-air or on the bottom of its landing squash, and that the
 * lock timeout is still measured from first contact (the `touchedTicks = -1`
 * sentinel).
 */
class ControlledDescentTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f)

    // --- gravity from spawn -------------------------------------------------

    @Test
    fun `the active piece falls under gravity from the spawn tick, with no hover`() {
        // Under ADR 0016 a fresh piece hovered weightless for the whole
        // positioning window (50 ticks by default) before it began to fall.
        // Under ADR 0017 it falls immediately. A handful of ticks — far fewer
        // than any window — must already show a clear drop, and the piece must
        // still be the same active piece (it cannot lock in the air it spawned
        // in).
        val sim = Simulation(config())
        sim.start()
        val body = sim.state.activePieceBody
        assertTrue(body >= 0, "the dealer did not make a piece active on start")

        val spawnY = centroidY(sim.state, body)
        val input = InputFrame()
        repeat(10) { sim.step(input) }

        assertEquals(
            body,
            sim.state.activePieceBody,
            "the piece locked within 10 ticks of spawning — it cannot have settled in mid-air",
        )
        val laterY = centroidY(sim.state, body)
        assertTrue(
            laterY < spawnY - 0.1f,
            "the piece did not fall from the spawn tick (spawn y=$spawnY, after 10 ticks=$laterY); " +
                "a weightless positioning window would have left it hovering",
        )
    }

    // --- steer AND rotate for the whole descent -----------------------------

    @Test
    fun `steer and rotate both take effect while the piece is still falling`() {
        // The reversal at the heart of the redesign: under ADR 0016 drag applied
        // only while positioning and rotate only while falling, never together.
        // Now both apply for the whole descent. A T (3 wide, 2 tall) is spawned
        // high so it is unambiguously mid-air; steering it moves it sideways
        // while gravity keeps pulling it down, and a quarter turn narrows it.
        val sim = Simulation(config())
        val body = sim.addPiece(archetype = 2, centerX = 9f, centerY = 22f)
        val input = InputFrame()

        val xBefore = centroidX(sim.state, body)
        val yBefore = centroidY(sim.state, body)
        input.clear()
        input.dragX = 0.4f
        sim.step(input)

        assertTrue(
            centroidX(sim.state, body) > xBefore + 0.2f,
            "steer must move the falling piece sideways (x $xBefore -> ${centroidX(sim.state, body)})",
        )
        assertTrue(
            centroidY(sim.state, body) < yBefore,
            "the piece must keep falling under gravity while it is steered — this is " +
                "'steer under accelerating gravity', not a positioning slide",
        )

        val widthBefore = bodyWidth(sim.state, body)
        input.clear()
        input.rotate = true
        sim.step(input)

        assertTrue(
            bodyWidth(sim.state, body) < widthBefore - 0.3f,
            "rotate must turn the same falling piece a quarter step (width $widthBefore -> " +
                "${bodyWidth(sim.state, body)}); steer and rotate both act on the one descent",
        )
    }

    // --- lock still needs contact + debounce --------------------------------

    @Test
    fun `a gravity-from-spawn piece never locks in mid-air or on its landing squash`() {
        // The contact requirement in hasSettled is exactly what makes
        // gravity-from-spawn safe: a piece spawns at zero velocity, so a pure
        // energy test would lock it the instant it appears. Requiring contact
        // rules that out; and the debounce rules out a lock on the instantaneous
        // stillness at the bottom of the impact squash.
        val sim = Simulation(config())
        sim.start()
        val body = sim.state.activePieceBody
        val input = InputFrame()

        var contactTick = -1
        for (tick in 0 until 1000) {
            sim.step(input)
            if (bodyInContact(sim.state, body)) {
                contactTick = tick
                break
            }
            // Airborne: touching nothing, so it must not have locked.
            assertEquals(
                body,
                sim.state.activePieceBody,
                "a piece touching nothing locked in mid-air at tick $tick",
            )
        }

        assertTrue(contactTick >= 0, "the piece never reached the floor within 1000 ticks")
        assertEquals(
            body,
            sim.state.activePieceBody,
            "the piece locked on its very first contact tick — the settle debounce (a soft body " +
                "wobbling on impact is not quiet) must hold it past the bottom of the squash",
        )
    }

    // --- the touchedTicks = -1 sentinel -------------------------------------

    @Test
    fun `the lock timeout is measured from first contact, preserving the sentinel`() {
        // The energy path is switched off (lockKineticEnergy = 0, which a moving
        // pile never satisfies) so the only way to lock is the timeout ceiling.
        // touchedTicks starts at -1: the FIRST contact tick sets it to 0 (not 1),
        // so the ceiling is reached exactly lockTimeoutTicks ticks after first
        // contact. Seeding 0 instead would fire the lock one tick early. The
        // first landing impact is gated on the identical inContactThisTick edge
        // hasSettled reads, so the gap from impact to lock is exactly the timeout.
        val timeout = 45
        val sim = Simulation(config().copy(lockKineticEnergy = 0f, lockTimeoutTicks = timeout))
        sim.start()
        val body = sim.state.activePieceBody
        val input = InputFrame()

        var firstImpactTick = -1
        var lockTick = -1
        var tick = 0
        while (tick < 1000 && lockTick < 0) {
            sim.step(input)
            if (firstImpactTick < 0 && sim.state.impacts.count > 0) firstImpactTick = tick
            if (sim.state.activePieceBody != body) lockTick = tick
            tick++
        }

        assertTrue(firstImpactTick >= 0, "the piece never reported a landing impact")
        assertTrue(lockTick >= 0, "the piece never locked via the timeout ceiling")
        assertEquals(
            timeout,
            lockTick - firstImpactTick,
            "the timeout must count from the first contact tick: seeding touchedTicks at 0 " +
                "rather than -1 would lock at ${timeout - 1} ticks after contact, one tick early",
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun centroidX(s: SimState, body: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until s.particleCount) {
            if (s.particleBody[i] == body) { sum += s.positionX[i]; n++ }
        }
        return sum / n
    }

    private fun centroidY(s: SimState, body: Int): Float {
        var sum = 0f
        var n = 0
        for (i in 0 until s.particleCount) {
            if (s.particleBody[i] == body) { sum += s.positionY[i]; n++ }
        }
        return sum / n
    }

    /** Bounding-box width of a body, for detecting a quarter turn. */
    private fun bodyWidth(s: SimState, body: Int): Float {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (i in 0 until s.particleCount) {
            if (s.particleBody[i] != body) continue
            val x = s.positionX[i]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        return maxX - minX
    }

    /** Whether any particle of [body] reports contact this tick. */
    private fun bodyInContact(s: SimState, body: Int): Boolean {
        for (i in 0 until s.particleCount) {
            if (s.particleBody[i] == body && s.particleContact[i] > 0f) return true
        }
        return false
    }
}
