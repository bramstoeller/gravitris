package gravitris.app.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * The procedural environment: a graduated background that replaces the flat
 * `glClearColor(0,0,0,1)` the client saw as "gel blobs on a black screen"
 * (`docs/ux/visual-direction.md` §3).
 *
 * ## What it is, and what it deliberately is not
 *
 * One full-screen quad, drawn once per frame before the well frame and the
 * bodies, with a trivial fragment shader. Round 3 (`visual-direction.md` §19)
 * **recolours it to the light candy world** — the dark near-black gradient is
 * superseded, not patched: the client asked for "lighter and candy-like," a
 * reversal of the premise. Same architecture, new colour constants and one
 * arithmetic change (a straight vertical sweep instead of a centre-peaked one).
 * It is:
 *
 * - a **vertical gradient** — `color-sky-top` `#BFE9F7` (soft sky-blue) at the
 *   top of frame easing down to `color-sky-bottom` `#FDEFE0` (warm pale cream)
 *   at the bottom, so the field reads as a bright candy world lit from above;
 * - **two fixed soft warm patches** (sun-through-haze) at the upper-left and
 *   lower-right, each a `smoothstep`-falloff disc drifting very slowly (~100s
 *   period) so the environment breathes without ever pulling the eye. The drift
 *   offset is the same for every pixel, so its `sin`/`cos` are computed once per
 *   frame on the CPU (see [draw]) and passed in as uniforms, not per pixel.
 *   **Their peak brightness is owed an on-device re-measurement** (§19): a
 *   bright additive patch on an already-bright field under-reads the opposite
 *   way the old dark-on-dark glows did — sane first-pass values, not final.
 *
 * ## Cost
 *
 * This is the single most expensive *new* thing in the visual pass, because it
 * is the only one whose cost is **O(screen pixels)** — a full-screen fill on a
 * mobile GPU on top of everything else — rather than O(bodies) or O(1). So the
 * fragment shader is kept to a handful of ALU ops and **no `sqrt`**: the radial
 * falloff is a `smoothstep` on squared distance, which is a soft blob either
 * way and saves a transcendental per pixel; the glow-drift `sin`/`cos` are
 * likewise kept out of the fragment (computed once per frame on the CPU), so
 * the whole full-screen pass runs **zero transcendentals per pixel**.
 * `visual-direction.md` §10 flags
 * this pass explicitly for on-device measurement — the shade dial's frame-time
 * readout is how its real cost gets priced on the client's device, not the
 * software emulator.
 *
 * Opaque, blending off: it covers the whole surface, so it establishes the
 * frame's base colour directly and nothing behind it needs to show through.
 * Its own flat program — pushing a full-screen quad through the gel shader
 * would mean carrying attributes to cancel every material term.
 */
class Background {

    private var program = 0
    private var aspectUniform = -1
    private var driftAUniform = -1
    private var driftBUniform = -1

    private var vao = 0
    private var vbo = 0

    /** Build the program and the static full-screen quad. Safe to call again
     *  after context loss (ADR 0010 §6) — everything is recreated from source. */
    fun create() {
        program = GlProgram.build(VERTEX, FRAGMENT)
        aspectUniform = GLES30.glGetUniformLocation(program, "uAspect")
        driftAUniform = GLES30.glGetUniformLocation(program, "uDriftA")
        driftBUniform = GLES30.glGetUniformLocation(program, "uDriftB")

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        vbo = buffers[0]

        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]

