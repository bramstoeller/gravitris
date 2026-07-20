package gravitris.app.gl

/**
 * The Stage 1 shader pair. **Flat colours only.**
 *
 * `docs/build-order.md` defers all procedural shading to Stage 3 deliberately,
 * and the reason is worth restating at the top of the file most likely to be
 * "improved" prematurely: the physics has to be judged first, without art
 * confusing the judgement. There is no gel, no subsurface, no rim light, no
 * grain and no band glow here, and adding any of them at this stage would
 * damage the Milestone 1 demo rather than help it.
 *
 * The fragment shader is therefore about as cheap as a fragment shader can be:
 * one flat varying, one uniform array lookup, one write. That is intentional
 * and it is also what makes the Stage 1 frame-time numbers meaningful — they
 * are the **floor**, the cost of geometry and overdraw with effectively no
 * per-pixel work. Stage 3's number, measured the same way on the same device,
 * minus this one, is the true price of the art direction. That subtraction is
 * the whole reason to measure now.
 */
object Shaders {

    /**
     * `vArchetype` is `flat` — no interpolation. This is literally
     * "flat-shaded triangles": the provoking vertex decides the colour of the
     * whole triangle, so no gradient can sneak in.
     *
     * World space reaches clip space through one scale and one offset rather
     * than a 4x4 matrix. The game is 2D with an axis-aligned camera, so a full
     * matrix would be 14 multiply-adds per vertex to compute values that are
     * structurally zero.
     */
    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in int aArchetype;

uniform vec2 uScale;
uniform vec2 uOffset;

flat out int vArchetype;

void main() {
    vArchetype = aArchetype;
    gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
}
"""

    /**
     * `mediump` is correct here and deliberate: the output is a flat palette
     * colour, so there is nothing whose precision could suffer, and `highp`
     * fragment work is measurably more expensive on the tile-based mobile GPUs
     * this product targets. Stage 3 must revisit this per-term rather than
     * promoting the whole shader.
     */
    const val FRAGMENT = """#version 300 es
precision mediump float;

flat in int vArchetype;

uniform vec3 uPalette[PALETTE_SIZE];

out vec4 fragColor;

void main() {
    fragColor = vec4(uPalette[vArchetype], 1.0);
}
"""

    /** Substitutes the palette size so the shader and [gravitris.app.Palette]
     *  cannot drift apart silently. */
    fun fragment(paletteSize: Int): String =
        FRAGMENT.replace("PALETTE_SIZE", paletteSize.toString())
}
