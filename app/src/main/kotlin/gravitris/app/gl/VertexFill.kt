package gravitris.app.gl

import gravitris.game.SimState

/**
 * Builds one frame's interleaved vertex data from a [SimState].
 *
 * Split out of [BodyMesh] purely so it can be tested: everything else in that
 * class touches GL and therefore cannot run in this container, while this is
 * the part where being wrong is both easy and expensive.
 *
 * ## Why this specific function is worth testing on its own
 *
 * The architect's warning for this milestone is that **a wrong interpolation
 * alpha produces judder that looks exactly like a physics bug** — and the
 * solver's stability is now measured in two independent implementations with
 * 2x margin, so a report of "the stack jitters" would send someone into the
 * solver for nothing. The lerp below is where that mistake would live. Getting
 * it backwards (`current -> previous`), or applying alpha to the wrong array
 * pair, still renders, still animates, and still looks like the physics
 * misbehaving.
 *
 * The same class of mistake, in the other direction, is what
 * [extrudeBoundary] fixes: this file used to write particle centres straight
 * into the vertex buffer, which drew every body one [SimState.particleRadius]
 * small on every side and put `2 * particleRadius` of background between two
 * bodies the solver was holding in measured contact. See ADR 0011.
 */
object VertexFill {

    /**
     * Corner displacement is `radius * SQRT_2` along the diagonal so that the
     * two offset edges meet at a square corner: at rest a corner particle sits
     * at `(0, 0)` with the material reaching to `x = -radius` and `y = -radius`,
     * and those two lines cross at `(-radius, -radius)`, a diagonal distance of
     * `radius * sqrt(2)`.
     */
    private const val SQRT_2 = 1.41421356f

    /**
     * Below this, the vector from a boundary particle to its inward reference
     * cannot be normalised into a meaningful direction. Two coincident
     * particles mean the solver has collapsed a cell, and dividing by that
     * length would put a NaN in the vertex buffer — which is not one bad
     * vertex on screen but, on most drivers, one missing body.
     */
    private const val MIN_DIRECTION_LENGTH = 1e-7f

    /**
     * The ADR 0006 render interpolation, fused into the buffer fill.
     *
     * The ADR notes this is where interpolation becomes almost free: the vertex
     * buffer is rebuilt every frame anyway, so it costs one lerp per component
     * rather than a separate pass over the particle arrays.
     *
     * **Compression is deliberately not interpolated.** It is a ratio derived
     * from positions, so lerping it alongside the positions it came from would
     * describe a deformation the geometry on screen does not have. The error is
     * invisible at 60Hz and the honest value is also the cheaper one.
     *
     * **Contact is not interpolated either, for the same reason and one more.**
     * It is the solver's occlusion accumulator, written only on the final
     * substep so it already describes the configuration being rendered, and it
     * changes discontinuously by nature — a body either has a neighbour or does
     * not. Lerping it would smear the seam across the frame in which contact
     * begins, which is precisely the frame the seam exists to mark.
     *
     * @param alpha `accumulator / TICK`, in `[0, 1]`. 0 draws the previous
     *   tick's state, 1 draws the current one.
     * @param out interleaved `[x, y, compression, contact]` per particle. Must
     *   hold at least `state.particleCount * FLOATS_PER_VERTEX` floats.
     * @return the number of floats written.
     */
    fun fill(state: SimState, alpha: Float, out: FloatArray): Int {
        val particles = state.particleCount

        // Loop bounds come from particleCount, never from array.size: the core
        // allocates every array to capacity once, so the tail beyond
        // particleCount holds stale or zeroed particles that would be drawn as
        // a degenerate blob at the origin.
        val currentX = state.positionX
        val currentY = state.positionY
        val previousX = state.prevPositionX
        val previousY = state.prevPositionY
        val compression = state.particleCompression
        val contact = state.particleContact

        var cursor = 0
        for (i in 0 until particles) {
            out[cursor++] = previousX[i] + (currentX[i] - previousX[i]) * alpha
            out[cursor++] = previousY[i] + (currentY[i] - previousY[i]) * alpha
            out[cursor++] = compression[i]
            out[cursor++] = contact[i]
        }

        extrudeBoundary(out, particles, state.bodyLattice, state.particleRadius)
        return cursor
    }