        // A full-screen triangle strip in clip space, with the 0..1 UV that the
        // gradient and the glows are positioned in. Static — the quad never
        // moves — so it is uploaded once here and never touched again.
        val verts = floatArrayOf(
            //  x,    y,    u,   v
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
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
        GLES30.glVertexAttribPointer(
            ATTRIB_POSITION, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, 0,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_UV)
        GLES30.glVertexAttribPointer(
            ATTRIB_UV, 2, GLES30.GL_FLOAT, false, STRIDE_BYTES, 2 * Float.SIZE_BYTES,
        )
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Draw the environment. Must be called first in the frame, after `glClear`
     * and before the well frame and bodies — it is the base layer everything
     * else is painted over.
     *
     * @param aspect surface width / height, so the radial glows stay circular
     *   rather than stretching with the panel's portrait aspect.
     * @param timeSeconds the renderer's wrapped shader clock, for the slow drift.
     */
    fun draw(aspect: Float, timeSeconds: Float) {
        // The two glow-drift offsets depend only on time, so they are the same
        // for every pixel in the frame. Computing the sin/cos here — once per
        // frame on the CPU — instead of in the fragment shader keeps the four
        // transcendentals out of the O(screen-pixels) full-screen pass, where
        // they would otherwise run ~2.6M times per frame for a value that never
        // varies across the surface. Pure cost move: the offsets, and so the
        // rendered result, are identical to computing them per pixel.
        val phase = timeSeconds * DRIFT_RATE
        val driftAx = sin(phase) * DRIFT_AMP
        val driftAy = cos(phase) * DRIFT_AMP
        val driftBx = cos(phase + DRIFT_PHASE_B) * DRIFT_AMP
        val driftBy = sin(phase + DRIFT_PHASE_B) * DRIFT_AMP

        GLES30.glUseProgram(program)
        GLES30.glUniform1f(aspectUniform, aspect)
        GLES30.glUniform2f(driftAUniform, driftAx, driftAy)
        GLES30.glUniform2f(driftBUniform, driftBx, driftBy)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    private companion object {
        const val ATTRIB_POSITION = 0
        const val ATTRIB_UV = 1
        const val STRIDE_BYTES = 4 * Float.SIZE_BYTES

        // Slow glow drift, computed CPU-side in draw() and passed as uDriftA/
        // uDriftB. ~100s period as an angular rate, tiny amplitude — the
        // environment breathes without ever pulling the eye. The two discs
        // drift on opposite diagonals (B's phase offset) so the pair never
        // reads as a single moving light. Cheap enough to keep, first to cut if
        // the on-device budget disagrees (visual-direction.md §3).
        const val DRIFT_RATE = 0.0628318f // 2*PI / 100s
        const val DRIFT_AMP = 0.03f
        const val DRIFT_PHASE_B = 1.7f

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aUv;
out vec2 vUv;
void main() {
    vUv = aUv;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
"""

        // Colours are the tokens.md environment values, inlined as constants
        // because this is a separate program from the gel shader and does not
        // share its palette uniform. Each carries the hex it came from in a
        // comment for review; the gradient stops are exact, and the glow tints
        // are documented against tokens.md §Colour (see the glow constants).
        private const val FRAGMENT = """#version 300 es
precision mediump float;

in vec2 vUv;

uniform float uAspect; // width / height, to keep the glows circular
uniform vec2 uDriftA;  // glow-A drift offset, computed once per frame on the CPU
uniform vec2 uDriftB;  // glow-B drift offset, computed once per frame on the CPU

out vec4 fragColor;

// Light candy world (tokens.md, §19), replacing the superseded near-black
// dark-canvas gradient. A soft sky-blue overhead warming to a pale cream at the
// bottom, so the vertical sweep reads as a light source above rather than a
// flat tint.
// color-sky-top #BFE9F7 — top of frame.
const vec3 SKY_TOP = vec3(0.749020, 0.913725, 0.968627);
// color-sky-bottom #FDEFE0 — bottom of frame.
const vec3 SKY_BOTTOM = vec3(0.992157, 0.937255, 0.878431);
// The two soft warm patches (tokens.md color-sky-glow-a/-b: sun-through-haze,
// NOT the dark direction's "crystal light"). These are the peak colour ADDED at
// each disc's centre. **On-device tuning owed (§19):** the round-2 additive-
// onto-near-black trick does NOT transfer — against an already-bright field a
// near-white add barely reads, but a large one blooms to a white blob. These
// are sane first-pass warm-white lifts (a soft brightening toward white at each
// centre), not measured on the real panel; the same failure mode the team hit
// once in the opposite direction, flagged loudly rather than guessed precisely.
const vec3 GLOW_A = vec3(0.060, 0.055, 0.040); // upper-left, warm sun-haze
const vec3 GLOW_B = vec3(0.055, 0.048, 0.036); // lower-right, warm sun-haze

// Radius of each disc, squared (the falloff runs on squared distance to avoid a
// per-pixel sqrt). ~0.28 in aspect-corrected units — a disc that brightens its
// own corner and falls to nothing well before mid-screen, so it reads as a
// localized "distant crystal light" rather than a broad wash over the whole
// field. A larger radius (the first pass used 0.42 ≈ 0.65 radius) covers the
// entire screen and blends invisibly into the vertical gradient.
const float GLOW_RADIUS2 = 0.08;

/**
 * The same R2 low-discrepancy ordered dither the gel shader uses, and for the
 * same reason: a smooth low-intensity gradient through near-black values bands
 * visibly on an 8-bit surface (tokens.md's banding note applies to this
 * gradient's own slow sweep, not only to the glow ramp). highp is mandatory —
 * gl_FragCoord reaches ~2400 on this panel and the dot exceeds 3000, which
 * mediump's 10-bit mantissa cannot resolve.
 */
float dither() {
    highp vec2 p = gl_FragCoord.xy;
    return fract(dot(p, vec2(0.7548776662, 0.5698402909))) - 0.5;
}

// Soft radial falloff of one disc (0 at the rim, 1 at the centre), on squared
// distance. The caller multiplies by the disc's tint, which carries the peak.
float disc(vec2 uv, vec2 center) {
    vec2 d = uv - center;
    d.x *= uAspect; // circular in screen space despite the portrait panel
    return smoothstep(GLOW_RADIUS2, 0.0, dot(d, d));
}

void main() {
    // Vertical gradient (§19): sky-blue at the top of frame (vUv.y = 1) easing
    // down to warm cream at the bottom (vUv.y = 0) — a straight overhead-light
    // sweep, not the dark theme's centre-peaked warm core. smoothstep, not a
    // raw lerp, so neither stop forms a visible band edge.
    vec3 color = mix(SKY_BOTTOM, SKY_TOP, smoothstep(0.0, 1.0, vUv.y));

    // Two fixed discs, drifting on opposite diagonals so the pair never reads
    // as a single moving light. The drift offsets are frame-constant (they
    // depend only on time), so they are computed once per frame on the CPU and
    // arrive as uniforms — no sin/cos in this full-screen fragment.
    color += GLOW_A * disc(vUv, vec2(0.28, 0.74) + uDriftA);
    color += GLOW_B * disc(vUv, vec2(0.74, 0.24) + uDriftB);

    // 1.4/255 peak-to-peak, one 8-bit code value — same amplitude as the gel
    // shader's dither (Tunables.DITHER_GAIN), applied last so the darkening
    // above cannot scale it away.
    color += dither() * (1.4 / 255.0);

    fragColor = vec4(color, 1.0);
}
"""
    }
}
