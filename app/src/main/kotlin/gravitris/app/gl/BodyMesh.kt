package gravitris.app.gl

import android.opengl.GLES30
import gravitris.app.Palette
import gravitris.game.SimState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The deforming geometry: one dynamic position buffer for every body in the
 * well, one static index buffer, one draw call.
 *
 * Implements ADR 0007 §1-3. Every body's geometry is new every frame — the
 * opposite of the static-mesh case GPUs are optimised for — so the per-frame
 * path is: walk the simulation's position arrays, apply the ADR 0006
 * interpolation lerp, upload with buffer orphaning, draw everything in one
 * call.
 *
 * ## Buffer split, and how it relates to ADR 0007
 *
 * ADR 0007 says "one dynamic vertex buffer for all bodies", interleaved. There
 * are **two** buffers here, split by update frequency rather than by vertex:
 *
 * - **dynamic, interleaved, rewritten every frame** — position, compression
 *   and contact, 16 bytes per particle.
 * - **static, rewritten only when the set of bodies changes** — the archetype
 *   index, and the body UV / free-surface pair.
 *
 * Stage 1 predicted that every varying Stage 3 added would be per-frame and
 * would join the dynamic buffer. **Two of the three turned out not to be**, and
 * the correction is worth stating because it is most of why Stage 3B's material
 * inputs are close to free:
 *
 * - `vContact` is genuinely dynamic — the solver rewrites it every tick — so it
 *   joined the interleaved buffer, taking it from 12 to 16 bytes per particle.
 *   At 24 bodies that is 7.0 KB/frame to 9.4 KB/frame, against the 2 MB/s that
 *   ADR 0007 measured and explicitly told us not to treat as a bottleneck.
 * - `vBodyUv` and `vEdge` are **static per particle**. `:core-sim` writes them
 *   once at body creation and never again, because they describe where a
 *   particle sits in its own lattice. So they sit with the archetype and cost
 *   nothing per frame at all.
 *
 * Splitting by update frequency rather than by vertex is what makes that
 * distinction expressible. It costs two extra buffer bindings at setup and
 * nothing per frame, and leaves ADR 0007's actual structure — one upload, one
 * draw call — intact.
 *
 * ## Index buffer sizing
 *
 * `GL_UNSIGNED_SHORT`, not `GL_UNSIGNED_INT`. At the default tier the maximum
 * vertex index is 60 bodies x 25 particles = 1500, comfortably inside 65535,
 * and halving index bandwidth is free. This assumption is asserted at
 * construction so a future quality tier that breaks it fails loudly here
 * rather than rendering garbage.
 */
class BodyMesh(private val maxParticles: Int, private val lattice: Int) {

    /**
     * Particles in one `lattice x lattice` cell. **The renderer's unit is the
     * cell, not the body** (ADR 0015): a tetromino body is four such cells laid
     * out as four consecutive `particlesPerCell` blocks in the particle arrays,
     * and the reused index pattern, the boundary extrusion and the per-instance
     * stride all work in cell units. A single-block piece was one cell; a
     * tetromino is four, at the same stride.
     */
    private val particlesPerCell = lattice * lattice

    /** Cells the buffers are sized for — the worst-case particle capacity in cells. */
    private val maxCells = maxParticles / particlesPerCell

    /** Indices for one cell; the index buffer repeats this pattern per cell. */
    private val indicesPerCell = LatticeTopology.indicesPerBody(lattice)

    private var vao = 0
    private var dynamicVbo = 0
    private var archetypeVbo = 0
    private var materialVbo = 0
    private var ibo = 0

    /**
     * Interleaved `[x, y, compression, contact]` per particle: position in
     * world units and already interpolated, compression as current/rest area,
     * contact as the solver's occlusion accumulator.
     */
    private val vertexScratch = FloatArray(maxParticles * FLOATS_PER_VERTEX)
    private val archetypeScratch = IntArray(maxParticles)

