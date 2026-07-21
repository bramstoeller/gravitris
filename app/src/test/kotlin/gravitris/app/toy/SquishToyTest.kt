package gravitris.app.toy

import gravitris.app.Palette
import gravitris.app.Tunables
import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The integration seam, exercised against the real solver.
 *
 * `:app`'s unit tests run on the plain JVM and `:core-sim` is framework-free
 * Kotlin, so the actual `Simulation` can be driven here — no device, no
 * emulator, no GL. That matters more than usual: everything between the shell
 * and the core is code that has never executed, and the specific risk at a
 * module seam is not a crash but a *silent* misreading of the other side's
 * contract. These tests are written to catch the quiet kind.
 */
class SquishToyTest {

    private fun config(
        wellWidth: Float = 10f,
        wellHeight: Float = 20f,
    ) = SimConfig(
        lattice = Tunables.TOY_LATTICE,
        wellWidth = wellWidth,
        wellHeight = wellHeight,
    )

    private fun toy(config: SimConfig = config()) =
        SquishToy(config, maxBodies = Tunables.TOY_MAX_BODIES)

    /** Steps [ticks] times with no player input. */
    private fun SquishToy.run(ticks: Int, input: InputFrame = InputFrame()) {
        repeat(ticks) { step(input) }
    }

    @Test
    fun `the first tick puts a piece in the well`() {
        val toy = toy()
        assertEquals(0, toy.state.bodyCount, "nothing exists before the first step")

        toy.step(InputFrame())

        assertEquals(1, toy.state.bodyCount)
        assertEquals(0, toy.state.activePieceBody, "the new piece is under player control")
        assertEquals(
            Tunables.TOY_LATTICE * Tunables.TOY_LATTICE,
            toy.state.particleCount,
        )
    }

    @Test
    fun `a piece falls, lands and is handed over for another`() {
        val toy = toy()
        toy.run(600)

        assertTrue(
            toy.state.bodyCount >= 2,
            "after ten seconds the first piece should have settled and a second arrived; " +
                "bodyCount was ${toy.state.bodyCount}",
        )
    }

    /**
     * The failure this guards is the one that would have shipped silently: the
     * piece lands, never reads as settled, and the toy hands the player nothing
     * more to drop. The screen still moves, the app still runs, and the client
     * concludes the demo is broken without being able to say how.
     */
    @Test
    fun `a landed piece actually comes to rest under the quiet threshold`() {
        val toy = toy()

        var releasedAt = -1
        for (tick in 0 until 900) {
            toy.step(InputFrame())
            if (toy.state.activePieceBody < 0) {
                releasedAt = tick
                break
            }
        }

        assertTrue(
            releasedAt >= 0,
            "a piece dropped into an empty well never came to rest under " +
                "${SquishToy.QUIET_SPEED_WORLD_PER_S} units/s within fifteen seconds",
        )
        // Pins the cadence the client will actually feel. Measured at ~210
        // ticks; the bound is loose enough to survive solver noise and tight
        // enough that a regression into "the toy feels stuck" fails here.
        assertTrue(
            releasedAt < 360,
            "one drop took $releasedAt ticks (${releasedAt / 60f}s) before the next piece; " +
                "that is long enough to read as the toy having stopped responding",
        )
    }

    @Test
    fun `pieces cycle through the palette and never index past it`() {
        val toy = toy()
        toy.run(1200)

        assertTrue(toy.state.bodyCount >= 2, "need several pieces to check the cycle")
        for (b in 0 until toy.state.bodyCount) {
            val archetype = toy.state.bodyArchetype[b]
            assertTrue(
                archetype in 0 until Palette.PIECE_COUNT,
                "body $b has archetype $archetype, outside the ${Palette.PIECE_COUNT}-hue palette",
            )
        }
    }

    /**
     * The core offers more archetypes than the palette has hues. Cycling on the
     * core's count would draw a piece in the well-surface grey; cycling past the
     * palette's size would index a GLSL array out of bounds, which is undefined
     * behaviour rather than a wrong colour.
     */
    @Test
    fun `the palette is not larger than the archetypes the core offers`() {
        assertTrue(
            Palette.PIECE_COUNT <= Simulation.ARCHETYPE_COUNT,
            "the palette claims ${Palette.PIECE_COUNT} pieces but the core offers " +
                "${Simulation.ARCHETYPE_COUNT} archetypes",
        )
        assertTrue(Palette.SURFACE_INDEX >= Palette.PIECE_COUNT)
    }

    /**
     * Drag and rotate are applied kinematically by the core — position and both
     * previous-position buffers move together, so the move injects no velocity.
     * A piece resting on the floor therefore reads as *perfectly still* while
     * being dragged, and a quietness test that did not special-case input would
     * take the piece out of the player's hand mid-gesture.
     */
    @Test
    fun `dragging a settled piece does not take it away`() {
        val toy = toy()
        val idle = InputFrame()

        // Drop a piece and let it settle, but keep hold of it by dragging.
        val dragging = InputFrame()
        var ticks = 0
        while (toy.state.bodyCount == 0 || toy.state.activePieceBody >= 0) {
            dragging.dragX = if (ticks % 2 == 0) 0.01f else -0.01f
            toy.step(dragging)
            ticks++
            if (ticks > 600) break
        }

        assertTrue(
            ticks > 600,
            "the piece was released after $ticks ticks despite continuous drag input",
        )

        // Stop dragging and it should now be released promptly.
        var afterRelease = 0
        while (toy.state.activePieceBody >= 0 && afterRelease < 300) {
            toy.step(idle)
            afterRelease++
        }
        assertTrue(
            toy.state.activePieceBody < 0,
            "once the drag stopped the piece should settle and be released",
        )
    }

