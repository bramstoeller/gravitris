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
 * Where the silhouette is.
 *
 * The client's report on the Milestone 1 build was "there is so much margin
 * around the blocks". `ContactGapTest` in `:core-sim` establishes the other
 * half of this story — settled bodies are in contact to within float noise, at
 * every quality tier — so the margin was never a physics separation. It was
 * this module: every position in the contract is a particle *centre*, the
 * solver treats material as reaching `particleRadius` past it, and the vertex
 * buffer was built from centres alone. Every body was drawn one radius small on
 * every side and two touching bodies showed `2 * particleRadius` of background
 * between them.
 *
 * These tests pin the fix from the render side, in the same units the client
 * sees it. `ContactGapTest` is the guard against the tempting wrong fix
 * (shrinking the radius until the drawn shapes meet); this file is the guard
 * against the gap coming back.
 */
class RenderFootprintTest {

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

    /**
     * A body's outline as particle indices in cyclic order: along the bottom
     * row, up the right column, back along the top, down the left.
     */
    private fun boundaryRing(body: Int, lattice: Int): IntArray {
        val base = body * lattice * lattice
        val last = lattice - 1
        fun at(row: Int, column: Int) = base + row * lattice + column
        val ring = ArrayList<Int>(4 * last)
        for (c in 0 until last) ring.add(at(0, c))
        for (r in 0 until last) ring.add(at(r, last))
        for (c in last downTo 1) ring.add(at(last, c))
        for (r in last downTo 1) ring.add(at(r, 0))
        return ring.toIntArray()
    }

    private fun pointToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSq = dx * dx + dy * dy
        val t = if (lengthSq <= 0f) 0f else {
            ((px - ax) * dx + (py - ay) * dy) / lengthSq
        }.coerceIn(0f, 1f)
        val cx = ax + dx * t
        val cy = ay + dy * t
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    /**
     * The smallest distance between two bodies' drawn outlines.
     *
     * Measured vertex-to-*edge*, not vertex-to-vertex. Two lattices resting
     * against each other are generally staggered, so their nearest vertices can
     * be up to half a lattice spacing apart while the outlines themselves are
     * flush. Vertex-to-vertex would report that stagger as a gap and would
     * therefore fail a correct renderer — it is the distance between the drawn
     * *shapes* that the client sees as margin.
     */
    private fun outlineDistance(buffer: FloatArray, a: IntArray, b: IntArray): Float {
        var best = Float.MAX_VALUE
        fun sweep(points: IntArray, polygon: IntArray) {
            for (p in points) {
                for (k in polygon.indices) {
                    val s = polygon[k]
                    val e = polygon[(k + 1) % polygon.size]
                    best = minOf(
                        best,
                        pointToSegment(
                            x(buffer, p), y(buffer, p),
                            x(buffer, s), y(buffer, s),
                            x(buffer, e), y(buffer, e),
                        ),
                    )
                }
            }
        }
        sweep(a, b)
        sweep(b, a)
        return best
    }

    /** The closest two bodies' outlines in the pile, and how far apart they are drawn. */
    private fun closestOutlineDistance(state: SimState, buffer: FloatArray): Float {
        var best = Float.MAX_VALUE
        for (a in 0 until state.bodyCount) {
            for (b in a + 1 until state.bodyCount) {
                best = minOf(
                    best,
                    outlineDistance(buffer, boundaryRing(a, state.bodyLattice), boundaryRing(b, state.bodyLattice)),
                )
            }
        }
        return best
    }

    /**
     * **The client-facing test.** In a settled pile, the two closest bodies'
     * drawn outlines must meet.
     *
     * `ContactGapTest` establishes that the closest pair of particles from
     * different bodies sits exactly one diameter apart, which *is* those two
     * surfaces in contact. Drawing centres therefore put a full
     * `2 * particleRadius` of background between two touching bodies — 0.45
     * world units at lattice 5, a quarter of a piece's width. This measures
     * the same pile with and without the extrusion, so it states the size of
     * the bug as well as pinning the fix.
     */
    @Test
    fun `settled bodies are drawn in contact, not one diameter apart`() {
        for (lattice in 4..6) {
            val config = SimConfig(lattice = lattice)
            val state = dropStack(config, 4).state
            val diameter = 2f * state.particleRadius
            assertTrue(state.bodyCount >= 2, "lattice $lattice: need a pile to measure a gap in")

            // The buffer as it was built before this fix: particle centres,
            // verbatim. Measuring it here means the improvement is demonstrated
            // rather than asserted from a comment.
            val centres = FloatArray(state.particleCount * BodyMesh.FLOATS_PER_VERTEX)
            for (i in 0 until state.particleCount) {
                centres[i * BodyMesh.FLOATS_PER_VERTEX] = state.positionX[i]
                centres[i * BodyMesh.FLOATS_PER_VERTEX + 1] = state.positionY[i]
            }

            val before = closestOutlineDistance(state, centres)
            val after = closestOutlineDistance(state, drawn(state))

            // The premise: drawing centres really does open a large gap. It
            // approaches a full diameter but does not reach it — the outlines
            // are polylines through the centres, and where two bodies meet at
            // an angle their chords cut the corner slightly. A lower bound is
            // what the premise actually needs: the bug was real and large.
            assertTrue(
                before > 0.6f * diameter,
                "lattice $lattice: expected drawing centres to leave most of a diameter " +
                    "($diameter) between touching bodies, measured only $before — the " +
                    "premise of this test no longer holds",
            )

            // And the fix: both outlines move a radius towards each other, so
            // the gap closes. The bound is 5% of a diameter rather than zero
            // because each body's outward direction is its own surface normal
            // and two settled, deformed bodies need not present exactly
            // parallel faces.
            assertTrue(
                after < 0.05f * diameter,
                "lattice $lattice: bodies the solver holds in contact are drawn $after " +
                    "world units apart (a diameter is $diameter) — the render inset the " +
                    "client reported as margin around the blocks is back",
            )
        }
    }

