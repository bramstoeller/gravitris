package gravitris.app.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * The ember-particle burst at a clearing band (`docs/ux/visual-direction.md`
 * §7.2) — the second cheap addition around the clear, the embers of the
 * ignition escaping the material.
 *
 * ## Cheap by construction — the "Juice it or lose it" discipline
 *
 * These are **not** soft bodies: no physics, no lattice, no solver. Each ember
 * is one small additive quad following a purely **analytic** trajectory —
 * position computed from elapsed wall-clock time alone (`p = origin + v·t +
 * ½g·t²`), a small upward-then-falling arc — and faded out on an alpha ramp.
 * Colour is `color-glow` amber `#FFB347`, the same reserved hue as the
 * band-glow itself, so these read as the ignition's embers rather than a new
 * colour language.
 *
 * ## One reusable pool, sized once
 *
 * The geometry is a **ring buffer sized once at startup** — never allocated per
 * clear — exactly as `BodyMesh` sizes for its worst case. A clear event spawns
 * a burst into the ring; older embers are overwritten only if bursts pile up
 * faster than they expire, which is graceful degradation, not a leak. Clears
 * are an occasional burst (a handful a minute), so this never competes with the
 * sustained per-frame body-rendering budget.
 *
 * Its own flat program with additive blending, enabled only for its own draw
 * and restored afterwards — the renderer keeps `GL_BLEND` off globally for the
 * opaque scene. Embers live in **world space** and are drawn with the same
 * world-to-clip `scale`/`offset` as the bodies, so a burst lands exactly on the
 * band it came from.
 */
class EmberBurst(private val seed: Long = 0x9E3779B97F4A7C15uL.toLong()) {

    private var program = 0
    private var scaleUniform = -1
    private var offsetUniform = -1

    private var vao = 0
    private var vbo = 0

    // Per-ember state, structure-of-arrays, allocated once. An ember is live
    // while its spawn time is non-zero and its age is under LIFE_SECONDS.
    private val spawnNanos = LongArray(POOL)
    private val originX = FloatArray(POOL)
    private val originY = FloatArray(POOL)
    private val velX = FloatArray(POOL)
    private val velY = FloatArray(POOL)
    private val seedScale = FloatArray(POOL) // per-ember size jitter, 0.6..1.0

    /** Next slot to spawn into. Wraps — the pool is a ring. */
    private var cursor = 0

    /** Reused so a spawn allocates nothing. Deterministic for reproducible
     *  screenshots; the look does not depend on true randomness. */
    private val rng = Random(seed)

    /** Scratch for the per-frame vertex build: [x, y, r, g, b, u, v] per vertex,
     *  six vertices (two triangles) per ember. Sized for the whole pool. */
    private val scratch = FloatArray(POOL * VERTS_PER_EMBER * FLOATS_PER_VERTEX)
    private val vertexBuffer = ByteBuffer
        .allocateDirect(scratch.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    fun create() {
        program = GlProgram.build(VERTEX, FRAGMENT)
        scaleUniform = GLES30.glGetUniformLocation(program, "uScale")
        offsetUniform = GLES30.glGetUniformLocation(program, "uOffset")

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
            scratch.size * Float.SIZE_BYTES,
            null,
            GLES30.GL_STREAM_DRAW,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_POSITION)
        GLES30.glVertexAttribPointer(
            ATTRIB_POSITION, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, 0,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_COLOR)
        GLES30.glVertexAttribPointer(
            ATTRIB_COLOR, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, 2 * Float.SIZE_BYTES,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_UV)
        GLES30.glVertexAttribPointer(
            ATTRIB_UV, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, 5 * Float.SIZE_BYTES,
        )
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        reset()
    }

    /** Forget every live ember. Called when a session is (re)built so a burst
     *  from the previous well does not survive into the next. */
    fun reset() {
        spawnNanos.fill(0L)
        cursor = 0
    }

    /**
     * Spawn a burst of embers spread across a clearing band's full width.
     *
     * @param bandCenterY world Y of the clearing band's centre.
     * @param wellWidth well width in world units; embers spawn across `[0, w]`.
     * @param nowNanos the frame clock, so every ember in a burst shares an epoch.
     */
    fun spawn(bandCenterY: Float, wellWidth: Float, nowNanos: Long) {
        for (i in 0 until EMBERS_PER_BAND) {
            val slot = cursor
            cursor = (cursor + 1) % POOL

            spawnNanos[slot] = nowNanos
            // Spread across the width, jittered so they do not form a visible row.
            val frac = (i + rng.nextFloat()) / EMBERS_PER_BAND
            originX[slot] = frac * wellWidth
            originY[slot] = bandCenterY + (rng.nextFloat() - 0.5f) * 0.4f
            // Up-and-out: a positive vertical throw, a symmetric horizontal fan.
            velY[slot] = EmberTrajectory.MIN_VY +
                rng.nextFloat() * (EmberTrajectory.MAX_VY - EmberTrajectory.MIN_VY)
            velX[slot] = (rng.nextFloat() - 0.5f) * 2f * EmberTrajectory.MAX_VX
            seedScale[slot] = 0.6f + rng.nextFloat() * 0.4f
        }
    }

