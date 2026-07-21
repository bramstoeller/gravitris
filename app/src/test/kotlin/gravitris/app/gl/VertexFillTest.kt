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

    /**
     * Offset of particle [i]'s vertex in the interleaved buffer.
     *
     * These tests used to index with a literal `i * 3` and broke the moment
     * Stage 3B added the contact channel — they failed loudly rather than
     * silently reading the wrong field, which was luck rather than design.
     * Going through the stride constant means the next channel added is a
     * change in one place.
     */
    private fun vertex(i: Int) = i * BodyMesh.FLOATS_PER_VERTEX

    /**
     * Particle indices whose vertex is written by the lerp and nothing else.
     *
     * `fill` also extrudes each body's boundary ring out to the material's true
     * surface, so a boundary vertex deliberately does *not* equal the position
     * it was interpolated from. The interpolation is still exactly observable
     * on the interior, and that is where these tests read it — the extrusion
     * has its own tests in [RenderFootprintTest].
     *
     * Checking the interior is not a weaker test of the lerp: the extrusion is
     * applied uniformly to every body from the already-interpolated buffer, so
     * an alpha mistake would show up on the interior first and identically.
     */
    private fun interiorParticles(toy: SquishToy): List<Int> {
        val lattice = toy.state.bodyLattice
        val perBody = lattice * lattice
        return (0 until toy.state.particleCount).filter { index ->
            val row = (index % perBody) / lattice
            val column = (index % perBody) % lattice
            row in 1..lattice - 2 && column in 1..lattice - 2
        }.also {
            assertTrue(it.isNotEmpty(), "no interior particles; the test proves nothing")
        }
    }

    @Test
    fun `alpha zero draws exactly the previous tick`() {
        val toy = movingToy()
        val out = scratch(toy)

        VertexFill.fill(toy.state, 0f, out)

        for (i in interiorParticles(toy)) {
            assertEquals(toy.state.prevPositionX[i], out[vertex(i)], 1e-6f)
            assertEquals(toy.state.prevPositionY[i], out[vertex(i) + 1], 1e-6f)
        }
    }

    @Test
    fun `alpha one draws exactly the current tick`() {
        val toy = movingToy()
        val out = scratch(toy)

        VertexFill.fill(toy.state, 1f, out)

        for (i in interiorParticles(toy)) {
            assertEquals(toy.state.positionX[i], out[vertex(i)], 1e-6f)
            assertEquals(toy.state.positionY[i], out[vertex(i) + 1], 1e-6f)
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
        for (i in interiorParticles(toy)) {
            val previous = toy.state.prevPositionY[i]
            val current = toy.state.positionY[i]
            if (current == previous) continue
            moved++

            val expected = previous + (current - previous) * 0.25f
            assertEquals(expected, out[vertex(i) + 1], 1e-6f)

            // Independently of the arithmetic: a quarter of the way along must
            // be nearer where the particle was than where it now is.
            val toPrevious = kotlin.math.abs(out[vertex(i) + 1] - previous)
            val toCurrent = kotlin.math.abs(out[vertex(i) + 1] - current)
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
            assertEquals(toy.state.particleCompression[i], out[vertex(i) + 2], 0f)
        }
    }

    /**
     * Contact is the seam term, and `piece-identity.md` ranks the seam as the
     * **primary** small-screen cue for where one piece ends and the next
     * begins. If it arrived at the shader interpolated, or lagged by a frame,
     * or landed in the wrong channel, the result would not be an obvious
     * failure — it would be pieces that are slightly harder to tell apart when
     * squashed together, which is the exact thing nobody notices going wrong.
     */
    @Test
    fun `contact is passed through unchanged rather than interpolated`() {
        val toy = movingToy()
        val out = scratch(toy)

        VertexFill.fill(toy.state, 0.5f, out)

        for (i in 0 until toy.state.particleCount) {
            assertEquals(toy.state.particleContact[i], out[vertex(i) + 3], 0f)
        }
    }

    /**
     * A settled pile must actually produce contact, or the seam term is being
     * shipped dark and the test above passes on a buffer of zeros.
     */
    @Test
    fun `a settled pile reports contact for the shader to draw a seam from`() {
        val toy = SquishToy(
            SimConfig(lattice = Tunables.TOY_LATTICE, wellWidth = 10f, wellHeight = 20f),
            maxBodies = Tunables.TOY_MAX_BODIES,
        )
        repeat(600) { toy.step(InputFrame()) }
        val out = FloatArray(toy.state.particleCount * BodyMesh.FLOATS_PER_VERTEX)

        VertexFill.fill(toy.state, 1f, out)

        val inContact = (0 until toy.state.particleCount).count { out[vertex(it) + 3] > 0f }
        assertTrue(
            inContact > 0,
            "no particle in a settled pile reports contact, so the seam that carries " +
                "small-screen piece boundaries would never be drawn",
        )
        for (i in 0 until toy.state.particleCount) {
            val contact = out[vertex(i) + 3]
            assertTrue(
                contact in 0f..1f,
                "contact $contact at particle $i is outside 0..1; the shader multiplies " +
                    "by it and would brighten rather than darken the seam",
            )
        }
    }

    /**
     * Body UV and the free-surface flag are uploaded on the slow path, so a
     * mistake here does not show up as a wrong colour for one frame — it shows
     * up as the whole material being wrong until the body count next changes.
     */
    @Test
    fun `static material attributes come straight from the core`() {
        val toy = movingToy()
        val state = toy.state
        val out = FloatArray(state.particleCount * BodyMesh.FLOATS_PER_MATERIAL_VERTEX)

        val written = VertexFill.fillStatics(state, out)

        assertEquals(state.particleCount * BodyMesh.FLOATS_PER_MATERIAL_VERTEX, written)
        for (i in 0 until state.particleCount) {
            val at = i * BodyMesh.FLOATS_PER_MATERIAL_VERTEX
            assertEquals(state.particleU[i], out[at], 0f)
            assertEquals(state.particleV[i], out[at + 1], 0f)
            assertEquals(state.particleEdge[i], out[at + 2], 0f)
            // §16 rounded corners: the corner flag rides the same static buffer,
            // straight from the core, as the fourth channel.
            assertEquals(state.particleCorner[i], out[at + 3], 0f)
        }
    }

    /**
     * The subsurface depth term reads `min(uv, 1 - uv)`, which is only a depth if
     * the UV genuinely reaches 0 and 1 across the piece — and the rim term is
     * only a rim if the free-surface flag marks the piece's real outline. Both
     * are assumptions about the core the shader cannot check.
     *
     * **Body-wide UV (§15 / backend handoff 0036).** As of the glossy-candy
     * redirect the UV is no longer per-cell (0..1 within each cell): it spans the
     * whole tetromino's footprint, aspect-preserving — 0..1 on the piece's longer
     * side and 0..k (k ≤ 1) on the shorter one — continuous across cell seams.
     * That is what makes the grain, the subsurface depth and the specular streak
     * read across the whole piece rather than restarting at every cell. So the
     * silhouette-to-core depth still reaches 0 (at the outline) and 1 (at the far
     * outline of the longer axis), but the axis that reaches 1 is whichever is
     * longer — this test checks the range is spanned across *either* axis rather
     * than assuming U specifically.
     *
     * A tetromino is four `L×L` cells (ADR 0015). The edge flag is 1 only on the
     * tetromino's *true outline* — a cell edge that faces a neighbour cell is a
     * welded seam, interior to the piece, and is flagged 0 so the rim light does
     * not draw a line down the middle of the material (backend handoff 0029). So
     * the free surface is a subset of the cell boundaries, never the cell
     * interior. The corner flag (§16) is in turn a subset of the edge flag: a
     * particle is never a corner (1) unless it is also on the free surface.
     */
    @Test
    fun `body uv spans the full range and the edge and corner flags mark the outline`() {
        val toy = movingToy()
        val state = toy.state
        val lattice = state.bodyLattice
        val perCell = lattice * lattice

        var sawZero = false
        var sawOne = false
        var sawEdge = false
        var sawCorner = false
        for (i in 0 until state.particleCount) {
            val row = (i % perCell) / lattice
            val column = (i % perCell) % lattice
            val onCellBoundary =
                row == 0 || row == lattice - 1 || column == 0 || column == lattice - 1
            val edge = state.particleEdge[i]
            val corner = state.particleCorner[i]

            assertTrue(edge == 0f || edge == 1f, "particle $i edge flag $edge is neither 0 nor 1")
            assertTrue(
                corner == 0f || corner == 1f,
                "particle $i corner flag $corner is neither 0 nor 1",
            )
            if (edge == 1f) {
                assertTrue(
                    onCellBoundary,
                    "particle $i is flagged free surface but sits inside a cell; the rim light " +
                        "would land in the middle of the material",
                )
                sawEdge = true
            }
            if (corner == 1f) {
                assertEquals(
                    1f, edge, 0f,
                    "particle $i is flagged a corner but not a free surface; the corner flag " +
                        "must be a subset of the edge flag, or §16 would round an interior point",
                )
                sawCorner = true
            }
            if (!onCellBoundary) {
                assertEquals(
                    0f, edge, 0f,
                    "particle $i is interior to its cell yet flagged as free surface",
                )
            }
            // The range check is axis-agnostic: body-wide UV reaches 0 and 1 on
            // the longer footprint side, whichever axis that is.
            if (state.particleU[i] == 0f || state.particleV[i] == 0f) sawZero = true
            if (state.particleU[i] == 1f || state.particleV[i] == 1f) sawOne = true
            assertTrue(state.particleU[i] in 0f..1f && state.particleV[i] in 0f..1f)
        }
        assertTrue(sawEdge, "no particle is flagged free surface, so the rim light never draws")
        assertTrue(
            sawCorner,
            "no particle is flagged a true corner, so §16 would never round anything",
        )
        assertTrue(sawZero && sawOne, "body uv does not span 0..1, so depth would never reach 0")
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
