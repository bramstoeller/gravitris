package gravitris.app.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The luminance beat's intensity envelope, extracted so it can be unit-tested
 * without a GPU: a symmetric triangle over `[0, durationNanos)` — up over the
 * first half, down over the second — that peaks at 1 mid-window and is 0 at both
 * ends and outside the window. It rises with the shader's 120ms ignition flash
 * and is gone by the end of the hold, so the beat and the flash read as one
 * event. Wall-clock nanoseconds, frame-rate independent.
 */
internal fun clearFlashEnvelope(elapsedNanos: Long, durationNanos: Long): Float {
    if (elapsedNanos < 0L || elapsedNanos >= durationNanos) return 0f
    val half = durationNanos / 2f
    val e = elapsedNanos.toFloat()
    return if (e < half) e / half else (durationNanos - e) / half
}

/**
 * The screen-wide luminance beat that fires with a band-clear ignition
 * (`docs/ux/visual-direction.md` §7.1) — the first of the three cheap
 * additions that turn an already-good clear into an *event* the player
 * registers, per Candy Crush's own lesson that the payoff is the layered
 * feedback around the instant, not the instant itself.
 *
 * ## What it is
 *
 * A single full-screen quad, flat additive `color-glow-hot` `#FFF4E0` at a very
 * low peak (~8%), faded in and out over the same 120 ms the ignition flash
 * already ramps. It is driven by a caller-supplied `intensity` (0..1) so all
 * the timing lives in one place in the renderer alongside the ember burst.
 *
 * ## What it is deliberately not
 *
 * **Not bloom, not HDR, not a post-process pass.** The spec is explicit that
 * the "no bloom" constraint is answered by *not being one*: this is a plain
 * additive blend of a single triangle, not a per-fragment glow-spread or a
 * blur kernel. Cost is one draw call and a trivial fragment shader for ~120 ms,
 * once per clear event — never a sustained per-frame cost.
 *
 * Blending is enabled only for this draw and restored to the renderer's
 * global "blend off" afterwards, so the opaque scene path is untouched.
 */
class ClearFlash {

    private var program = 0
    private var intensityUniform = -1

    private var vao = 0
    private var vbo = 0

    fun create() {
        program = GlProgram.build(VERTEX, FRAGMENT)
        intensityUniform = GLES30.glGetUniformLocation(program, "uIntensity")

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]

        val verts = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val buffer = ByteBuffer
            .allocateDirect(verts.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(verts).position(0)

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            verts.size * Float.SIZE_BYTES,
            buffer,
            GLES30.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(ATTRIB_POSITION, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Draw the beat at the given [intensity] (0..1). A zero-or-negative
     * intensity is a no-op, so the renderer calls this unconditionally and pays
     * nothing when no clear is active.
     *
     * Enables additive blending for its own draw and disables it again — the
     * renderer keeps `GL_BLEND` off globally for the opaque scene, so this must
     * leave that invariant exactly as it found it.
     */
    fun draw(intensity: Float) {
        if (intensity <= 0f) return

        GLES30.glEnable(GLES30.GL_BLEND)
        // Additive: the beat adds warm light on top of the finished scene. The
        // fragment premultiplies by intensity, so the source factor is GL_ONE.
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)

        GLES30.glUseProgram(program)
        GLES30.glUniform1f(intensityUniform, intensity.coerceIn(0f, 1f))
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)

        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private companion object {
        const val ATTRIB_POSITION = 0

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPosition;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

        // color-glow-hot #FFF4E0 (docs/ux/tokens.md), pre-scaled by the ~8% peak
        // so full intensity adds only a soft warm wash, never a white-out — the
        // beat acknowledges the clear, it does not blind. PEAK folds the peak
        // opacity into the constant so the fragment is a single multiply.
        private const val FRAGMENT = """#version 300 es
precision mediump float;
uniform float uIntensity;
out vec4 fragColor;
const vec3 HOT = vec3(1.0, 0.957, 0.878);
const float PEAK = 0.08;
void main() {
    fragColor = vec4(HOT * (PEAK * uIntensity), 1.0);
}
"""
    }
}
