package gravitris.app.gl

/**
 * The Stage 1 shader pair. **Flat palette colour plus one compression term.**
 *
 * `docs/build-order.md` defers procedural shading to Stage 3 deliberately, and
 * the reason is worth restating at the top of the file most likely to be
 * "improved" prematurely: the physics has to be judged first, without art
 * confusing the judgement. There is no gel, no subsurface, no rim light, no
 * grain and no band glow here, and adding any of them at this stage would
 * damage the Milestone 1 demo rather than help it.
 *
 * ## The one exception, and its boundary
 *
 * Compressed material darkens. Approved by the Product Lead against the
 * milestone's own purpose: Milestone 1 exists to answer "does it feel heavy?",
 * the client answers largely through their eyes, and with a single flat colour
 * per body the interior deformation is invisible — only the silhouette carries
 * the squash, so the demo could not answer the question it exists to ask.
 *
 * ADR 0007 makes the same argument from the other direction: `vCompression` is
 * "the cheapest available route to the brief's requirement that the blocks
 * read as heavy", and it is a rendering response to a physical quantity rather
 * than an animation.
 *
 * **The boundary is explicit: compression to darkness, nothing else.** No rim
 * light, no gradient, no grain, no second term. Wanting one means this is
 * Stage 3 work and it should stop here.
 *
 * The base colour stays genuinely flat — `vArchetype` is a `flat` varying, so
 * the palette lookup is constant across each triangle. Only the physical
 * quantity is interpolated.
 *
 * ## Why it is toggleable
 *
 * `uCompressionGain` is set to zero to disable the term outright, so the same
 * device in the same session can be measured with and without it. Stage 3's
 * frame time minus Stage 1's is only the true price of the art direction if we
 * know what this one term already costs, and that subtraction is the reason to
 * measure at all. The zero case restores the true floor: one flat varying, one
 * uniform lookup, one write.
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
layout(location = 2) in float aCompression;

uniform vec2 uScale;
uniform vec2 uOffset;

flat out int vArchetype;
out float vCompression;

void main() {
    vArchetype = aArchetype;
    vCompression = aCompression;
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
in float vCompression;

uniform vec3 uPalette[PALETTE_SIZE];

// Strength of the compression darkening. Zero disables the term entirely,
// which is the measurement baseline — see the note on this object.
uniform float uCompressionGain;

// Ceiling on the darkening, so heavily compressed material stays legible as
// its own hue. Piece identity is carried by hue (docs/ux/piece-identity.md)
// and must survive deformation; letting this reach black would destroy the
// primary identity cue exactly where pieces pile up and need it most.
uniform float uCompressionMax;

out vec4 fragColor;

void main() {
    // Below 1 is compressed, above 1 is stretched. Only compression darkens:
    // brightening stretched material would be a lighting effect, which is
    // Stage 3's job and outside this term's approved boundary.
    float compression = max(0.0, 1.0 - vCompression);
    float darken = min(compression * uCompressionGain, uCompressionMax);
    fragColor = vec4(uPalette[vArchetype] * (1.0 - darken), 1.0);
}
"""

    /** Substitutes the palette size so the shader and [gravitris.app.Palette]
     *  cannot drift apart silently. */
    fun fragment(paletteSize: Int): String =
        FRAGMENT.replace("PALETTE_SIZE", paletteSize.toString())
}
