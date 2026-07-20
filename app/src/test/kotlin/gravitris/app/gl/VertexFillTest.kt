package gravitris.app.gl

import gravitris.app.Tunables
import gravitris.app.toy.SquishToy
import gravitris.game.InputFrame
import gravitris.game.SimConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The render interpolation, against the real solver.
 *
 * ADR 0007's warning for this milestone is that a wrong alpha produces judder
 * indistinguishable from a physics bug — and the solver's stability is measured
 * in two independent implementations with 2x margin, so a "the stack jitters"
 * report would send someone into the solver for nothing. These tests exist so
 * the render path can be ruled out first, by evidence rather than by argument.
 */
class VertexFillTest {

    private fun movingToy(): SquishToy {
        val toy = SquishToy(
            SimConfig(lattice = Tunables.TOY_LATTICE, wellWidth = 10f, wellHeight = 20f),
            maxBodies = Tunables.TOY_MAX_BODIES,
        )
        // Thirty ticks in, the piece is falling — previous and current
        // positions genuinely differ, which is what makes alpha observable.
        repeat(30) { toy.step(InputFrame()) }
        return toy
    }

    private fun scratch(toy: SquishToy) =
        FloatArray(toy.state.particleCount * BodyMesh.FLOATS_PER_VERTEX)

    @Test
    fun `alpha zero draws exactly the previous tick`() {
        val toy = movingToy()
        val out = scratch(toy)

        VertexFill.fill(toy.state, 0f, out)

        for (i in 0 until toy.state.particleCount) {
            assertEquals(toy.state.prevPositionX[i], out[i * 3], 1e-6f)
            assertEquals(toy.state.prevPositionY[i], out[i * 3 + 1], 1e-6f)
        }
    }

    @Test
    fun `alpha one draws exactly the current tick`() {
        val toy = movingToy()
        val out = scratch(toy)

        VertexFill.fill(toy.state, 1f, out)

        for (i in 0 until toy.state.particleCount) {
            assertEquals(toy.state.positionX[i], out[i * 3], 1e-6f)
            assertEquals(toy.state.positionY[i], out[i * 3 + 1], 1e-6f)
        }
    }

    /**
     * The direction check. A reversed lerp passes both endpoint tests above —
     * it only differs in between — so this is the one that actually catches it.
     */
    @Test
    fun `alpha runs from previous towards current, not the other way`() {
        val toy = movingToy()
        val out = scratch(toy)

        VertexFill.fill(toy.state, 0.25f, out)

        var moved = 0
        for (i in 0 until toy.state.particleCount) {
            val previous = toy.state.prevPositionY[i]
            val current = toy.state.positionY[i]
            if (current == previous) continue
            moved++

            val expected = previous + (current - previous) * 0.25f
            assertEquals(expected, out[i * 3 + 1], 1e-6f)

            // Independently of the arithmetic: a quarter of the way along must
            // be nearer where the particle was than where it now is.
            val toPrevious = kotlin.math.abs(out[i * 3 + 1] - previous)
            val toCurrent = kotlin.math.abs(out[i * 3 + 1] - current)
            assertTrue(
                toPrevious < toCurrent,
                "particle $i at alpha 0.25 sits nearer the current position than the " +
                    "previous one — the interpolation is running backwards",
            )
        }
        assertTrue(moved > 0, "no particle moved between ticks; the test proves nothing")
    }

    @Test
    fun `compression is passed through unchanged rather than interpolated`() {
        val toy = movingToy()
        val out = scratch(toy)

        VertexFill.fill(toy.state, 0.5f, out)

        for (i in 0 until toy.state.particleCount) {
            assertEquals(toy.state.particleCompression[i], out[i * 3 + 2], 0f)
        }
    }

    /**
     * The core allocates every array to capacity once, so `positionX.size` is
     * far larger than `particleCount`. Filling to `size` would draw the unused
     * tail as a degenerate blob at the world origin — a black-screen-class bug
     * that no assertion elsewhere would catch.
     */
    @Test
    fun `only live particles are written`() {
        val toy = movingToy()
        val state = toy.state
        assertTrue(
            state.positionX.size > state.particleCount,
            "expected spare capacity, otherwise this test proves nothing",
        )

        val out = FloatArray(state.positionX.size * BodyMesh.FLOATS_PER_VERTEX) { Float.NaN }
        val written = VertexFill.fill(state, 1f, out)

        assertEquals(state.particleCount * BodyMesh.FLOATS_PER_VERTEX, written)
        assertTrue(out[written].isNaN(), "the fill wrote past the live particle count")
    }

    @Test
    fun `an empty well writes nothing`() {
        val toy = SquishToy(
            SimConfig(lattice = Tunables.TOY_LATTICE, wellWidth = 10f, wellHeight = 20f),
            maxBodies = Tunables.TOY_MAX_BODIES,
        )
        assertEquals(0, VertexFill.fill(toy.state, 0.5f, FloatArray(16)))
    }
}
