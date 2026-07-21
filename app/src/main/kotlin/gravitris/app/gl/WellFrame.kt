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

    /**
     * The walls are not gel, and this is where that is arranged.
     *
     * The frame shares the body program — it is three quads, and a second
     * program would cost a state change per frame to save nothing — but it has
     * none of the material attribute arrays, because the walls do not deform,
     * have no lattice, and have no free surface. With an array disabled the
     * shader reads the *generic* attribute value, so each one has to be set to
     * whatever makes the gel shader collapse back to a flat `color-surface`
     * quad. Every value below is chosen for that, and each one is load-bearing:
     *
     * | attribute | value | what a wrong value would look like |
     * | --------- | ----- | ---------------------------------- |
     * | compression | 1 (rest) | the generic default is 0, read as *fully* compressed, darkening the walls to the shader's ceiling |
     * | contact | 0 | walls uniformly darkened by the AO seam term |
     * | edge | 0 | the whole wall rim-lit cool white, since the rim term has no geometry to concentrate it on |
     * | body UV | (0, 0) | at (0,0) the subsurface depth is 0 and `mottle` is `sin(0)*sin(0)` = 0, so both terms vanish; at the centre (0.5, 0.5) the walls would instead take the full deep-tone tint |
     * | corner | 0 | the walls have no silhouette corner and would otherwise round toward the tray colour at their (0,0)-UV generic; a wall is not a piece (backend handoff 0036) |
     *
     * Band glow is the one term this cannot reach, because it is driven by
     * world height rather than by a vertex attribute. It is suppressed in the
     * shader instead, by testing `vArchetype` against the piece count — a wall
     * glowing on its own would read as a horizontal HUD line rather than as
     * material warming from within, which is exactly what the client rejected.
     * The §14 gloss streak is suppressed the same way (its geometry does not
     * vanish at UV (0,0)), by the same `vArchetype < PIECE_COUNT` gate — so a
     * wall never picks up a glossy candy highlight.
     *
     * The dither still applies, which is intentional: `color-surface` #1B1E29
     * is dark enough to band against a true-black background on its own.
     */
    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glVertexAttrib1f(BodyMesh.ATTRIB_COMPRESSION, 1f)
        GLES30.glVertexAttrib1f(BodyMesh.ATTRIB_CONTACT, 0f)
        GLES30.glVertexAttrib2f(BodyMesh.ATTRIB_BODY_UV, 0f, 0f)
        GLES30.glVertexAttrib1f(BodyMesh.ATTRIB_EDGE, 0f)
        GLES30.glVertexAttrib1f(BodyMesh.ATTRIB_CORNER, 0f)
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