    /**
     * Fill the per-particle attributes that never change: body UV and the
     * free-surface flag.
     *
     * These are the two members of ADR 0007's varying list that are **static
     * per particle** — `:core-sim` writes them once when a body is created and
     * never touches them again, because a particle's position within its own
     * lattice is what identifies it. So they are uploaded on the same schedule
     * as the archetype (only when the set of bodies changes) rather than
     * rewritten every frame, and Stage 3B's two new material inputs therefore
     * cost **zero** per-frame bandwidth and zero per-frame CPU.
     *
     * They are copied out of [SimState] rather than re-derived from the lattice
     * index here, even though the shell knows the lattice and the arithmetic is
     * two divisions. Re-deriving would put a second definition of "where is
     * this particle in its body" in the shell, and the first thing that breaks
     * when a quality tier changes is the copy nobody remembered was a copy.
     *
     * @param out interleaved `[u, v, edge, corner]` per particle.
     * @return the number of floats written.
     */
    fun fillStatics(state: SimState, out: FloatArray): Int {
        val u = state.particleU
        val v = state.particleV
        val edge = state.particleEdge
        // §16 rounded corners: 1 at a true outer-silhouette corner of the whole
        // piece, 0 everywhere else — static per particle, copied straight from
        // the core (backend handoff 0036) rather than re-derived, for the same
        // reason as UV and edge above: a second definition of "which particle is
        // a corner" is the copy nobody remembers is a copy.
        val corner = state.particleCorner

        var cursor = 0
        for (i in 0 until state.particleCount) {
            out[cursor++] = u[i]
            out[cursor++] = v[i]
            out[cursor++] = edge[i]
            out[cursor++] = corner[i]
        }
        return cursor
    }

    /**
     * Push each body's outer ring of vertices out to the material's real
     * surface, in place.
     *
     * ## Why this is needed at all
     *
     * Every position in the `:core-sim` contract is a particle *centre*, and
     * the solver treats material as reaching [SimState.particleRadius] past it
     * — a resting body's lowest centres sit exactly one radius above the floor,
     * and two bodies in contact hold their centres exactly two radii apart.
     * A mesh built from centres alone is therefore inset by a radius on every
     * side, and two bodies that are genuinely touching draw with `2 * radius`
     * of background between them. At lattice 5 that is 0.45 world units, a
     * quarter of a piece's width, and it is what the client saw as "so much
     * margin around the blocks".
     *
     * ## Why outward from the inward neighbour, and not from the centroid
     *
     * The obvious implementation — scale each body up about its centroid —
     * is wrong for exactly the bodies this product is about. A uniform scale
     * adds a *proportion*, not a distance, so a squashed body gets too little
     * extrusion where it is thin and too much where it is wide, and the drawn
     * footprint stops matching the real one precisely when the deformation is
     * most visible. The extrusion has to be a fixed distance along the local
     * outward direction.
     *
     * That direction is taken as the vector from the particle's inward
     * reference neighbour to the particle itself. It is measured on the
     * *deformed* lattice, so it follows the material as it squashes and
     * shears rather than assuming the body is still a square.
     *
     * The reference neighbour is one lattice step inward on each axis the
     * particle is on an edge of: one step for an edge particle, one diagonal
     * step for a corner. Every such reference is **strictly interior** for
     * any lattice of 3 or more, and interior particles are never displaced —
     * which is what makes this safe to do in a single in-place pass with no
     * scratch buffer and no per-frame allocation. Ordering cannot matter if
     * nothing read is ever written.
     *
     * ## What it does not do
     *
     * The true surface of a union of disks is *rounded* at a body's corners.
     * Reproducing that needs vertices this mesh does not have — the topology
     * is fixed per quality tier and shared by every body (ADR 0007 §2), and
     * adding a rounding fan would cost vertices, bandwidth and a second index
     * layout for no gain the client can see at this size. Squaring the corner
     * instead overshoots the true arc by `radius * (sqrt(2) - 1)`, about
     * 0.09 world units at lattice 5, at four points per body. Blocks with
     * sharp corners is also what the piece is supposed to look like.
     */
    private fun extrudeBoundary(out: FloatArray, particles: Int, lattice: Int, radius: Float) {
        // A lattice of 2 is all corners and has no interior to measure an
        // outward direction against. SimConfig only ever produces 4, 5 or 6
        // (ADR 0009), so this is a guard against a future tier, not a case
        // that occurs today — and drawing the inset silhouette is a better
        // failure than drawing a NaN one.
        if (lattice < 3 || radius <= 0f) return

        val particlesPerBody = lattice * lattice
        val last = lattice - 1
        val bodies = particles / particlesPerBody

        for (body in 0 until bodies) {
            val base = body * particlesPerBody
            for (row in 0..last) {
                // Which way is inward on each axis. Zero means this particle is
                // not on that pair of edges; a corner gets both, and the two
                // together make the diagonal step.
                val rowStep = if (row == 0) 1 else if (row == last) -1 else 0
                for (column in 0..last) {
                    val columnStep = if (column == 0) 1 else if (column == last) -1 else 0
                    if (rowStep == 0 && columnStep == 0) continue // interior

                    val vertex = (base + row * lattice + column) * BodyMesh.FLOATS_PER_VERTEX
                    val reference = (base + (row + rowStep) * lattice + (column + columnStep)) *
                        BodyMesh.FLOATS_PER_VERTEX

                    val dx = out[vertex] - out[reference]
                    val dy = out[vertex + 1] - out[reference + 1]
                    val length = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (length < MIN_DIRECTION_LENGTH) continue

                    // One radius per axis the particle is on an edge of, so the
                    // two offset edges of a corner meet squarely.
                    val reach = if (rowStep != 0 && columnStep != 0) radius * SQRT_2 else radius
                    val scale = reach / length
                    out[vertex] += dx * scale
                    out[vertex + 1] += dy * scale
                }
            }
        }
    }
}
