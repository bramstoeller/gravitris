package gravitris.app.gl

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
 *   disc at 4–8% peak opacity, drifting very slowly (`sin(uTime)`, ~100s
 *   period) so the environment breathes without ever pulling the eye.
 *
 * ## Cost
 *
 * This is the single most expensive *new* thing in the visual pass, because it
 * is the only one whose cost is **O(screen pixels)** — a full-screen fill on a
 * mobile GPU on top of everything else — rather than O(bodies) or O(1). So the
 * fragment shader is kept to a handful of ALU ops and **no `sqrt`**: the radial
 * falloff is a `smoothstep` on squared distance, which is a soft blob either
 * way and saves a transcendental per pixel. `visual-direction.md` §10 flags
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
    private var timeUniform = -1

    private var vao = 0
    private var vbo = 0

    /** Build the program and the static full-screen quad. Safe to call again
     *  after context loss (ADR 0010 §6) — everything is recreated from source. */
    fun create() {
        program = GlProgram.build(VERTEX, FRAGMENT)
        aspectUniform = GLES30.glGetUniformLocation(program, "uAspect")
        timeUniform = GLES30.glGetUniformLocation(program, "uTime")

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
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(aspectUniform, aspect)
        GLES30.glUniform1f(timeUniform, timeSeconds)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    private companion object {
        const val ATTRIB_POSITION = 0
        const val ATTRIB_UV = 1
        const val STRIDE_BYTES = 4 * Float.SIZE_BYTES

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
        // share its palette uniform. Kept in sync with docs/ux/tokens.md §Colour
        // by BackgroundColoursTest, which re-derives each triple from its hex.
        private const val FRAGMENT = """#version 300 es
precision mediump float;

in vec2 vUv;

uniform float uAspect; // width / height, to keep the glows circular
uniform float uTime;   // seconds, wrapped by the CPU (Tunables.SHADER_TIME_WRAP_SECONDS)

out vec4 fragColor;

// color-bg-deep #05050C — top and bottom of frame.
const vec3 BG_DEEP = vec3(0.019608, 0.019608, 0.047059);
// color-bg-core #0E1730 — vertical centre of frame.
const vec3 BG_CORE = vec3(0.054902, 0.090196, 0.188235);
// color-bg-glow-a #0E1730 — upper-left disc.
const vec3 GLOW_A = vec3(0.054902, 0.090196, 0.188235);
// color-bg-glow-b #241B3D — lower-right disc.
const vec3 GLOW_B = vec3(0.141176, 0.105882, 0.239216);

// Peak opacities, inside tokens.md's 4-8% band. Deliberately faint: the
// environment must never compete with the gel or the amber glow for the eye.
const float GLOW_A_PEAK = 0.06;
const float GLOW_B_PEAK = 0.05;

// Radius of each disc, squared, since the falloff runs on squared distance to
// avoid a per-pixel sqrt.
const float GLOW_RADIUS2 = 0.42;

// Slow drift: ~100s period as an angular rate, tiny amplitude. One sine per
// glow — cheap enough to keep, first to cut if the budget disagrees
// (visual-direction.md §3).
const float DRIFT_RATE = 0.0628318; // 2*PI / 100s
const float DRIFT_AMP = 0.03;

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

// Additive contribution of one soft radial disc, falloff on squared distance.
float disc(vec2 uv, vec2 center, float peak) {
    vec2 d = uv - center;
    d.x *= uAspect; // circular in screen space despite the portrait panel
    return peak * smoothstep(GLOW_RADIUS2, 0.0, dot(d, d));
}

void main() {
    // Vertical gradient: brightest (core) at the centre, deep at both ends.
    // smoothstep, not a raw lerp, so the warm centre eases in rather than
    // forming a visible band edge.
    float t = 1.0 - abs(vUv.y * 2.0 - 1.0);
    vec3 color = mix(BG_DEEP, BG_CORE, smoothstep(0.0, 1.0, t));

    // Two fixed discs, drifting on opposite diagonals so the pair never reads
    // as a single moving light.
    vec2 driftA = vec2(sin(uTime * DRIFT_RATE), cos(uTime * DRIFT_RATE)) * DRIFT_AMP;
    vec2 driftB = vec2(cos(uTime * DRIFT_RATE + 1.7), sin(uTime * DRIFT_RATE + 1.7)) * DRIFT_AMP;
    color += GLOW_A * disc(vUv, vec2(0.28, 0.74) + driftA, GLOW_A_PEAK);
    color += GLOW_B * disc(vUv, vec2(0.74, 0.24) + driftB, GLOW_B_PEAK);

    // 1.4/255 peak-to-peak, one 8-bit code value — same amplitude as the gel
    // shader's dither (Tunables.DITHER_GAIN), applied last so the darkening
    // above cannot scale it away.
    color += dither() * (1.4 / 255.0);

    fragColor = vec4(color, 1.0);
}
"""
    }
}
