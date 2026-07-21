package gravitris.app.gl

import android.opengl.GLES30
import gravitris.app.Tunables

/**
 * The soft contact shadow under every piece (`docs/ux/visual-direction.md` §18).
 *
 * A soft, dark, tray-coloured shadow directly beneath each piece is a big part
 * of why real candy reads as *sitting in a world* rather than floating on it —
 * the client named "schaduwen" explicitly. This is that, done the cheap, honest
 * way the rest of this renderer already uses (`Background`/`WellFrame`
 * establish the pattern: a second small program alongside the gel shader).
 *
 * ## Mechanism, and what it deliberately is not
 *
 * A **second draw pass of the body geometry that already exists**, drawn after
 * the well frame and before the real bodies, so each piece paints over its own
 * shadow. It reuses [BodyMesh]'s exact vertex buffer and index buffer — this
 * class owns no geometry at all. [use] binds this program and its uniforms; the
 * caller then invokes `BodyMesh.draw()`, which binds the mesh's VAO and issues
 * the draw through whatever program is currently active. The shadow shader only
 * references `aPosition` (location 0) and `aEdge` (location 5); the mesh's other
 * enabled attribute arrays are simply ignored by it.
 *
 * The vertex shader applies a fixed world-space offset (down + slightly right,
 * `shadow-offset-piece`) before the shared world→clip transform, so the shadow
 * lands beneath the piece and scales with it, not the screen. The fragment
 * shader is far cheaper than the gel material: a flat `color-shadow`
 * (a darkened *tray* tone, never pure black — a black shadow on a saturated
 * candy world reads as a hole) at a low constant alpha, softened at its own
 * outline using the existing `vEdge` varying so the silhouette feathers rather
 * than cutting off as a hard duplicate outline.
 *
 * **This is the one pass in the whole renderer that turns `GL_BLEND` on**, and
 * only for itself: a single low-complexity pass with a trivial fragment shader,
 * not a general-purpose transparency system competing with the main material
 * every frame. [use] does not touch blend state — the caller enables it around
 * this pass and restores the renderer's global "blend off" afterward, exactly
 * as the ember/flash passes already manage their own additive blending.
 *
 * ## What it does not do (a first-pass honesty note)
 *
 * §18 also suggests a small outward scale from each body's own centroid, to
 * fake a blurred (slightly larger) silhouette. That needs a per-body centroid
 * the vertex shader is not given — the mesh carries no centroid attribute — so
 * this pass uses offset + an edge-feathered alpha instead, which removes the
 * hard duplicate-outline read without new data. If the shadow wants more of a
 * soft blurred spread on-device, a centroid attribute is the follow-up, flagged
 * rather than hacked in.
 *
 * ## Cost (§18, named plainly)
 *
 * Roughly doubles the body geometry's per-frame vertex throughput (two draws of
 * the same vertex count) and adds one blend-enabled draw with its own state
 * change. The fragment cost of the shadow itself is far below the gel material
 * (a handful of ops), but the vertex cost and the blend state change are real
 * and stack with §17's MSAA and §19's full-screen background — the three
 * together are this round's frame-budget risk, priced only on the client's
 * phone.
 */
class PieceShadow {

    private var program = 0
    private var scaleUniform = -1
    private var offsetUniform = -1
    private var shadowOffsetUniform = -1
    private var colorUniform = -1
    private var alphaUniform = -1

    /** Build the program. Safe to call again after context loss (ADR 0010 §6) —
     *  no geometry of its own, so this is the whole of its GL state. */
    fun create() {
        program = GlProgram.build(VERTEX, FRAGMENT)
        scaleUniform = GLES30.glGetUniformLocation(program, "uScale")
        offsetUniform = GLES30.glGetUniformLocation(program, "uOffset")
        shadowOffsetUniform = GLES30.glGetUniformLocation(program, "uShadowOffset")
        colorUniform = GLES30.glGetUniformLocation(program, "uShadowColor")
        alphaUniform = GLES30.glGetUniformLocation(program, "uShadowAlpha")
    }

    /**
     * Bind this program and push its uniforms, ready for a `BodyMesh.draw()`.
     *
     * [scale] and [offset] are the same world→clip scale/offset the body program
     * uses this frame; the shadow rides the identical transform so it stays
     * registered with the piece under any layout.
     */
    fun use(scale: FloatArray, offset: FloatArray) {
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(scaleUniform, scale[0], scale[1])
        GLES30.glUniform2f(offsetUniform, offset[0], offset[1])
        GLES30.glUniform2f(shadowOffsetUniform, Tunables.SHADOW_OFFSET_X, Tunables.SHADOW_OFFSET_Y)
        GLES30.glUniform3f(colorUniform, Tunables.SHADOW_R, Tunables.SHADOW_G, Tunables.SHADOW_B)
        GLES30.glUniform1f(alphaUniform, Tunables.SHADOW_ALPHA)
    }

    private companion object {
        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 5) in float aEdge;

uniform vec2 uScale;
uniform vec2 uOffset;
uniform vec2 uShadowOffset; // world units, down + slightly right

out float vEdge;

void main() {
    vEdge = aEdge;
    // Offset in world space before the shared world->clip transform, so the
    // shadow scales with the piece rather than the screen.
    gl_Position = vec4((aPosition + uShadowOffset) * uScale + uOffset, 0.0, 1.0);
}
"""

        private const val FRAGMENT = """#version 300 es
precision mediump float;

in float vEdge;

uniform vec3 uShadowColor; // color-shadow: a darkened TRAY tone, never black
uniform float uShadowAlpha;

out vec4 fragColor;

void main() {
    // Feather the shadow's own outline so it reads as soft, not as a hard
    // duplicate silhouette. vEdge ramps 1 at the outer boundary to 0 interior;
    // fading alpha out across the outermost part of that ring softens the edge.
    float soft = 1.0 - smoothstep(0.55, 1.0, vEdge);
    fragColor = vec4(uShadowColor, uShadowAlpha * soft);
}
"""
    }
}
