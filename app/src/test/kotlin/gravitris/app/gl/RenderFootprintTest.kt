package gravitris.app.gl

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where the silhouette is, under the ADR 0018 free-edge extrusion.
 *
 * The client's report on the Milestone 1 build was "there is so much margin
 * around the blocks". `:core-sim`'s `ContactGapTest` establishes the other half
 * of this story — settled bodies are in contact to within float noise — so the
 * margin was never a physics separation. It was this module: every position in
 * the contract is a particle *centre*, the solver treats material as reaching
 * `particleRadius` past it, and the vertex buffer was built from centres alone.
 *
 * The fix is [VertexFill.extrudeBoundary], and its shape changed with ADR 0018.
 * It used to push out *every* cell's outer ring, which also collapsed the
 * columns where two cells of one tetromino abut — the internal "+" the client
 * then reported. It now extrudes only TRUE outer silhouette, gated on
 * [SimState.particleFreeEdges]: a seam-facing side has its bit clear and is left
 * at its centre for the per-archetype bridge mesh to fill.
 *
 * So the client-facing "two touching bodies meet, not a diameter apart" property
 * is now proved compositionally rather than by re-tracing polygon outlines in
 * `:app`: `ContactGapTest` proves two touching bodies' nearest centres are one
 * *diameter* apart, and the per-particle facts below prove every true silhouette
 * particle moves one *radius* along its outward normal — so two faces pressed
 * together each advance a radius and meet. What these tests own is that exact
 * per-particle characterisation of the extrusion, plus the two on-screen anchors
 * (the floor, and a freshly spawned piece's footprint) and the seam that must
 * NOT collapse.
 */
class RenderFootprintTest {

    // The frozen free-edge wire format (docs/contracts.md §3 / ADR 0018),
    // mirrored here exactly as VertexFill mirrors it — a seam-facing side is
    // clear, a true-silhouette side is set.
    private val freeLeft = 1
    private val freeRight = 2
    private val freeDown = 4
    private val freeUp = 8

    /** Drops pieces down the middle, exactly as `ContactGapTest` and the toy do. */
    private fun dropStack(config: SimConfig, count: Int): Simulation {
        val sim = Simulation(config)
        repeat(count) {
            sim.addPiece(
                archetype = it % Simulation.ARCHETYPE_COUNT,
                centerX = config.wellWidth * 0.5f,
                centerY = config.wellHeight - 1.5f,
            )
            sim.clearActivePiece()
            repeat(90) { _ -> sim.step(InputFrame()) }
        }
        // Long settle: the claim is about a pile at rest, not mid-collision.
        repeat(240) { sim.step(InputFrame()) }
        return sim
    }

    private fun drawn(state: SimState): FloatArray {
        val out = FloatArray(state.particleCount * BodyMesh.FLOATS_PER_VERTEX)
        VertexFill.fill(state, 1f, out)
        return out
    }

    private fun x(buffer: FloatArray, i: Int) = buffer[i * BodyMesh.FLOATS_PER_VERTEX]
    private fun y(buffer: FloatArray, i: Int) = buffer[i * BodyMesh.FLOATS_PER_VERTEX + 1]

    private fun freeAxes(mask: Int): Int {
        var n = 0
        if (mask and freeLeft != 0 || mask and freeRight != 0) n++
        if (mask and freeDown != 0 || mask and freeUp != 0) n++
        return n
    }

    /**
     * **Interior AND seam-facing particles are left exactly where the solver put
     * them.** This is the render side of the "+"-fix: a particle whose free-edge
     * mask is 0 is either deep interior or a seam-facing edge, and neither may
     * move — pushing the seam-facing ones toward each other is precisely what
     * stamped the collapsed "+" the client reported. Only true silhouette moves.
     */
    @Test
    fun `particles with no free edge are not displaced`() {
        for (lattice in 4..6) {
            val config = SimConfig(lattice = lattice)
            val state = dropStack(config, 4).state
            val buffer = drawn(state)

            var checked = 0
            for (i in 0 until state.particleCount) {
                if (state.particleFreeEdges[i] != 0) continue
                assertEquals(state.positionX[i], x(buffer, i), 1e-6f, "lattice $lattice particle $i x moved")
                assertEquals(state.positionY[i], y(buffer, i), 1e-6f, "lattice $lattice particle $i y moved")
                checked++
            }
            assertTrue(checked > 0, "lattice $lattice: no zero-mask particles examined")
        }
    }

    /**
     * **Every true silhouette particle is extruded a fixed distance**: one radius
     * where it is free on a single axis, `radius * sqrt(2)` at a true corner
     * (free on both axes, so the two offset edges meet squarely). A fixed
     * *distance*, never a proportion — the anti-centroid-scale property, which is
     * the whole reason this is an extrusion rather than a scale-up: a scale would
     * add too little where a deformed body is thin and too much where it is wide.
     */
    @Test
    fun `each free silhouette particle moves a fixed distance, a radius or a diagonal`() {
        val config = SimConfig(lattice = 5)
        val state = dropStack(config, 4).state
        val buffer = drawn(state)
        val radius = state.particleRadius
        val diagonal = radius * sqrt(2f)

        var checkedEdge = 0
        var checkedCorner = 0
        for (i in 0 until state.particleCount) {
            val n = freeAxes(state.particleFreeEdges[i])
            if (n == 0) continue
            val moved = sqrt(
                (x(buffer, i) - state.positionX[i]).let { it * it } +
                    (y(buffer, i) - state.positionY[i]).let { it * it },
            )
            // Coincident-cell guard: a collapsed cell has no outward direction and
            // is deliberately left in place (see extrudeBoundary), so it would
            // read as 0 movement. A settled pile has none, but exclude it in
            // principle rather than assume.
            if (moved < 1e-6f) continue
            val expected = if (n == 2) diagonal else radius
            assertEquals(
                expected, moved, 1e-4f,
                "lattice 5 particle $i (mask ${state.particleFreeEdges[i]}, $n free axes) moved " +
                    "$moved, expected $expected — a centroid scale would make this vary with " +
                    "the body's deformation",
            )
            if (n == 2) checkedCorner++ else checkedEdge++
        }
        assertTrue(checkedEdge > 0, "no single-axis silhouette particles examined")
        assertTrue(checkedCorner > 0, "no corner silhouette particles examined")
        // Deformation is the point — assert the pile is actually squashed, or a
        // passing fixed-distance result means nothing.
        assertTrue(
            state.particleCompression.take(state.particleCount).any { abs(it - 1f) > 0.005f },
            "the pile is undeformed, so this test did not exercise the case it exists for",
        )
    }

    /**
     * **An internal cell seam is not collapsed.** On an O piece, cell 0's right
     * column faces cell 1 (a seam), so those two facing columns carry no free-edge
     * bit and stay at their centres — one lattice spacing (a diameter) apart,
     * which the per-archetype bridge mesh fills with real triangles. If the old
     * per-cell extrusion came back it would pull them coincident and re-stamp the
     * "+". The seam-facing side is asserted against `particleFreeEdges` directly,
     * mirroring `:core-sim`'s `SeamlessTopologyTest`.
     */
    @Test
    fun `a cell seam is left a diameter wide for the bridge, not collapsed`() {
        val config = SimConfig(lattice = 5)
        val sim = Simulation(config)
        sim.addPiece(1, config.wellWidth * 0.5f, config.wellHeight - 1.5f) // O
        val state = sim.state
        val buffer = drawn(state)

        val l = state.bodyLattice
        val mid = l / 2
        val cell0Right = mid * l + (l - 1)
        val cell1Left = l * l + mid * l + 0

        assertEquals(
            0, state.particleFreeEdges[cell0Right] and freeRight,
            "the seam-facing side must not be flagged free, or extrusion would reopen the seam",
        )
        // Neither seam-facing particle is extruded, so both sit at their centres.
        assertEquals(state.positionX[cell0Right], x(buffer, cell0Right), 1e-6f, "cell0 seam column x moved")
        assertEquals(state.positionX[cell1Left], x(buffer, cell1Left), 1e-6f, "cell1 seam column x moved")

        val gap = abs(x(buffer, cell1Left) - x(buffer, cell0Right))
        val diameter = 2f * state.particleRadius
        assertEquals(
            diameter, gap, 1e-3f,
            "the seam columns are drawn $gap apart; a diameter ($diameter) is what the bridge " +
                "mesh fills — collapsing them to 0 is exactly the '+' this fix removes",
        )
    }

    /**
     * A resting body is drawn sitting on the floor, not hovering a radius above
     * it: the bottom silhouette carries FREE_DOWN and is extruded down by a
     * radius, onto the floor the solver holds the centres a radius above.
     */
    @Test
    fun `a resting body is drawn sitting on the floor, not hovering above it`() {
        for (lattice in 4..6) {
            val config = SimConfig(lattice = lattice)
            val state = dropStack(config, 4).state
            val buffer = drawn(state)

            var lowest = Float.MAX_VALUE
            for (i in 0 until state.particleCount) lowest = minOf(lowest, y(buffer, i))

            assertEquals(
                0f, lowest, 1e-2f,
                "lattice $lattice: the lowest drawn vertex must be on the floor (y=0), " +
                    "not ${state.particleRadius} above it",
            )
        }
    }

    /**
     * An undeformed piece is drawn at the material extent the solver says it is:
     * the particle-centre bounding box grown by exactly one [SimState.particleRadius]
     * on every side (ADR 0011). Checking the *delta* is shape-agnostic — the
     * outermost cells present a free silhouette on every side of the box — and it
     * still catches a corner treatment that quietly rounds the silhouette *in*
     * instead of squaring it off, without hard-coding which shape archetype 0 is.
     * Also pins that the extrusion is OUTWARD: an inward one would shrink the box.
     */
    @Test
    fun `a freshly spawned piece is drawn a radius past its particle centres`() {
        for (lattice in 4..6) {
            val config = SimConfig(lattice = lattice)
            val sim = Simulation(config)
            sim.addPiece(0, config.wellWidth * 0.5f, config.wellHeight - 1.5f)
            val state = sim.state
            val buffer = drawn(state)

            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            var minCx = Float.MAX_VALUE
            var maxCx = -Float.MAX_VALUE
            var minCy = Float.MAX_VALUE
            var maxCy = -Float.MAX_VALUE
            for (i in 0 until state.particleCount) {
                minX = minOf(minX, x(buffer, i)); maxX = maxOf(maxX, x(buffer, i))
                minY = minOf(minY, y(buffer, i)); maxY = maxOf(maxY, y(buffer, i))
                minCx = minOf(minCx, state.positionX[i]); maxCx = maxOf(maxCx, state.positionX[i])
                minCy = minOf(minCy, state.positionY[i]); maxCy = maxOf(maxCy, state.positionY[i])
            }

            val r = state.particleRadius
            assertEquals(maxCx - minCx + 2f * r, maxX - minX, 1e-4f, "lattice $lattice: drawn width")
            assertEquals(maxCy - minCy + 2f * r, maxY - minY, 1e-4f, "lattice $lattice: drawn height")
        }
    }

    /**
     * Extruding outward moves material towards the walls. The solver clamps
     * particle centres to one radius inside the well, so the extruded surface
     * should land exactly *on* the wall — never through it.
     */
    @Test
    fun `bodies are not drawn through the well walls or floor`() {
        for (lattice in 4..6) {
            val config = SimConfig(lattice = lattice)
            val state = dropStack(config, 4).state
            val buffer = drawn(state)

            for (i in 0 until state.particleCount) {
                assertTrue(
                    x(buffer, i) >= -1e-2f && x(buffer, i) <= config.wellWidth + 1e-2f,
                    "lattice $lattice: particle $i is drawn at x=${x(buffer, i)}, outside " +
                        "the well [0, ${config.wellWidth}]",
                )
                assertTrue(y(buffer, i) >= -1e-2f, "lattice $lattice: particle $i is drawn below the floor")
            }
        }
    }

    /**
     * A collapsed cell puts two particles in the same place, and normalising
     * that zero-length direction would write a NaN into the vertex buffer.
     * That is not one bad vertex on screen — on most drivers it is a missing
     * body, which from this container (no GPU, no emulator) would only surface
     * on the client's phone.
     */
    @Test
    fun `coincident particles do not put NaN in the vertex buffer`() {
        val config = SimConfig(lattice = 5)
        val sim = Simulation(config)
        sim.addPiece(0, config.wellWidth * 0.5f, config.wellHeight - 1.5f)
        val state = sim.state

        // Collapse the body onto a single point — the degenerate case the
        // solver should never produce, which is exactly why it must be handled.
        for (i in 0 until state.particleCount) {
            state.positionX[i] = 5f
            state.positionY[i] = 5f
            state.prevPositionX[i] = 5f
            state.prevPositionY[i] = 5f
        }

        val buffer = drawn(state)
        for (value in buffer) {
            assertTrue(value.isFinite(), "the vertex buffer contains a non-finite value: $value")
        }
    }
}
