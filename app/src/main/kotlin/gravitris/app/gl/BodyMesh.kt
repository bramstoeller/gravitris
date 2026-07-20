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
 * - **dynamic, interleaved, rewritten every frame** — position and
 *   compression, 12 bytes per particle. This is the buffer the ADR is talking
 *   about, and every varying Stage 3 adds (`vEdge`, `vContact`, `vBodyUv`) is
 *   per-particle and per-frame, so all of them join it here.
 * - **static, rewritten only when the set of bodies changes** — the archetype
 *   index. A body's archetype never changes while that body exists, so
 *   interleaving it would mean re-uploading 1500 integers per frame that are
 *   bit-identical to the ones already there.
 *
 * The archetype is the one member of ADR 0007's varying list that was never
 * dynamic, so pulling it out costs one extra buffer binding at setup and
 * nothing per frame, while leaving the ADR's actual structure intact.
 *
 * ## Index buffer sizing
 *
 * `GL_UNSIGNED_SHORT`, not `GL_UNSIGNED_INT`. At the default tier the maximum
 * vertex index is 60 bodies x 25 particles = 1500, comfortably inside 65535,
 * and halving index bandwidth is free. This assumption is asserted at
 * construction so a future quality tier that breaks it fails loudly here
 * rather than rendering garbage.
 */
class BodyMesh(private val maxBodies: Int, private val lattice: Int) {

    private val particlesPerBody = lattice * lattice
    private val maxParticles = maxBodies * particlesPerBody
    private val indicesPerBody = LatticeTopology.indicesPerBody(lattice)

    private var vao = 0
    private var dynamicVbo = 0
    private var archetypeVbo = 0
    private var ibo = 0

    /**
     * Interleaved `[x, y, compression]` per particle: position in world units
     * and already interpolated, compression as current/rest area.
     */
    private val vertexScratch = FloatArray(maxParticles * FLOATS_PER_VERTEX)
    private val archetypeScratch = IntArray(maxParticles)

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(vertexScratch.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val archetypeBuffer: IntBuffer = ByteBuffer
        .allocateDirect(archetypeScratch.size * Int.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asIntBuffer()

    /** Bodies whose archetypes are currently uploaded, so the static buffer is
     *  only rewritten when the set of bodies actually changes. */
    private var uploadedBodies = -1

    private var indexCount = 0

    init {
        require(maxParticles <= 65_536) {
            "BodyMesh uses GL_UNSIGNED_SHORT indices, which cap at 65536 vertices; " +
                "$maxBodies bodies x $particlesPerBody particles = $maxParticles. " +
                "Either lower maxBodies or switch the IBO to GL_UNSIGNED_INT."
        }
    }

    /** Creates every GL object. Safe to call again after context loss. */
    fun create() {
        val names = IntArray(3)
        GLES30.glGenBuffers(3, names, 0)
        dynamicVbo = names[0]
        archetypeVbo = names[1]
        ibo = names[2]

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
     * Build the index buffer once. ADR 0007 §2: a body's lattice topology never
     * changes, only its vertex positions do — so this is uploaded once and
     * reused for every body forever. The pattern for body 0 is repeated with a
     * vertex offset for each subsequent body, which is what lets all bodies
     * share a single draw call.
     */
    private fun uploadIndices() {
        val indices = LatticeTopology.buildIndices(maxBodies, lattice)

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
                "$maxParticles ($maxBodies bodies x $particlesPerBody); the shell's body cap " +
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
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            vertexScratch.size * Float.SIZE_BYTES,
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
            uploadArchetypes(state, particles)
            uploadedBodies = state.bodyCount
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        indexCount = state.bodyCount * indicesPerBody
        return particles
    }

    /**
     * Archetypes are clamped into the palette's range on the way to the GPU.
     *
     * `uPalette` is a fixed-size array in GLSL and indexing it out of bounds is
     * undefined behaviour — not a wrong colour but anything the driver likes,
     * differing per GPU. The core declares seven archetypes
     * (`Simulation.ARCHETYPE_COUNT`) while docs/ux/piece-identity.md specifies
     * six hues, so the two counts already disagree and the shell is what stands
     * between that disagreement and the driver.
     *
     * [gravitris.app.toy.SquishToy] only ever asks for archetypes in range, so
     * this clamp should never fire today. It is here because the failure it
     * prevents is undebuggable from this container and would arrive the first
     * time Stage 3 introduces a seventh piece.
     */
    private fun uploadArchetypes(state: SimState, particles: Int) {
        val particleBody = state.particleBody
        val bodyArchetype = state.bodyArchetype
        for (i in 0 until particles) {
            archetypeScratch[i] = bodyArchetype[particleBody[i]].coerceIn(0, Palette.SIZE - 1)
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

        /** `[x, y, compression]` — the dynamic, per-frame vertex. */
        const val FLOATS_PER_VERTEX = 3
        const val VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.SIZE_BYTES
    }
}
