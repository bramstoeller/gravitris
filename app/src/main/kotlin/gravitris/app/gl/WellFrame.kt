package gravitris.app.gl

import android.opengl.GLES30
import gravitris.app.Palette
import gravitris.app.WellLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The well's walls and floor: three rectangles in `color-surface`.
 *
 * `docs/ux/screens/playing.md`: "Well walls/floor — `color-surface`, otherwise
 * undecorated. No grid lines (the whole point of this game is that there is no
 * grid)." So this is three quads and nothing else, and it should stay that way.
 *
 * The walls sit **outside** the play rectangle, in the inset margin. That is
 * the ADR 0010 distinction made visible: the GL surface covers the whole
 * display including under the system bars, the *playfield* is inset, and the
 * boundary between them is exactly where these rectangles are drawn. On a
 * device with a large gesture bar the floor is partly behind it, which is
 * correct — the surface bleeds to the screen edge decoratively while nothing
 * playable does.
 *
 * Rebuilt whenever the layout changes (rotation, inset change), which is rare
 * enough that a dynamic buffer costs nothing and avoids a second coordinate
 * system.
 */
class WellFrame {

    private var vao = 0
    private var positionVbo = 0
    private var archetypeVbo = 0
    private var ibo = 0

    private val positions = FloatArray(QUADS * 4 * 2)

    private val positionBuffer = ByteBuffer
        .allocateDirect(positions.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

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
            positions.size * Float.SIZE_BYTES,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(BodyMesh.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(BodyMesh.ATTRIB_POSITION, 2, GLES30.GL_FLOAT, false, 0, 0)

        // Every vertex of the frame uses the same palette slot, uploaded once.
        val archetypes = IntArray(QUADS * 4) { Palette.SURFACE_INDEX }
        val archetypeBuffer = ByteBuffer
            .allocateDirect(archetypes.size * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        archetypeBuffer.put(archetypes).position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, archetypeVbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            archetypes.size * Int.SIZE_BYTES,
            archetypeBuffer,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(BodyMesh.ATTRIB_ARCHETYPE)
        GLES30.glVertexAttribIPointer(BodyMesh.ATTRIB_ARCHETYPE, 1, GLES30.GL_INT, 0, 0)

        val indices = ShortArray(QUADS * 6)
        for (quad in 0 until QUADS) {
            val base = (quad * 4).toShort()
            val offset = quad * 6
            indices[offset] = base
            indices[offset + 1] = (base + 1).toShort()
            indices[offset + 2] = (base + 2).toShort()
            indices[offset + 3] = base
            indices[offset + 4] = (base + 2).toShort()
            indices[offset + 5] = (base + 3).toShort()
        }
        val indexBuffer = ByteBuffer
            .allocateDirect(indices.size * Short.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        indexBuffer.put(indices).position(0)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * Short.SIZE_BYTES,
            indexBuffer,
            GLES30.GL_STATIC_DRAW,
        )

        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** Recompute the three rectangles for the current well geometry. */
    fun layout(layout: WellLayout) {
        val width = layout.widthWorld
        val height = layout.heightWorld
        val t = THICKNESS_WORLD

        var cursor = 0
        // Floor, spanning the full width plus both wall footprints.
        cursor = quad(cursor, -t, -t, width + t, 0f)
        // Left wall.
        cursor = quad(cursor, -t, 0f, 0f, height)
        // Right wall.
        quad(cursor, width, 0f, width + t, height)

        positionBuffer.position(0)
        positionBuffer.put(positions)
        positionBuffer.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, positionVbo)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            positions.size * Float.SIZE_BYTES,
            positionBuffer,
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun quad(cursor: Int, x0: Float, y0: Float, x1: Float, y1: Float): Int {
        var i = cursor
        positions[i++] = x0; positions[i++] = y0
        positions[i++] = x1; positions[i++] = y0
        positions[i++] = x1; positions[i++] = y1
        positions[i++] = x0; positions[i++] = y1
        return i
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        // The frame shares the body program but has no compression attribute
        // array — the walls do not deform. With the array disabled the shader
        // reads the generic attribute value, which defaults to 0 and would be
        // interpreted as "fully compressed", darkening the walls to the
        // shader's ceiling. Rest is 1.
        GLES30.glVertexAttrib1f(BodyMesh.ATTRIB_COMPRESSION, 1f)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, QUADS * 6, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    private companion object {
        const val QUADS = 3

        /** Wall thickness in world units. The well is 10 world units wide, so
         *  this is a 3% border — visible as a boundary without stealing play
         *  area. */
        const val THICKNESS_WORLD = 0.3f
    }
}