    /**
     * `addPiece` throws on an overlapping placement, and the backend engineer is
     * explicit that this must not be caught and ignored. So the toy has to know
     * the well is full *before* it asks. If this test fails, the app crashes on
     * the client's phone after a couple of minutes of play.
     */
    @Test
    fun `filling the well resets it instead of throwing`() {
        // A short well fills quickly, which is the point: this reaches the
        // reset path in seconds rather than minutes.
        val toy = toy(config(wellHeight = 12f))

        var resets = 0
        repeat(30_000) {
            toy.step(InputFrame())
            resets = toy.resets
        }

        assertTrue(resets >= 1, "a 12-unit well should have filled and reset at least once")
        assertTrue(
            toy.state.bodyCount <= Tunables.TOY_MAX_BODIES,
            "body count ${toy.state.bodyCount} exceeded the rail the buffers are sized for",
        )
    }

    @Test
    fun `a reset empties the well`() {
        val toy = toy()
        toy.run(600)
        assertTrue(toy.state.bodyCount > 0)

        toy.reset()

        assertEquals(0, toy.state.bodyCount)
        assertEquals(0, toy.state.particleCount)
        assertEquals(-1, toy.state.activePieceBody)
    }

    /**
     * The renderer sizes its vertex and index buffers from `TOY_MAX_BODIES`, so
     * the core's derived capacity has to cover it for every well the layout can
     * produce. The clamps in `WellLayout` bound that to 12..30 units tall.
     */
    @Test
    fun `every well the layout can produce has capacity for the shell's body rail`() {
        for (height in intArrayOf(12, 16, 20, 24, 30)) {
            val toy = toy(config(wellHeight = height.toFloat()))
            val required = Tunables.TOY_MAX_BODIES * Tunables.TOY_LATTICE * Tunables.TOY_LATTICE
            assertTrue(
                toy.state.particleCapacity >= required,
                "a ${height}-unit well has capacity ${toy.state.particleCapacity}, below $required",
            )
        }
    }

    /**
     * The toy stages pieces itself and never calls [Simulation.start], so no
     * game *rule* runs: nothing scores, levels, locks, clears or ends.
     *
     * Coverage bands are the deliberate exception, and the reason is worth
     * stating because this assertion used to demand `bandFill == 0`. Stage 3A
     * moved band occupancy onto the always-on per-tick path — it is a
     * *measurement* of the world, like `kineticEnergy`, not a rule — precisely
     * so the gel shader reads live fill from this toy without the mechanic
     * running (backend handoff 0019). So `bandFill` is expected to be non-zero
     * once material settles; what proves no *rule* fired is that
     * `bandClearProgress` never leaves -1 (no clear) and score/level/phase/
     * landing are all inert.
     */
    @Test
    fun `no game rules have appeared`() {
        val toy = toy()
        toy.run(900)

        assertEquals(0, toy.state.score)
        assertEquals(1, toy.state.level)
        assertEquals(gravitris.game.Phase.Playing, toy.state.phase)
        assertFalse(toy.state.landing.valid)
        for (b in 0 until toy.state.bandClearProgress.size) {
            assertEquals(-1f, toy.state.bandClearProgress[b], "no clear may run in the toy")
        }
    }

    /** Pieces must land inside the walls, or the well is drawn around nothing. */
    @Test
    fun `material stays inside the well`() {
        val config = config()
        val toy = toy(config)
        toy.run(1800)

        for (i in 0 until toy.state.particleCount) {
            val x = toy.state.positionX[i]
            val y = toy.state.positionY[i]
            assertTrue(x >= -0.5f && x <= config.wellWidth + 0.5f, "particle $i escaped sideways at x=$x")
            assertTrue(y >= -0.5f, "particle $i fell through the floor at y=$y")
        }
    }

    @Test
    fun `a hard drop lands harder than a gentle one`() {
        fun peakImpact(hardDrop: Boolean): Float {
            val toy = toy()
            val input = InputFrame()
            toy.step(input)
            if (hardDrop) {
                toy.slamActivePiece(30f)
                toy.step(input)
            }
            var peak = 0f
            repeat(300) {
                toy.step(input)
                for (k in 0 until toy.state.impacts.count) {
                    if (toy.state.impacts.strength[k] > peak) peak = toy.state.impacts.strength[k]
                }
            }
            return peak
        }

        val gentle = peakImpact(hardDrop = false)
        val hard = peakImpact(hardDrop = true)

        assertTrue(gentle > 0f, "a piece falling under gravity should register an impact")
        assertTrue(
            hard > gentle,
            "a slammed piece ($hard) should hit harder than a free fall ($gentle); if these are " +
                "equal, slamActivePiece is not reaching the solver and every impact will " +
                "feel the same in the hand",
        )
        assertNotEquals(1f, gentle, "a gentle landing should not already saturate the haptic curve")
    }
}
