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
 * bodies, with a trivial fragment shader. It is:
 *
 * - a **vertical gradient** — `color-bg-deep` `#05050C` at the top and bottom
 *   of frame, warming very slightly to `color-bg-core` `#0E1730` at the
 *   vertical centre — so the field reads as a world rather than a dead value,
 *   while staying overwhelmingly dark (mean luminance barely above true black,
 *   preserving the OLED-power and glow-contrast arguments in `tokens.md`);
 * - **two fixed soft radial glows** ("distant crystal light," not
 *   representational) at the upper-left and lower-right, cool hues only —
 *   **never amber**, that is band-glow's alone — each a `smoothstep`-falloff
 *   disc at 4–8% peak opacity, drifting very slowly (~100s period) so the
 *   environment breathes without ever pulling the eye. The drift offset is the
 *   same for every pixel, so its `sin`/`cos` are computed once per frame on the
 *   CPU (see [draw]) and passed in as uniforms, not evaluated per pixel.
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
 * Its own flat program, like [UrgencyBar] — pushing a full-screen quad through
 * the gel shader would mean carrying attributes to cancel every material term.
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

// color-bg-deep #05050C — top and bottom of frame.
const vec3 BG_DEEP = vec3(0.019608, 0.019608, 0.047059);
// color-bg-core #0E1730 — vertical centre of frame.
const vec3 BG_CORE = vec3(0.054902, 0.090196, 0.188235);
// The two "distant crystal light" discs (tokens.md color-bg-glow-a/-b): a cool
// TEAL upper-left, a cool VIOLET lower-right — never amber. These are the peak
// colour ADDED at each disc's centre, and their luminance is ~4-5% of white,
// which is tokens.md's "4-8% peak opacity" read as brightness toward a light
// rather than an alpha-blend toward a colour. The token hues #0E1730/#241B3D are
// near-black, so adding them (or blending toward them) produces no visible
// brightening at all — these lift the same cool hues to where the disc actually
// reads as a soft light while staying deep and cool.
const vec3 GLOW_A = vec3(0.020, 0.048, 0.072); // upper-left, deep teal
const vec3 GLOW_B = vec3(0.052, 0.030, 0.082); // lower-right, deep violet

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
    // Vertical gradient: brightest (core) at the centre, deep at both ends.
    // smoothstep, not a raw lerp, so the warm centre eases in rather than
    // forming a visible band edge.
    float t = 1.0 - abs(vUv.y * 2.0 - 1.0);
    vec3 color = mix(BG_DEEP, BG_CORE, smoothstep(0.0, 1.0, t));

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