    /** Interleaved `[u, v, edge]` per particle — static, see the class note. */
    private val materialScratch = FloatArray(maxParticles * FLOATS_PER_MATERIAL_VERTEX)

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexScratch.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val archetypeBuffer: IntBuffer = ByteBuffer
        .allocateDirect(archetypeScratch.size * Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()

    private val materialBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(materialScratch.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /** Bodies whose archetypes are currently uploaded, so the static buffer is
     *  only rewritten when the set of bodies actually changes. */
    private var uploadedBodies = -1

    private var indexCount = 0

    init {
        require(maxParticles % particlesPerCell == 0) {
            "maxParticles ($maxParticles) is not a whole number of $particlesPerCell-particle " +
                "cells; capacity must come from SimState.particleCapacity, which is always a " +
                "whole number of bodies and therefore of cells"
        }
        require(maxParticles <= 65_536) {
            "BodyMesh uses GL_UNSIGNED_SHORT indices, which cap at 65536 vertices; " +
                "$maxCells cells x $particlesPerCell particles = $maxParticles. " +
                "Either lower the capacity or switch the IBO to GL_UNSIGNED_INT."
        }
    }

    /** Creates every GL object. Safe to call again after context loss. */
    fun create() {
        val names = IntArray(4)
        GLES30.glGenBuffers(4, names, 0)
        dynamicVbo = names[0]
        archetypeVbo = names[1]
        materialVbo = names[2]
        ibo = names[3]

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]

        GLES30.glBindVertexArray(vao)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexScratch.size * Float.SIZE_BYTES,
            null,
            GLES30.GL_STREAM_DRAW,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            ATTRIB_POSITION, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE_BYTES, 0,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_COMPRESSION)
        GLES30.glVertexAttribPointer(
            ATTRIB_COMPRESSION, 1, GLES30.GL_FLOAT, false,
            VERTEX_STRIDE_BYTES, 2 * Float.SIZE_BYTES,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_CONTACT)
        GLES30.glVertexAttribPointer(
            ATTRIB_CONTACT, 1, GLES30.GL_FLOAT, false,
            VERTEX_STRIDE_BYTES, 3 * Float.SIZE_BYTES,
        )

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, materialVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            materialScratch.size * Float.SIZE_BYTES,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_BODY_UV)
        GLES30.glVertexAttribPointer(
            ATTRIB_BODY_UV, 2, GLES30.GL_FLOAT, false, MATERIAL_STRIDE_BYTES, 0,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_EDGE)
        GLES30.glVertexAttribPointer(
            ATTRIB_EDGE, 1, GLES30.GL_FLOAT, false,
            MATERIAL_STRIDE_BYTES, 2 * Float.SIZE_BYTES,
        )
        // §16 rounded corners: the true-silhouette-corner flag, static per
        // particle (SimState.particleCorner), on the same slow-update buffer as
        // UV and edge — it changes only when the set of bodies changes, so it
        // costs nothing per frame. Interpolated across the mesh exactly like
        // aEdge; the shader shapes the rounding from it (backend handoff 0036).
        GLES30.glEnableVertexAttribArray(ATTRIB_CORNER)
        GLES30.glVertexAttribPointer(
            ATTRIB_CORNER, 1, GLES30.GL_FLOAT, false,
            MATERIAL_STRIDE_BYTES, 3 * Float.SIZE_BYTES,
        )

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, archetypeVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            archetypeScratch.size * Int.SIZE_BYTES,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_ARCHETYPE)
        // glVertexAttribIPointer, not glVertexAttribPointer: the attribute is a
        // genuine integer in the shader. Using the float entry point here would
        // silently reinterpret the bits and index the palette with nonsense.
        GLES30.glVertexAttribIPointer(ATTRIB_ARCHETYPE, 1, GLES30.GL_INT, 0, 0)

        uploadIndices()

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        uploadedBodies = -1
    }

    /**
     * Build the index buffer once. ADR 0007 §2: a cell's lattice topology never
     * changes, only its vertex positions do — so this is uploaded once and
     * reused for every cell forever. The pattern for cell 0 is repeated with a
     * vertex offset for each subsequent cell (a tetromino is four cells,
     * ADR 0015), which is what lets the whole stack share a single draw call.
     */
    private fun uploadIndices() {
        val indices = LatticeTopology.buildIndices(maxCells, lattice)

        val buffer = ByteBuffer
            .allocateDirect(indices.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        buffer.put(indices).position(0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * Short.SIZE_BYTES,
            buffer,
            GLES30.GL_STATIC_DRAW,
        )
    }

    /**
     * Fill and upload this frame's geometry.
     *
     * @param alpha ADR 0006 interpolation factor, `accumulator / TICK`.
     * @return the number of particles uploaded, for the readout.
     */
    fun upload(state: SimState, alpha: Float): Int {
        val particles = state.particleCount
        if (particles == 0) {
            indexCount = 0
            return 0
        }

        // The shell caps bodies at the figure these buffers were sized for, so
        // this cannot fire today. It is here because the alternative failure is
        // an array index exception thrown from the GL thread, which takes the
        // app down with a message that says nothing about the actual cause.
        check(particles <= maxParticles) {
            "the simulation has $particles particles but the mesh was sized for " +
                "$maxParticles ($maxCells cells x $particlesPerCell); the shell's capacity " +
                "and the buffer sizing have diverged"
        }

        val cursor = VertexFill.fill(state, alpha, vertexScratch)

        vertexBuffer.position(0)
        vertexBuffer.put(vertexScratch, 0, cursor)
        vertexBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dynamicVbo)
        // Buffer orphaning (ADR 0007 §1): hand the driver a fresh allocation
        // before writing, so it never has to stall waiting for the previous
        // frame's draw to finish reading the old contents.
        //
        // Orphaned at the LIVE size, not the buffer's capacity. This used to
        // pass `vertexScratch.size`, which is `maxBodies` — 40 bodies x 25
        // particles x 16 bytes = 16 KB reallocated every frame no matter how
        // few bodies were in the well, against the 7-10 KB a typical stack
        // actually writes. The driver's allocator is precisely the kind of
        // place multi-millisecond frame-time outliers come from, and this was
        // asking it to do roughly twice the work for nothing. Found while
        // auditing the render path for per-frame allocation after the backend
        // engineer showed the device's jank is not the solver.
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            cursor * Float.SIZE_BYTES,
            null,
            GLES30.GL_STREAM_DRAW,
        )
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            cursor * Float.SIZE_BYTES,
            vertexBuffer,
        )

        if (state.bodyCount != uploadedBodies) {
            uploadStatics(state, particles)
            uploadedBodies = state.bodyCount
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        // Draw by CELL, not by body: a tetromino is four cells and the index
        // pattern is per-cell (ADR 0015). particleCount is always a whole number
        // of cells, so this is exact.
        indexCount = (particles / particlesPerCell) * indicesPerCell
        return particles
    }

    /**
     * Upload everything that is constant for as long as the current set of
     * bodies exists: the archetype index, and the body UV / free-surface pair.
     *
     * ## Archetypes are folded onto the piece hues on the way to the GPU
     *
     * `uPalette` is a fixed-size array in GLSL and indexing it out of bounds is
     * undefined behaviour — not a wrong colour but anything the driver likes,
     * differing per GPU. The core declares seven archetypes
     * (`Simulation.ARCHETYPE_COUNT`) while docs/ux/piece-identity.md specifies
     * six hues, so the two counts disagree and the shell is what stands between
     * that disagreement and the driver.
     *
     * The real piece sequence deals **all seven** archetypes. The Milestone-1
     * toy only ever dealt six, so a plain clamp sufficed for it; under the wired
     * game archetype 6 would clamp to [Palette.SURFACE_INDEX] and paint a piece
     * in the well-surface grey. [Palette.pieceHue] folds every archetype onto a
     * real piece hue (0 until [Palette.PIECE_COUNT]) instead — see it for the
     * 7-vs-6 collision, which is a flagged design decision, not this method's.
     */
    private fun uploadStatics(state: SimState, particles: Int) {
        val particleBody = state.particleBody
        val bodyArchetype = state.bodyArchetype
        for (i in 0 until particles) {
            archetypeScratch[i] = Palette.pieceHue(bodyArchetype[particleBody[i]])
        }

        archetypeBuffer.position(0)
        archetypeBuffer.put(archetypeScratch, 0, particles)
        archetypeBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, archetypeVbo)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            particles * Int.SIZE_BYTES,
            archetypeBuffer,
        )

        val materialFloats = VertexFill.fillStatics(state, materialScratch)
        materialBuffer.position(0)
        materialBuffer.put(materialScratch, 0, materialFloats)
        materialBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, materialVbo)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            materialFloats * Float.SIZE_BYTES,
            materialBuffer,
        )
    }

    /** One draw call for the entire stack — ADR 0007 §3. */
    fun draw() {
        if (indexCount == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES,
            indexCount,
            GLES30.GL_UNSIGNED_SHORT,
            0,
        )
        GLES30.glBindVertexArray(0)
    }

    /**
     * Force the static archetype buffer to be rewritten on the next [upload].
     *
     * The buffer is normally rewritten only when the body *count* changes,
     * which is sound while bodies are only ever added. Emptying the well
     * violates that: a fresh simulation can present the same body count with
     * different archetypes, and the stale buffer would leave every piece
     * wearing the previous well's colours. Called by the renderer whenever it
     * replaces the simulation.
     */
    fun invalidateArchetypes() {
        uploadedBodies = -1
    }

    /** Triangles submitted this frame, for the readout. */
    fun trianglesDrawn(): Int = indexCount / 3

    companion object {
        const val ATTRIB_POSITION = 0
        const val ATTRIB_ARCHETYPE = 1
        const val ATTRIB_COMPRESSION = 2
        const val ATTRIB_CONTACT = 3
        const val ATTRIB_BODY_UV = 4
        const val ATTRIB_EDGE = 5
        const val ATTRIB_CORNER = 6

        /** `[x, y, compression, contact]` — the dynamic, per-frame vertex. */
        const val FLOATS_PER_VERTEX = 4
        const val VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.SIZE_BYTES

        /** `[u, v, edge, corner]` — uploaded only when the set of bodies changes. */
        const val FLOATS_PER_MATERIAL_VERTEX = 4
        const val MATERIAL_STRIDE_BYTES = FLOATS_PER_MATERIAL_VERTEX * Float.SIZE_BYTES
    }
}
