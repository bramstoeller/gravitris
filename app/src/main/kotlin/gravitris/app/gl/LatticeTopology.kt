package gravitris.app.gl

/**
 * The static triangle topology of a lattice body, shared by every body of the
 * same quality tier.
 *
 * ADR 0007 §2: "A body's lattice topology never changes — only its vertex
 * positions do. Indices are uploaded once per lattice size and reused for
 * every body forever."
 *
 * Pure and free of GL so it can be tested on the JVM. That matters more than
 * usual here: this build container has no GPU and no usable emulator, so a
 * malformed index buffer would not surface as a wrong picture during
 * development — it would surface as a black screen on the client's phone, with
 * no way to bisect it from here. Testing the topology directly is the only
 * verification available before the APK leaves the container.
 */
object LatticeTopology {

    /** Triangles per body for a lattice of [lattice] particles per edge. */
    fun trianglesPerBody(lattice: Int): Int = (lattice - 1) * (lattice - 1) * 2

    /** Indices per body — three per triangle. */
    fun indicesPerBody(lattice: Int): Int = trianglesPerBody(lattice) * 3

    /**
     * Build the index buffer covering [maxBodies] bodies.
     *
     * Body `n`'s indices are body 0's pattern offset by `n * lattice^2`, which
     * is what lets every body share one vertex buffer and one draw call.
     *
     * Winding is consistently counter-clockwise in a y-up world space. Face
     * culling is disabled at Stage 1 (the geometry is 2D and opaque), so
     * winding does not currently affect what is drawn — but it will the moment
     * anything wants a front/back distinction, and getting it consistent now
     * costs nothing.
     */
    fun buildIndices(maxBodies: Int, lattice: Int): ShortArray {
        require(lattice >= 2) { "A lattice needs at least 2 particles per edge, got $lattice" }
        require(maxBodies >= 1) { "maxBodies must be positive, got $maxBodies" }

        val particlesPerBody = lattice * lattice
        val vertexCount = maxBodies * particlesPerBody
        require(vertexCount <= 65_536) {
            "Indices are GL_UNSIGNED_SHORT, capped at 65536 vertices; " +
                "$maxBodies bodies x $particlesPerBody particles = $vertexCount"
        }

        val indices = ShortArray(maxBodies * indicesPerBody(lattice))
        var cursor = 0
        for (body in 0 until maxBodies) {
            val base = body * particlesPerBody
            for (row in 0 until lattice - 1) {
                for (column in 0 until lattice - 1) {
                    // Row 0 is the bottom of the body in world space, so "row +
                    // 1" is above. Names follow that, not screen convention.
                    val bottomLeft = base + row * lattice + column
                    val bottomRight = bottomLeft + 1
                    val topLeft = bottomLeft + lattice
                    val topRight = topLeft + 1

                    indices[cursor++] = bottomLeft.toShort()
                    indices[cursor++] = bottomRight.toShort()
                    indices[cursor++] = topLeft.toShort()

                    indices[cursor++] = topLeft.toShort()
                    indices[cursor++] = bottomRight.toShort()
                    indices[cursor++] = topRight.toShort()
                }
            }
        }
        return indices
    }
}
