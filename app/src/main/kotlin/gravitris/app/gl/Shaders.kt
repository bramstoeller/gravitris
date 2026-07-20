package gravitris.app.gl

/**
 * The Stage 3B shader pair. **Procedural gel/rubber shading, no texture assets.**
 *
 * Stage 1 was one flat palette colour plus a compression term, deliberately, so
 * the physics could be judged without art confusing the judgement. That
 * question has been answered on the client's device — the deformation is
 * visible and the bodies touch — so this file is now the art direction, built
 * from `docs/ux/piece-identity.md`, `docs/ux/band-glow.md` and ADR 0007's
 * varying contract.
 *
 * ## The constraint that shaped every line below
 *
 * The client's device measures **15.0 ms mean, 24.5 p95, 66.5 fps at 17
 * bodies** with a nearly-flat shader, against a 16.67 ms budget. Fragment cost
 * is the entire remaining performance risk and there is very little of it left.
 * So this is not a physically-motivated shading model, and it deliberately is
 * not the best-looking one that could be written: every term here is the
 * cheapest approximation that still reads, and terms that could not be made
 * cheap were not written at all.
 *
 * Concretely, and each of these is a decision rather than an oversight:
 *
 * - **No loops, no branches on per-fragment data.** The only control flow is on
 *   [uShadeTier] and `vArchetype`, both uniform-or-flat across an entire draw
 *   call, so every fragment in a warp takes the same path.
 * - **No `pow`, no `exp`, no normalize, no matrix.** Rim falloff is a cubic
 *   written as two multiplies.
 * - **One transcendental pair for the grain and one for the pulse**, not a
 *   multi-octave noise. A proper value-noise lookup is four hashes and a
 *   bilinear blend — roughly 50 ALU ops — which this budget cannot buy.
 * - **The dither is three ops**, not a hash. See [DITHER] below.
 * - **`mediump` everywhere except the two places it is provably wrong**, which
 *   are marked `highp` individually. Stage 1's note said "Stage 3 must revisit
 *   this per-term rather than promoting the whole shader"; this is that,
 *   honoured literally.
 *
 * ## The tiers exist to be cut
 *
 * [uShadeTier] selects how much of the art direction runs, and it is a runtime
 * uniform rather than a compile-time variant precisely so the client's device
 * can walk down it in one session and report a frame time for each step. **This
 * is the cut list, made executable rather than written in prose:** if the
 * measured cost overruns, the response is to lower the shipped default tier,
 * and the tiers are ordered so that the cheapest, highest-legibility terms
 * survive longest.
 *
 * | tier | adds | why it is at this position |
 * | ---- | ---- | -------------------------- |
 * | 0 | flat colour + compression darkening | The Stage 1 baseline, bit-identical. The measurement floor. |
 * | 1 | contact seam, rim light, subsurface, dither | The legibility tier. The seam is `piece-identity.md`'s **primary** small-screen boundary cue, ranked there above the lightness ladder. Cut this and squashed pieces stop being separable. |
 * | 2 | grain | Identity tier. Grain scale is the CVD/monochrome backup cue, and `piece-identity.md` already calls it "the most at-risk cue on this screen size" — so it is the first thing that should go. |
 * | 3 | band glow | Not yet load-bearing: the band fill it reads is Stage 3A and does not exist, so at Stage 3B this tier renders a debug sweep. It is last because it is the only tier whose *absence* costs the player nothing today. |
 *
 * ## What is deliberately not here
 *
 * No bloom and no HDR post-process — ADR 0007 §7 and `band-glow.md` both
 * confirm the glow is faked in this shader with colour math and nothing else.
 * No landing silhouette, no clear/dissolve animation, no scoring: Stage 3A and
 * Stage 4.
 */
object Shaders {

