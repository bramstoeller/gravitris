package gravitris.app.gl

import android.opengl.GLES30
import gravitris.app.sim.SimState
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
 * ## Deviation from ADR 0007, recorded deliberately
 *
 * ADR 0007 says "one dynamic vertex buffer for all bodies", interleaved. This
 * uses **two** buffers: positions (dynamic, rewritten every frame) and
 * archetype indices (static, rewritten only when the set of bodies changes).
 *
 * The archetype of a body never changes while that body exists, so
 * interleaving it would mean re-uploading 1500 integers per frame that are
 * bit-identical to the ones already there. Splitting by update frequency
 * rather than by vertex is the standard answer and it costs one extra buffer
 * binding at setup, not per frame.
 *
 * This does not conflict with Stage 3. Every varying ADR 0007 adds later
 * (`vCompression`, `vEdge`, `vContact`, `vBodyUv`) is per-particle and changes
 * every frame, so all of them belong in the dynamic buffer alongside position
 * — which is exactly the interleaving the ADR is protecting. The archetype was
 * the one member of that list that was never dynamic.
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
    private var positionVbo = 0
    private var archetypeVbo = 0
    private var ibo = 0

    /** Interleaved x,y for every particle, in world units, already interpolated. */
    private val positionScratch = FloatArray(maxParticles * 2)
    private val archetypeScratch = IntArray(maxParticles)

    private val positionBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(positionScratch.size * Float.SIZE_BYTES)
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
        positionVbo = names[0]
        archetypeVbo = names[1]
        ibo = names[2]

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]

        GLES30.glBindVertexArray(vao)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, positionVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            positionScratch.size * Float.SIZE_BYTES,
            null,
            GLES30.GL_STREAM_DRAW,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(ATTRIB_POSITION, 2, GLES30.GL_FLOAT, false, 0, 0)

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

        val currentX = state.positionX
        val currentY = state.positionY
        val previousX = state.prevPositionX
        val previousY = state.prevPositionY

        // The ADR 0006 lerp, fused into the buffer fill. The ADR notes this is
        // where interpolation becomes almost free: the vertex buffer is being
        // rebuilt every frame anyway, so it costs one lerp per component rather
        // than a separate pass over the particle arrays.
        var cursor = 0
        for (i in 0 until particles) {
            positionScratch[cursor++] = previousX[i] + (currentX[i] - previousX[i]) * alpha
            positionScratch[cursor++] = previousY[i] + (currentY[i] - previousY[i]) * alpha
        }

        positionBuffer.position(0)
        positionBuffer.put(positionScratch, 0, cursor)
        positionBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, positionVbo)
        // Buffer orphaning (ADR 0007 §1): hand the driver a fresh allocation
        // before writing, so it never has to stall waiting for the previous
        // frame's draw to finish reading the old contents.
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            positionScratch.size * Float.SIZE_BYTES,
            null,
            GLES30.GL_STREAM_DRAW,
        )
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            cursor * Float.SIZE_BYTES,
            positionBuffer,
        )

        if (state.bodyCount != uploadedBodies) {
            uploadArchetypes(state, particles)
            uploadedBodies = state.bodyCount
        }

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        indexCount = state.bodyCount * indicesPerBody
        return particles
    }

    private fun uploadArchetypes(state: SimState, particles: Int) {
        val particleBody = state.particleBody
        val bodyArchetype = state.bodyArchetype
        for (i in 0 until particles) {
            archetypeScratch[i] = bodyArchetype[particleBody[i]]
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

    /** Triangles submitted this frame, for the readout. */
    fun trianglesDrawn(): Int = indexCount / 3

    companion object {
        const val ATTRIB_POSITION = 0
        const val ATTRIB_ARCHETYPE = 1
    }
}
