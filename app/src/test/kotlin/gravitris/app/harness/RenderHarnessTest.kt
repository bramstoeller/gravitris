package gravitris.app.harness

import gravitris.app.Palette
import gravitris.app.WellLayout
import gravitris.app.sim.InputFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the Stage 1 render harness.
 *
 * These do **not** test physics — the harness has none, and says so. They test
 * the contract the renderer depends on: finite positions, indices that address
 * real bodies, archetypes inside the palette, previous positions that actually
 * lag, and impact energies inside the 0..1 range the haptic curve assumes.
 *
 * That contract is worth pinning even though the harness is temporary, because
 * a violation of it would show up as corrupt geometry or a wild vibration on
 * the client's phone — the one place we cannot debug from here. When the real
 * `Simulation` replaces the harness, these expectations move with it.
 */
class RenderHarnessTest {

    private lateinit var layout: WellLayout
    private lateinit var harness: RenderHarness
    private val input = InputFrame()

    @BeforeEach
    fun setUp() {
        layout = WellLayout().apply {
            update(1080, 2400, 0, 130, 0, 60, 2.75f)
        }
        harness = RenderHarness(layout)
    }

    private fun step(ticks: Int, configure: InputFrame.() -> Unit = {}) {
        repeat(ticks) {
            input.clear()
            input.configure()
            harness.step(input)
        }
    }

    @Test
    fun `starts with one body ready to fall`() {
        assertEquals(1, harness.bodyCount)
        assertEquals(25, harness.particleCount, "5x5 lattice, the default tier")
    }

    @Test
    fun `every particle position stays finite`() {
        step(600)
        for (i in 0 until harness.particleCount) {
            assertTrue(harness.positionX[i].isFinite(), "particle $i x is not finite")
            assertTrue(harness.positionY[i].isFinite(), "particle $i y is not finite")
        }
    }

    @Test
    fun `particle body indices always address a real body`() {
        step(600)
        for (i in 0 until harness.particleCount) {
            assertTrue(
                harness.particleBody[i] in 0 until harness.bodyCount,
                "particle $i points at body ${harness.particleBody[i]}",
            )
        }
    }

    @Test
    fun `archetypes stay inside the palette`() {
        step(900)
        for (body in 0 until harness.bodyCount) {
            assertTrue(
                harness.bodyArchetype[body] in 0 until Palette.PIECE_COUNT,
                "body $body has archetype ${harness.bodyArchetype[body]}",
            )
        }
    }

    @Test
    fun `the piece falls`() {
        val startY = harness.positionY[0]
        step(30)
        assertTrue(harness.positionY[0] < startY, "gravity should have moved it down")
    }

    @Test
    fun `previous positions lag current ones while moving`() {
        // The ADR 0006 interpolation lerp is meaningless if these are equal.
        step(30)
        var moved = false
        for (i in 0 until harness.particleCount) {
            if (harness.positionY[i] != harness.prevPositionY[i]) moved = true
        }
        assertTrue(moved, "prevPosition must trail position for interpolation to work")
    }

    // --- compression, the one shading input Stage 1 carries -----------------

    @Test
    fun `an undeformed piece reports compression at rest`() {
        // Before any impact the lattice is undeformed, so every cell should
        // measure its own rest area. A resting piece that already reported
        // compression would darken for no physical reason.
        for (i in 0 until harness.particleCount) {
            assertEquals(
                1f, harness.particleCompression[i], 0.02f,
                "particle $i is undeformed but reports ${harness.particleCompression[i]}",
            )
        }
    }

    @Test
    fun `compression departs from rest after an impact`() {
        // Land it hard, then look for material that is actually compressed.
        // If this stays at 1 the shading term is wired to a dead quantity and
        // the demo shows nothing, which is the failure the term exists to fix.
        input.clear()
        input.hardDrop = true
        input.hardDropVelocity = 40f
        harness.step(input)

        var landed = false
        var lowest = Float.MAX_VALUE
        repeat(400) {
            input.clear()
            harness.step(input)
            if (harness.impacts.count > 0) landed = true
            // Sampled every tick, not once at the end: the squash decays over
            // roughly a second, so checking after the fact would measure a
            // piece that had already settled.
            if (landed) {
                for (i in 0 until harness.particleCount) {
                    if (harness.particleCompression[i] < lowest) {
                        lowest = harness.particleCompression[i]
                    }
                }
            }
        }
        assertTrue(landed, "the piece should have landed")
        // A hard landing must produce compression the eye can actually see.
        // At the shader's gain of 1.4 this is roughly a 14% darkening or more,
        // which is the whole reason the term exists.
        assertTrue(
            lowest < 0.90f,
            "expected visibly compressed material after a hard landing, lowest was $lowest",
        )
    }