    /**
     * ADR 0007's varying contract, in full, for the first time.
     *
     * Stage 1 carried two of the six. The other four are all sourced from
     * `SimState` rather than re-derived here — `particleU`/`particleV`,
     * `particleEdge` and `particleContact` are already published by `:core-sim`
     * and populated by the solver, so the shell never has to reconstruct the
     * lattice mapping and cannot drift from the core's definition of them.
     *
     * `vArchetype` stays `flat` — the palette lookup and the grain scale are
     * constant across a triangle, and only physical quantities interpolate.
     *
     * `vWorldPos` is passed through rather than reconstructed from
     * `gl_FragCoord`, because reconstructing it would mean inverting the
     * scale/offset in the fragment stage — the same arithmetic, done per
     * fragment instead of per vertex.
     *
     * World space still reaches clip space through one scale and one offset
     * rather than a 4x4 matrix: the game is 2D with an axis-aligned camera, so
     * a full matrix would be 14 multiply-adds per vertex to compute values that
     * are structurally zero.
     */
    const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in int aArchetype;
layout(location = 2) in float aCompression;
layout(location = 3) in float aContact;
layout(location = 4) in vec2 aBodyUv;
layout(location = 5) in float aEdge;

uniform vec2 uScale;
uniform vec2 uOffset;

flat out int vArchetype;
out float vCompression;
out float vContact;
out float vEdge;
out vec2 vBodyUv;
out vec2 vWorldPos;

void main() {
    vArchetype = aArchetype;
    vCompression = aCompression;
    vContact = aContact;
    vEdge = aEdge;
    vBodyUv = aBodyUv;
    vWorldPos = aPosition;
    gl_Position = vec4(aPosition * uScale + uOffset, 0.0, 1.0);
}
"""

    /**
     * The gel material.
     *
     * Read the term order in `main` as the physical story it is imitating:
     * light enters the material, scatters through its depth (subsurface),
     * is blocked where another body presses against it (contact seam), grazes
     * off the free surface on the way out (rim), is modulated by the material's
     * own internal structure (grain), is absorbed more where the material is
     * compressed (the Stage 1 term, preserved), and finally has the band's
     * warmth added to it from the outside (glow). Each of those is one or two
     * lines.
     */
    const val FRAGMENT = """#version 300 es
precision mediump float;

flat in int vArchetype;
in float vCompression;
in float vContact;
in float vEdge;
in vec2 vBodyUv;
in vec2 vWorldPos;

uniform vec3 uPalette[PALETTE_SIZE];

// Per-archetype grain frequency multiplier, docs/ux/piece-identity.md's
// tertiary identity cue. In the same indexed-by-archetype form as the palette
// and for the same reason ADR 0007 gives: a seventh piece must not need a
// shader change.
uniform float uGrainScale[PALETTE_SIZE];

// How much of the art direction runs. See the tier table on this object.
uniform int uShadeTier;

// Seconds, wrapped by the CPU well before mediump loses whole-number
// resolution. Only the glow's pulse and shimmer read it; nothing else here
// animates, which is why the grain does not crawl.
uniform float uTime;

// --- Stage 1's term, preserved verbatim ---------------------------------
uniform float uCompressionGain;
uniform float uCompressionMax;

// --- Stage 3B gel terms --------------------------------------------------
uniform float uSubsurfaceGain;
uniform float uSubsurfaceSaturate;
uniform float uSubsurfaceDarken;
uniform float uContactGain;
uniform float uRimGain;
uniform float uGrainGain;
uniform float uGrainFrequency;
uniform float uDitherGain;

// --- band glow (docs/ux/band-glow.md) ------------------------------------
uniform float uBandFill[BAND_COUNT];
// -1 = not clearing, else 0..1 progress through the clear envelope. Separate
// from the fill because a band at fill 1.0 and a band mid-dissolve are
// indistinguishable from fill alone (ADR 0007 §5).
uniform float uBandClearProgress[BAND_COUNT];
uniform float uBandBottomY;
uniform float uBandInvHeight;
uniform float uGlowGain;
uniform float uGlowCapRatio;
uniform float uIgnitionCapRatio;
uniform vec3 uIgnitionColor;
uniform float uPulseRateSlow;
uniform float uPulseRateFast;
uniform float uPulseAmplitude;
uniform float uShimmerGain;

out vec4 fragColor;

// Rec.601 luma. Used only to build the subsurface deep tone and to cap the
// glow; it is not a colour-space conversion and nothing here is gamma-correct.
const vec3 LUMA = vec3(0.299, 0.587, 0.114);

// docs/ux/piece-identity.md: the rim is a FIXED neutral cool white, never
// tinted per piece. A coloured rim would shift the apparent hue at exactly the
// edges where players read piece boundaries, and hue is the primary identity
// cue.
const vec3 RIM_COLOR = vec3(0.82, 0.88, 1.0);

// color-glow #FFB347. Reserved — never a piece hue (docs/ux/tokens.md).
const vec3 GLOW_COLOR = vec3(1.0, 0.702, 0.278);

const float TWO_PI = 6.2831853;

// Half-width of the band boundary feather, in band heights. 0.07 either side
// makes the transition ~14% of a band tall, inside band-glow.md's "roughly
// 10-15%". See bandFillAt.
const float BAND_FEATHER = 0.07;

/**
 * Low-discrepancy ordered dither, three ops.
 *
 * docs/ux/tokens.md and band-glow.md both call out the same risk: against
 * color-bg #000000 on an OLED panel, a slow sweep through near-black values
 * bands visibly on an 8-bit surface, and this shader has two such sweeps — the
 * subsurface/AO darkening and the glow's 0.0 -> 0.15 climb. Both specs say to
 * fix it by dithering with the existing procedural noise field rather than
 * building a second dithering system.
 *
 * This is that fix, with one deliberate substitution. A hash of gl_FragCoord
 * (Hoskins-style, fract/dot/fract) is the usual answer and costs ~12 ops. The
 * R2 low-discrepancy sequence below — fract of a dot with the plastic
 * constant's reciprocals — costs a dot and a fract, distributes more evenly
 * than a hash at this amplitude, and is what the dither actually needs. At
 * roughly a quarter of the price it is the right trade for a shader whose
 * entire problem is fill cost.
 *
 * It is static in time, not animated: a crawling dither at these amplitudes
 * reads as sensor noise on a dark panel, which is the artefact rather than the
 * fix. The shimmer that band-glow.md wants animated is a separate term.
 *
 * highp is mandatory. gl_FragCoord reaches ~2400 on this panel and the dot
 * product exceeds 3000; at mediump's 10-bit mantissa the fract of that
 * quantises to a handful of distinct values and the dither becomes a visible
 * grid.
 */
float dither() {
    highp vec2 p = gl_FragCoord.xy;
    return fract(dot(p, vec2(0.7548776662, 0.5698402909))) - 0.5;
}

/**
 * The gel's internal mottling.
 *
 * Two sine products on the body's own UV, which is an interference pattern
 * rather than true noise. It is regular if you go looking for it, and a
 * multi-octave value noise would look better — that is the honest trade, and it
 * was made on cost: this is ~14 ops against ~50 for the cheapest real noise,
 * and the dither above breaks up the regularity at the pixel level.
 *
 * Scaled by the per-archetype grain frequency, so the pattern's size is
 * piece-identity.md's tertiary identity cue. Sampled in body UV rather than
 * world space so the grain squashes with the material — a gel's internal
 * structure deforms with it, and a world-space grain would read as the piece
 * sliding underneath a fixed pattern.
 */
float mottle(vec2 uv) {
    return sin(uv.x * 3.1 + uv.y * 1.7) * sin(uv.y * 2.6 - uv.x * 1.1);
}

/**
 * Band fill at a world height: flat across the body of each band, feathered
 * only near the boundaries.
 *
 * ## Why this is not a plain interpolation between band centres
 *
 * That was the first implementation, and it was wrong. ADR 0007 predicts
 * "interpolate between adjacent band fills" as the cheap fix for the 20-band
 * quantisation, which is true as far as it goes, but band-glow.md is more
 * specific about how much: *"Feather the emissive mask at the top/bottom of
 * each band by roughly 10-15% of band height (soft falloff, not a hard
 * cutoff)."*
 *
 * A straight lerp between band centres feathers across the **entire** band
 * height — roughly seven times what the spec asks — which leaves no flat
 * region anywhere and turns the whole well into one smooth vertical gradient.
 * That matters because of a number from the backend engineer: a band is 1.0
 * world unit tall and a piece is 2.40, so **a single piece spans about three
 * bands**. Over-feathered, those three values blur into a gradient across the
 * piece and the horizontal zone — which is the entire signal — stops being
 * legible as a zone at all.
 *
 * So: blend to the neighbouring band only across [BAND_FEATHER] of band height
 * either side of a boundary, reaching an exact half-and-half mix at the
 * boundary itself so the result stays continuous. Everywhere else the band
 * reads its own value flat, and the banding is crisp without being a hard
 * cutoff — which would read as a debug overlay or a HUD line, and the client
 * rejected HUD chrome.
 *
 * Sampled per fragment, from an interpolated world position, never per vertex.
 * Per-vertex sampling across a piece 2.4 bands tall would smear three bands'
 * values through the rasteriser and destroy the same signal from the other
 * direction.
 */
float bandFillAt(float worldY) {
    float b = (worldY - uBandBottomY) * uBandInvHeight;
    float index = floor(b);
    float offset = b - index - 0.5; // -0.5..0.5, zero at the band's centre

    float last = float(BAND_COUNT - 1);
    float here = clamp(index, 0.0, last);
    // step() rather than a ternary: the neighbour is the band the fragment is
    // leaning towards, and branchless keeps the warp coherent.
    float there = clamp(index + (step(0.0, offset) * 2.0 - 1.0), 0.0, last);

    float weight = 0.5 * smoothstep(0.5 - BAND_FEATHER, 0.5, abs(offset));
    return mix(uBandFill[int(here)], uBandFill[int(there)], weight);
}

void main() {
    vec3 base = uPalette[vArchetype];
    float baseLuma = dot(base, LUMA);
    vec3 color = base;

    // --- tier 1: the legibility terms ------------------------------------
    if (uShadeTier >= 1) {
        // Subsurface depth: 0 at the body's silhouette, 1 at its core.
        // Taken from the body UV rather than from vEdge on purpose. vEdge goes
        // 1 -> 0 across a single lattice cell, which is a fifth of the body at
        // lattice 5 — correct for a rim, far too tight for the depth of a
        // translucent solid. This gives a gradient across the whole body, so
        // the two terms operate at genuinely different spatial scales, and that
        // separation is most of what makes this read as a material rather than
        // an outlined shape.
        vec2 d = min(vBodyUv, 1.0 - vBodyUv);
        float depth = min(d.x, d.y) * 2.0;

        // The deep tone is the SAME hue, darkened and MORE saturated —
        // piece-identity.md is explicit that shifting it toward brown or grey
        // is the same failure as tinting the rim. Saturating away from luma
        // rather than converting to HSV keeps this to three ops and cannot
        // rotate the hue, which an HSV round-trip at mediump can.
        vec3 deep = mix(vec3(baseLuma), base, uSubsurfaceSaturate) * uSubsurfaceDarken;
        color = mix(color, deep, depth * uSubsurfaceGain);

        // Contact seam / ambient occlusion. piece-identity.md ranks this the
        // PRIMARY small-screen boundary cue, above the lightness ladder,
        // because it is the one cue that survives two same-ish hues squashed
        // together. It darkens; the rim brightens; ADR 0007 separated the two
        // varyings for exactly this reason.
        color *= 1.0 - vContact * uContactGain;

        // Rim light on the free surface. Cubic falloff as two multiplies
        // rather than pow(). Suppressed where the surface is in contact: a face
        // pressed against a neighbour is not a free surface, and letting it rim
        // would put a bright line down the middle of the seam the term above
        // just drew.
        float rim = vEdge * vEdge * vEdge * (1.0 - vContact);
        color += RIM_COLOR * (rim * uRimGain);
    }

    // --- tier 2: identity grain ------------------------------------------
    if (uShadeTier >= 2) {
        float m = mottle(vBodyUv * (uGrainFrequency * uGrainScale[vArchetype]));
        color *= 1.0 + m * uGrainGain;
    }

    // --- the Stage 1 term, unchanged and applied last ---------------------
    // Below 1 is compressed, above 1 is stretched. Only compression darkens;
    // brightening stretched material is a lighting effect and the rim above is
    // where lighting lives.
    //
    // It runs after the gel terms rather than being folded into them so that it
    // scales the finished material colour. Its gain of 4.0 is tuned against the
    // real solver's measured 0.888..1.00 range (Tunables.COMPRESSION_GAIN), the
    // backend confirmed that range barely moved under the softening, and
    // applying it as a final multiply is what keeps that tuning valid — folding
    // it in earlier would let the subsequent terms lift material back up and
    // silently change the darkening the client already approved.
    float compression = max(0.0, 1.0 - vCompression);
    float darken = min(compression * uCompressionGain, uCompressionMax);
    color *= 1.0 - darken;

    // --- tier 3: band glow -------------------------------------------------
    // Gated on the archetype so the well frame never glows. The glow must read
    // as warmth from INSIDE material; on the bare walls it would read as a
    // horizontal HUD line, which the client rejected. vArchetype is flat and
    // the frame is its own draw call, so this branch is coherent.
    if (uShadeTier >= 3 && vArchetype < PIECE_COUNT) {
        float fill = bandFillAt(vWorldPos.y);

        // band-glow.md's curve, exact at every breakpoint it specifies:
        // 0 below 40%, 0.15 at 70%, 0.45 at 85%, 0.85 at 90%. Three smoothsteps
        // summed rather than four branches — same numbers, no divergence, and
        // smoothstep is the eased interpolation the spec requires (it is
        // emphatic that linear would make 40-70% look like something is already
        // happening and undercut the accelerate-at-the-end read that teaches
        // the rule).
        float emissive = 0.15 * smoothstep(0.40, 0.70, fill)
                       + 0.30 * smoothstep(0.70, 0.85, fill)
                       + 0.40 * smoothstep(0.85, 0.90, fill);

        // Breathing: 2.4s period from 70% fill, tightening to 0.9s by 90%.
        // Passed as angular rates and mixed, rather than mixing periods and
        // dividing, to keep a divide out of the fragment stage.
        float urgency = smoothstep(0.70, 0.90, fill);
        float rate = mix(uPulseRateSlow, uPulseRateFast, urgency);
        float pulse = 1.0 + uPulseAmplitude * urgency * sin(uTime * rate);

        // Ember shimmer reuses the grain field rather than sampling a second
        // noise source — band-glow.md asks for exactly this, to avoid a second
        // fetch. Scrolling the UV in time is what makes the glow's internal
        // structure MOVE, which the spec names as the primary tell that the
        // warmth is a property of the zone and not of the piece.
        float shimmer = 1.0 + uShimmerGain * urgency *
            mottle(vBodyUv * uGrainFrequency + vec2(uTime * 0.7, uTime * -0.5));

        float glow = emissive * pulse * shimmer;

        // --- ignition, the one moment the identity cap may be exceeded ------
        //
        // Envelope agreed with the backend engineer for Stage 3A: progress runs
        // 0 -> 1 over 24 ticks (400ms) with the material still present for all
        // of it, so this plays on real geometry. Within that, feel-feedback.md's
        // 120ms flash + 80ms hold + 200ms dissolve lands at progress 0.30 and
        // 0.50.
        //
        // Only the flash is implemented here. The dissolve is Stage 3A's to
        // define — it removes material, which is geometry rather than shading —
        // and guessing at it would put a second definition in the codebase for
        // that work to collide with.
        float clearing = uBandClearProgress[int(floor(clamp(
            (vWorldPos.y - uBandBottomY) * uBandInvHeight, 0.0, float(BAND_COUNT - 1))))];
        // -1 means not clearing. Rising to the white-hot core over the first
        // half of the flash, falling back through the hold.
        float flash = max(0.0, 1.0 - abs(clearing - 0.15) / 0.15) * step(0.0, clearing);

        // The identity cap. band-glow.md: the base hue must contribute at least
        // ~35% of the final colour, so a glowing blue piece stays "blue,
        // glowing" and not "a differently-hued piece" — which matters most
        // exactly when several differently-hued pieces share a filling band.
        // Base share >= 0.35 means the added luma may not exceed
        // (0.65 / 0.35) = 1.857 times the base luma, and uGlowCapRatio carries
        // that constant already divided by GLOW_COLOR's own luma, so the whole
        // rule costs one multiply and one min.
        //
        // The flash is the single exception the spec grants — "a genuine
        // white-hot flash is intentional and momentary" — so the cap *lifts*
        // rather than disappearing, and only for as long as flash is non-zero.
        float cap = mix(uGlowCapRatio, uIgnitionCapRatio, flash);
        glow = min(glow + flash, cap * baseLuma);
        color += mix(GLOW_COLOR, uIgnitionColor, flash) * (glow * uGlowGain);
    }

    // Always on, at every tier above the flat baseline, and deliberately after
    // every darkening term — dithering before them would be scaled away by the
    // exact gradients it exists to protect. band-glow.md asks for "a small,
    // always-on floor amplitude even when band fill is 0%"; this is that floor.
    if (uShadeTier >= 1) {
        color += dither() * uDitherGain;
    }

    fragColor = vec4(color, 1.0);
}
"""

    /**
     * Substitutes the sizes the shader shares with Kotlin, so the two cannot
     * drift apart silently.
     *
     * Every one of these is an array bound or a comparison against an index
     * that comes from the other side of a contract, and GLSL has no way to
     * assert them at runtime — indexing `uPalette` or `uBandFill` out of range
     * is undefined behaviour, which on a real driver is not a wrong colour but
     * whatever that GPU happens to do.
     */
    fun fragment(paletteSize: Int, pieceCount: Int, bandCount: Int): String =
        FRAGMENT
            .replace("PALETTE_SIZE", paletteSize.toString())
            .replace("PIECE_COUNT", pieceCount.toString())
            .replace("BAND_COUNT", bandCount.toString())
}
