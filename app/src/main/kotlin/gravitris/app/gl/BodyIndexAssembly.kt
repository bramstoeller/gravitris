package gravitris.app.gl

import gravitris.game.SimState

/**
 * Assembles the whole stack's triangle index buffer from the core's
 * per-archetype, whole-piece render topology (ADR 0018).
 *
 * Split out of [BodyMesh] for exactly the reason [VertexFill] was: everything
 * else in that class touches GL and cannot run in this GPU-less container, while
 * this is where being wrong is both easy and expensive — a malformed index
 * buffer is not a wrong picture but a black screen or a missing body on the
 * client's phone, with no way to bisect it from here.
 *
 * ## Why this replaced `LatticeTopology`
 *
 * The old shell derived one cell's topology from the lattice size alone and
 * tiled it per cell, because the index buffer was built in `onSurfaceCreated`
 * before any `Simulation` existed to ask. That is what left every cell seam a
 * hard "+": each cell was a separate mesh with no triangles bridging the join
 * (handoff 0038). ADR 0018 moved the whole-piece topology — the cells' own
 * triangles PLUS the seam bridges — into [SimState.bodyTriangleIndices], and the
 * shell now consumes it straight rather than re-deriving anything. There is only
 * one definition of the topology again (the core's), so the old
 * `LatticeTopology`/`TopologyMatchesSolverTest` guard against "two definitions
 * drifting apart" is no longer guarding anything and was retired with it;
 * `:core-sim`'s `SeamlessTopologyTest` now pins the topology at its single
 * source, and [BodyIndexAssemblyTest] pins this concatenation.
 */
object BodyIndexAssembly {

    /**
     * The worst-case index count for [maxBodies] bodies of any archetype: the
     * longest per-archetype entry times the most bodies the mesh is sized for.
     *
     * Derived from the topology in hand rather than a lattice formula, so it
     * cannot drift from the core's own triangle count — the bridges make the
     * length shape-dependent (an O bridges four seams, the others three), so
     * there is no single per-lattice constant that describes it.
     */
    fun capacityShorts(state: SimState, maxBodies: Int): Int {
        var maxLenPerBody = 0
        for (triangles in state.bodyTriangleIndices) {
            if (triangles.size > maxLenPerBody) maxLenPerBody = triangles.size
        }
        return maxBodies * maxLenPerBody
    }

    /**
     * Concatenate every live body's whole-piece indices into [out], each body's
     * values offset by `b * particlesPerBody` (NOT per cell — the per-archetype
     * array already spans all four cells), as `GL_UNSIGNED_SHORT` values.
     *
     * @param out must hold at least [capacityShorts] shorts for the mesh's
     *   capacity; sizing it from a per-cell formula would under-allocate, since
     *   the bridges make the real count larger and shape-dependent.
     * @return the number of shorts written — the draw's index count, a running
     *   total over the jagged per-archetype lengths, not a closed-form product.
     */
    fun assemble(state: SimState, out: ShortArray): Int {
        val particlesPerBody = state.particlesPerBody
        val perArchetype = state.bodyTriangleIndices
        val bodyArchetype = state.bodyArchetype

        var cursor = 0
        for (b in 0 until state.bodyCount) {
            val triangles = perArchetype[bodyArchetype[b]]
            val offset = b * particlesPerBody
            for (v in triangles) {
                out[cursor++] = (v + offset).toShort()
            }
        }
        return cursor
    }
}