    @Test
    fun `compression stays physically plausible throughout a long run`() {
        // The shader maps this to darkness, so a wild value is a visible
        // artefact rather than a rounding error. Negative would mean an
        // inverted cell.
        repeat(4000) {
            input.clear()
            harness.step(input)
            for (i in 0 until harness.particleCount) {
                val compression = harness.particleCompression[i]
                assertTrue(
                    compression.isFinite() && compression in 0f..4f,
                    "particle $i reported compression $compression",
                )
            }
        }
    }

    @Test
    fun `compression is reported for every particle of every body`() {
        repeat(1200) {
            input.clear()
            harness.step(input)
        }
        assertTrue(harness.bodyCount > 1, "expected several bodies by now")
        for (i in 0 until harness.particleCount) {
            assertTrue(
                harness.particleCompression[i] > 0f,
                "particle $i has no compression value — stale or never written",
            )
        }
    }

    @Test
    fun `drag moves the piece horizontally`() {
        val before = harness.positionX[0]
        step(1) { dragX = 2f }
        assertTrue(harness.positionX[0] > before, "a positive drag moves the piece right")
    }

    @Test
    fun `drag cannot push the piece through a wall`() {
        step(120) { dragX = 5f }
        for (i in 0 until harness.particleCount) {
            assertTrue(
                harness.positionX[i] <= layout.widthWorld + 1f,
                "particle $i escaped the right wall at ${harness.positionX[i]}",
            )
        }

        step(240) { dragX = -5f }
        for (i in 0 until harness.particleCount) {
            assertTrue(
                harness.positionX[i] >= -1f,
                "particle $i escaped the left wall at ${harness.positionX[i]}",
            )
        }
    }

    @Test
    fun `rotate changes the piece's proportions`() {
        step(2)
        val widthBefore = extent(harness.positionX)
        step(1) { rotate = true }
        val widthAfter = extent(harness.positionX)

        assertNotEquals(
            widthBefore, widthAfter,
            "a tap must visibly do something, or the input path cannot be judged",
        )
    }

    @Test
    fun `impacts carry energies the haptic curve can use`() {
        // Run long enough for many landings, and assert every reported energy
        // is inside 0..1 — the range HapticCurve assumes and the range the
        // Vibrator amplitude scale is derived from.
        var seen = 0
        repeat(3000) {
            input.clear()
            harness.step(input)
            for (i in 0 until harness.impacts.count) {
                val energy = harness.impacts.strength[i]
                assertTrue(energy in 0f..1f, "impact energy $energy is out of range")
                seen++
            }
        }
        assertTrue(seen > 0, "a few thousand ticks should have produced landings")
    }

    @Test
    fun `a hard drop lands the piece sooner than gravity alone`() {
        val gravityOnly = RenderHarness(layout)
        var gravityTicks = 0
        while (gravityOnly.impacts.count == 0 && gravityTicks < 2000) {
            input.clear()
            gravityOnly.step(input)
            gravityTicks++
        }

        val dropped = RenderHarness(layout)
        input.clear()
        input.hardDrop = true
        input.hardDropVelocity = 40f
        dropped.step(input)
        var dropTicks = 1
        while (dropped.impacts.count == 0 && dropTicks < 2000) {
            input.clear()
            dropped.step(input)
            dropTicks++
        }

        assertTrue(
            dropTicks < gravityTicks,
            "hard drop took $dropTicks ticks, plain fall took $gravityTicks",
        )
    }

    @Test
    fun `the well resets rather than overflowing its body capacity`() {
        // A long session must not walk off the end of the fixed arrays.
        repeat(60_000) {
            input.clear()
            harness.step(input)
            assertTrue(harness.bodyCount in 1..60, "bodyCount was ${harness.bodyCount}")
            assertTrue(harness.particleCount <= 1500)
        }
    }

    private fun extent(positions: FloatArray): Float {
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (i in 0 until 25) {
            if (positions[i] < low) low = positions[i]
            if (positions[i] > high) high = positions[i]
        }
        return high - low
    }
}
