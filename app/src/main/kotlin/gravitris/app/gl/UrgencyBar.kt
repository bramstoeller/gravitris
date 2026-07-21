package gravitris.app.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The positioning-window countdown, drawn as a bar across the top of the well
 * that drains toward the centre as the window runs out (ADR 0016).
 *
 * The core parks a freshly-spawned piece at the spawn row for a short window,
 * during which the player slides it; then it drops on its own. `SimState`
 * publishes the remaining fraction as `positioningTicksRemaining /
 * positioningWindowTicks`, and this turns that 0..1 value into a "move now"
 * pressure cue — the client's *"much less long able to move"*. See
 * [gravitris.app.PositioningUrgency] for the fraction itself.
 *
 * ## Its own flat program, not the gel shader
 *
 * [WellFrame] shares the gel program because a wall is still gel-shaped
 * geometry with the material terms neutralised. This is not: it is a HUD cue
 * with a flat colour and no material, and pushing it through the gel shader
 * would mean carrying a palette slot for a colour that is not a piece hue and
 * then setting five vertex attributes to cancel out subsurface, rim, contact,
 * grain and glow. A ten-line flat program is both smaller and honest about what
 * this is. It costs one program switch per frame, and only while a piece is
 * positioning — a fraction of the session — against nothing saved.
 *
 * The geometry is rebuilt every frame because the bar's length *is* the signal,
 * so there is nothing static to cache: one quad, eight floats, uploaded into a
 * `GL_DYNAMIC_DRAW` buffer.
 */
class UrgencyBar {

    private var program = 0
    private var scaleUniform = -1
    private var offsetUniform = -1
    private var colorUniform = -1

    private var vao = 0
    private var vbo = 0

    private val positions = FloatArray(4 * 2)
    private val positionBuffer = ByteBuffer
        .allocateDirect(positions.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /** Build the program and buffers. Safe to call again after context loss
     *  (ADR 0010 §6) — everything here is recreated from source. */
    fun create() {
        program = GlProgram.build(VERTEX, FRAGMENT)
        scaleUniform = GLES30.glGetUniformLocation(program, "uScale")
        offsetUniform = GLES30.glGetUniformLocation(program, "uOffset")
        colorUniform = GLES30.glGetUniformLocation(program, "uColor")

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            positions.size * Float.SIZE_BYTES,
            null,
            GLES30.GL_DYNAMIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(BodyMesh.ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(BodyMesh.ATTRIB_POSITION, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Draw the bar at the given remaining [fraction] (0..1), across the top of a
     * well [widthWorld] x [heightWorld] world units, using the renderer's own
     * world-to-clip [scale] and [offset]. Caller draws nothing when the piece is
     * not positioning; a zero-or-negative fraction is a no-op here as well.
     *
     * The bar is centred and shrinks symmetrically toward the middle, so the
     * countdown reads as "closing in" rather than sliding off one edge. It sits
     * flush with the top edge of the play area, above where a positioning piece
     * hovers, so it never overdraws the piece the player is aiming.
     */
    fun draw(
        fraction: Float,
        widthWorld: Float,
        heightWorld: Float,
        scale: FloatArray,
        offset: FloatArray,
    ) {
        if (fraction <= 0f) return

        val half = 0.5f * widthWorld * fraction.coerceIn(0f, 1f)
        val cx = 0.5f * widthWorld
        val x0 = cx - half
        val x1 = cx + half
        val y1 = heightWorld
        val y0 = heightWorld - THICKNESS

        // Triangle strip: bottom-left, bottom-right, top-left, top-right.
        // Cull-face is disabled by the renderer, so winding is immaterial.
        positions[0] = x0; positions[1] = y0
        positions[2] = x1; positions[3] = y0
        positions[4] = x0; positions[5] = y1
        positions[6] = x1; positions[7] = y1

        positionBuffer.position(0)
        positionBuffer.put(positions)
        positionBuffer.position(0)

        GLES30.glUseProgram(program)
        GLES30.glUniform2f(scaleUniform, scale[0], scale[1])
        GLES30.glUniform2f(offsetUniform, offset[0], offset[1])
        GLES30.glUniform4f(colorUniform, COLOR_R, COLOR_G, COLOR_B, 1f)

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            positions.size * Float.SIZE_BYTES,
            positionBuffer,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private companion object {
        /** Thickness in world units — see [gravitris.app.Tunables.POSITIONING_BAR_THICKNESS_WORLD]. */
        val THICKNESS = gravitris.app.Tunables.POSITIONING_BAR_THICKNESS_WORLD

        // color-text #F2F1EC (docs/ux/tokens.md). A neutral, legible placeholder
        // rather than a warm one: the amber/red warning hues (color-glow,
        // color-warn) both already carry gameplay meaning — band fill and a
        // not-yet-spec'd top-out zone — that a positioning timer must not be
        // mistaken for. The visual pass owns the final colour.
        const val COLOR_R = 0xF2 / 255f
        const val COLOR_G = 0xF1 / 255f
        const val COLOR_B = 0xEC / 255f

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPosition;
uniform vec2 uScale;
uniform vec2 uOffset;
void main() {
    gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
}
"""

        private const val FRAGMENT = """#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}
"""
    }
}