    /**
     * The same claim against the floor, which is where it is most obvious on
     * screen: a resting body's lowest centres sit exactly one radius up, so
     * drawing centres left the pile hovering.
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
                0f,
                lowest,
                1e-2f,
                "lattice $lattice: the lowest drawn vertex must be on the floor (y=0), " +
                    "not ${state.particleRadius} above it",
            )
        }
    }

    /**
     * An undeformed piece must be drawn at the material extent the solver says it
     * is: the particle-centre bounding box grown by exactly one [particleRadius]
     * on every side (ADR 0011).
     *
     * A tetromino is four cells (ADR 0015), so its absolute size is shape-
     * dependent and no single constant like `pieceExtent` describes it. But the
     * render invariant is shape-agnostic and exact: the outermost cells present a
     * free edge on every side of the bounding box, so the drawn silhouette is the
     * centre extent plus a radius per side. Checking the *delta* rather than an
     * absolute size still catches an extrusion that is close but not the radius —
     * including a corner treatment that quietly rounds the silhouette in instead
     * of squaring it off — without hard-coding which shape archetype 0 is.
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
     * **The anti-centroid-scale test**, and the reason this is an extrusion
     * rather than the one-line fix.
     *
     * Scaling a body up about its centroid also closes the gap on an
     * undeformed piece, so the test above cannot tell the two apart. It breaks
     * on a *deformed* body — which is every body that matters in this product —
     * because a scale adds a proportion of the distance from the centroid, not
     * a fixed distance: too little where the body is thin, too much where it is
     * wide.
     *
     * So: squash a pile, then check that every edge-midpoint vertex moved by
     * the same fixed distance regardless of how far it sits from its body's
     * centre. Under a centroid scale these displacements would vary with the
     * body's shape.
     */
    @Test
    fun `extrusion adds a fixed distance, not a proportion of body size`() {
        val config = SimConfig(lattice = 5)
        val state = dropStack(config, 4).state
        val buffer = drawn(state)
        val lattice = state.bodyLattice
        val perBody = lattice * lattice
        val radius = state.particleRadius

        var checked = 0
        var mostDeformed = 0f
        for (i in 0 until state.particleCount) {
            val row = (i % perBody) / lattice
            val column = (i % perBody) % lattice
            val onRowEdge = row == 0 || row == lattice - 1
            val onColumnEdge = column == 0 || column == lattice - 1
            // Edge midpoints only: corners are extruded by radius * sqrt(2) so
            // the two offset edges meet squarely, which is a different number.
            if (onRowEdge == onColumnEdge) continue

            val moved = sqrt(
                (x(buffer, i) - state.positionX[i]).let { it * it } +
                    (y(buffer, i) - state.positionY[i]).let { it * it },
            )
            mostDeformed = maxOf(mostDeformed, abs(moved - radius))
            assertEquals(
                radius,
                moved,
                1e-4f,
                "particle $i sits on a body edge and must be extruded exactly one radius " +
                    "($radius) out; it moved $moved. A centroid scale would make this vary " +
                    "with the body's deformation",
            )
            checked++
        }
        assertTrue(checked > 0, "no edge particles examined; the test proves nothing")
        // Deformation is the whole point of the test — assert the pile actually
        // is squashed, or a passing result means nothing.
        assertTrue(
            state.particleCompression.take(state.particleCount).any { abs(it - 1f) > 0.005f },
            "the pile is undeformed, so this test did not exercise the case it exists for",
        )
    }

    /** Interior geometry is the solver's, untouched. Only the outer ring moves. */
    @Test
    fun `interior vertices are left exactly where the solver put them`() {
        val config = SimConfig(lattice = 5)
        val state = dropStack(config, 3).state
        val buffer = drawn(state)
        val lattice = state.bodyLattice
        val perBody = lattice * lattice

        var checked = 0
        for (i in 0 until state.particleCount) {
            val row = (i % perBody) / lattice
            val column = (i % perBody) % lattice
            if (row !in 1..lattice - 2 || column !in 1..lattice - 2) continue
            assertEquals(state.positionX[i], x(buffer, i), 1e-6f, "interior particle $i x")
            assertEquals(state.positionY[i], y(buffer, i), 1e-6f, "interior particle $i y")
            checked++
        }
        assertTrue(checked > 0, "no interior particles examined; the test proves nothing")
    }

    /**
     * Extruding outward moves material towards the walls. The solver clamps
     * particle centres to one radius inside the well, so the extruded surface
     * should land exactly *on* the wall — never through it. If that inset ever
     * changes on the core side, this is what catches the bodies bleeding into
     * the frame.
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