    /**
     * Advance every live ember to [nowNanos], build the vertex buffer, and draw
     * the burst additively with the renderer's world-to-clip [scale]/[offset].
     * A frame with no live embers draws nothing.
     */
    fun draw(nowNanos: Long, scale: FloatArray, offset: FloatArray) {
        var v = 0
        for (i in 0 until POOL) {
            val born = spawnNanos[i]
            if (born == 0L) continue
            val t = (nowNanos - born) / 1_000_000_000f
            if (t < 0f || t >= EmberTrajectory.LIFE_SECONDS) {
                spawnNanos[i] = 0L
                continue
            }

            // Analytic arc + fade — all the maths lives in EmberTrajectory so it
            // is unit-tested; this loop only turns it into geometry.
            val px = EmberTrajectory.x(originX[i], velX[i], t)
            val py = EmberTrajectory.y(originY[i], velY[i], t)
            val alpha = EmberTrajectory.alpha(t)
            val size = EmberTrajectory.size(seedScale[i], t)

            // Premultiply the amber by alpha so the additive blend fades the
            // ember out; the fragment's round falloff does the rest.
            val r = GLOW_R * alpha
            val g = GLOW_G * alpha
            val b = GLOW_B * alpha

            v = quad(v, px, py, size, r, g, b)
        }

        if (v == 0) return

        vertexBuffer.position(0)
        vertexBuffer.put(scratch, 0, v)
        vertexBuffer.position(0)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)

        GLES30.glUseProgram(program)
        GLES30.glUniform2f(scaleUniform, scale[0], scale[1])
        GLES30.glUniform2f(offsetUniform, offset[0], offset[1])

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v * Float.SIZE_BYTES, null, GLES30.GL_STREAM_DRAW)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, v * Float.SIZE_BYTES, vertexBuffer)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, v / FLOATS_PER_VERTEX)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** True while any ember is still alive, so the renderer knows whether the
     *  burst still needs drawing. Cheap: a scan of one Long array. */
    fun hasLiveEmbers(nowNanos: Long): Boolean {
        for (i in 0 until POOL) {
            val born = spawnNanos[i]
            if (born != 0L &&
                (nowNanos - born) / 1_000_000_000f < EmberTrajectory.LIFE_SECONDS
            ) return true
        }
        return false
    }

    /** Emit one ember's two triangles (six vertices) into [scratch] at [cursor],
     *  returning the new cursor. Each vertex is [x, y, r, g, b, u, v]. */
    private fun quad(
        cursor: Int, cx: Float, cy: Float, size: Float,
        r: Float, g: Float, b: Float,
    ): Int {
        var i = cursor
        fun vert(dx: Float, dy: Float) {
            scratch[i++] = cx + dx * size
            scratch[i++] = cy + dy * size
            scratch[i++] = r
            scratch[i++] = g
            scratch[i++] = b
            scratch[i++] = dx
            scratch[i++] = dy
        }
        vert(-1f, -1f); vert(1f, -1f); vert(1f, 1f)
        vert(-1f, -1f); vert(1f, 1f); vert(-1f, 1f)
        return i
    }

    private companion object {
        const val ATTRIB_POSITION = 0
        const val ATTRIB_COLOR = 1
        const val ATTRIB_UV = 2

        const val FLOATS_PER_VERTEX = 7 // x, y, r, g, b, u, v
        const val VERTS_PER_EMBER = 6
        const val STRIDE_BYTES = FLOATS_PER_VERTEX * Float.SIZE_BYTES

        /**
         * Ring capacity. `visual-direction.md` §7.2 asks for 8–16 embers per
         * band, sized for the max simultaneous burst across every band that
         * could clear at once. [EMBERS_PER_BAND] × a generous simultaneous-band
         * count, rounded up — a realistic clear is one to four bands, so this is
         * headroom, and bursts that exceed it degrade by overwriting the oldest
         * embers rather than by allocating.
         */
        const val POOL = 256
        const val EMBERS_PER_BAND = 14

        // Trajectory constants and the ember lifetime live in EmberTrajectory,
        // the pure, unit-tested half of this effect.

        // color-glow #FFB347 (docs/ux/tokens.md) — the reserved amber, the same
        // hue as the band-glow these embers escape from.
        const val GLOW_R = 1.0f
        const val GLOW_G = 0.702f
        const val GLOW_B = 0.278f

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec3 aColor;
layout(location = 2) in vec2 aUv;
uniform vec2 uScale;
uniform vec2 uOffset;
out vec3 vColor;
out vec2 vUv;
void main() {
    vColor = aColor;
    vUv = aUv;
    gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
}
"""

        // Round soft ember: a radial falloff on the corner UV, so an additive
        // quad reads as a glowing dot rather than a square. The colour already
        // carries the per-ember alpha (premultiplied on the CPU), so this is one
        // multiply on top of it.
        private const val FRAGMENT = """#version 300 es
precision mediump float;
in vec3 vColor;
in vec2 vUv;
out vec4 fragColor;
void main() {
    float falloff = smoothstep(1.0, 0.0, dot(vUv, vUv));
    fragColor = vec4(vColor * falloff, 1.0);
}
"""
    }
}
