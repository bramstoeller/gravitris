package gravitris.app.gl

import gravitris.app.Palette
import gravitris.app.Tunables
import kotlin.math.PI
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * What can honestly be tested about a fragment shader with no GPU in the room.
 *
 * Not much of the *look* — that needs eyes on the client's device, and this
 * file does not pretend otherwise. What it can pin down is the class of
 * mistakes that would reach the device as an undebuggable failure rather than
 * an ugly one:
 *
 * - a placeholder left unsubstituted, which is a shader compile error on start
 *   and a black screen with a logcat line nobody is reading;
 * - an array bound that disagrees with the Kotlin side, which is undefined
 *   behaviour in GLSL rather than a wrong colour;
 * - the tuned constants silently drifting away from the numbers the UX specs
 *   derive them from.
 *
 * The last of those is the interesting one. Several constants in [Tunables] are
 * not free parameters at all — they are arithmetic consequences of a sentence
 * in `band-glow.md` or `accessibility.md`, and the derivation lives only in a
 * comment. These tests re-do the arithmetic, so a future tuning pass that
 * "rounds a number" gets told which spec it just broke.
 */
class ShadersTest {

    private fun fragment() =
        Shaders.fragment(Palette.SIZE, Palette.PIECE_COUNT, bandCount = 20)

    @Test
    fun `every placeholder is substituted`() {
        val source = fragment()
        for (placeholder in listOf("PALETTE_SIZE", "PIECE_COUNT", "BAND_COUNT")) {
            assertFalse(
                source.contains(placeholder),
                "$placeholder survived substitution; the shader will fail to compile on " +
                    "the device and the app will start to a black screen",
            )
        }
    }

    @Test
    fun `the substituted sizes are the ones Kotlin uploads`() {
        val source = fragment()
        assertTrue(source.contains("uniform vec3 uPalette[${Palette.SIZE}]"))
        assertTrue(source.contains("uniform float uGrainScale[${Palette.SIZE}]"))
        assertTrue(source.contains("uniform float uBandFill[20]"))
        assertTrue(
            source.contains("vArchetype < ${Palette.PIECE_COUNT}"),
            "the glow's piece test must use the real piece count, or the well frame " +
                "will glow as if it were material",
        )
    }

    /**
     * Every attribute the vertex shader declares must have a matching location
     * constant, because `BodyMesh` binds by number and GLSL binds by name — the
     * two agree only by convention, and a mismatch reads whatever memory the
     * other attribute is bound to.
     */
    @Test
    fun `vertex attribute locations match the mesh's binding points`() {
        val source = Shaders.VERTEX
        val expected = mapOf(
            BodyMesh.ATTRIB_POSITION to "in vec2 aPosition",
            BodyMesh.ATTRIB_ARCHETYPE to "in int aArchetype",
            BodyMesh.ATTRIB_COMPRESSION to "in float aCompression",
            BodyMesh.ATTRIB_CONTACT to "in float aContact",
            BodyMesh.ATTRIB_BODY_UV to "in vec2 aBodyUv",
            BodyMesh.ATTRIB_EDGE to "in float aEdge",
            BodyMesh.ATTRIB_CORNER to "in float aCorner",
        )
        for ((location, declaration) in expected) {
            assertTrue(
                source.contains("layout(location = $location) $declaration"),
                "attribute '$declaration' is not declared at location $location",
            )
        }
        assertEquals(
            expected.size, expected.keys.toSet().size,
            "two attributes share a location",
        )
    }

    /**
     * `band-glow.md`: "Cap the blend so the base hue always contributes at
     * least ~35% of the final colour... Never let sustained glow fully white-out
     * a piece."
     *
     * The shader applies that as one multiply and one min, against a ratio that
     * folds the whole rule into a single constant. This re-derives it: base
     * share >= 0.35 means added luma <= (0.65 / 0.35) x base luma, and the
     * shader's `glow` is in units of the glow colour, so the ratio carries a
     * division by that colour's own luma.
     */
    @Test
    fun `the glow cap enforces the thirty-five percent base-hue floor`() {
        val glowLuma = 0.299f * 1.0f + 0.587f * 0.702f + 0.114f * 0.278f // #FFB347
        val derived = (0.65f / 0.35f) / glowLuma

        assertEquals(
            derived, Tunables.GLOW_CAP_RATIO, 0.02f,
            "the glow cap no longer matches band-glow.md's 35% base-hue floor",
        )

        // And check the rule it stands for, on the darkest piece in the palette
        // — the one where a fixed additive glow would swamp the base hue first.
        val palette = Palette.asVec3Array()
        for (piece in 0 until Palette.PIECE_COUNT) {
            val r = palette[piece * 3]
            val g = palette[piece * 3 + 1]
            val b = palette[piece * 3 + 2]
            val baseLuma = 0.299f * r + 0.587f * g + 0.114f * b
            val cappedGlow = Tunables.GLOW_CAP_RATIO * baseLuma
            val addedLuma = cappedGlow * Tunables.GLOW_GAIN * glowLuma
            val baseShare = baseLuma / (baseLuma + addedLuma)
            assertTrue(
                baseShare >= 0.34f,
                "at full sustained glow piece $piece keeps only ${baseShare * 100}% of its " +
                    "own hue; band-glow.md requires ~35% so it still reads as 'blue, " +
                    "glowing' rather than as a differently-hued piece",
            )
        }
    }

    /**
     * The pulse rates are `band-glow.md`'s periods expressed as angular rates,
     * because the shader mixes rates rather than dividing by a period.
     */
    @Test
    fun `pulse rates are the specified breathing periods`() {
        assertEquals(
            (2.0 * PI / 2.4).toFloat(), Tunables.PULSE_RATE_SLOW, 1e-4f,
            "band-glow.md specifies a 2.4s breathing period at 70-85% fill",
        )
        assertEquals(
            (2.0 * PI / 0.9).toFloat(), Tunables.PULSE_RATE_FAST, 1e-4f,
            "band-glow.md specifies the pulse tightening to 0.9s at the final approach",
        )
    }

    /**
     * `accessibility.md`, as a hard rule and not a preference: "no visual
     * element may flash faster than 3 times per second (WCAG 2.3.1)... write it
     * down explicitly so nobody 'tunes' the final-approach pulse rate past it
     * later while chasing more urgency."
     *
     * This is that, written down somewhere that fails.
     */
    @Test
    fun `no pulse rate approaches the photosensitivity flash threshold`() {
        for ((name, rate) in listOf(
            "slow" to Tunables.PULSE_RATE_SLOW,
            "fast" to Tunables.PULSE_RATE_FAST,
        )) {
            val hz = rate / (2.0 * PI)
            assertTrue(
                hz < 3.0,
                "the $name pulse runs at $hz Hz, at or past WCAG 2.3.1's three-flashes-" +
                    "per-second threshold; this is a photosensitivity limit, not a taste call",
            )
        }
    }

    /**
     * The dither has to be worth at least one 8-bit code value or it cannot
     * move a pixel across a quantisation boundary, and much more than one reads
     * as noise. `tokens.md` requires designing against an 8-bit surface as the
     * baseline regardless of the panel's 10-bit capability.
     */
    @Test
    fun `dither amplitude straddles a single eight-bit code value`() {
        val codeValues = Tunables.DITHER_GAIN * 255f
        assertTrue(
            codeValues in 1.0f..2.0f,
            "the dither is worth $codeValues code values; below 1 it cannot cross a " +
                "quantisation step and above ~2 it is visible as noise on a true-black field",
        )
    }

    /**
     * The ignition flash is the single point where the identity cap is allowed
     * to be exceeded, so the two caps must stay in the right relationship and
     * the flash must stay genuinely momentary.
     *
     * The envelope it rides on was agreed with the Backend Engineer for Stage
     * 3A: `bandClearProgress` runs 0 to 1 over 24 ticks (400ms) with material
     * still present, and `feel-feedback.md`'s 120ms flash therefore occupies
     * the first 0.30 of it. The shader peaks the flash at 0.15 — the midpoint
     * of that window — and returns to zero at both ends.
     */
    @Test
    fun `the ignition cap lifts above the identity cap but stays bounded`() {
        assertTrue(
            Tunables.IGNITION_CAP_RATIO > Tunables.GLOW_CAP_RATIO,
            "the ignition flash must be able to exceed the sustained cap, or there is " +
                "no flash",
        )

        // The flash's shape, as the shader computes it, at the envelope
        // positions that matter.
        fun flashAt(progress: Float) =
            if (progress < 0f) 0f else maxOf(0f, 1f - kotlin.math.abs(progress - 0.15f) / 0.15f)

        assertEquals(0f, flashAt(-1f), 0f, "a band that is not clearing must not flash")
        assertEquals(0f, flashAt(0f), 1e-6f, "the flash starts from nothing")
        assertEquals(1f, flashAt(0.15f), 1e-6f, "the flash peaks mid-window")
        assertEquals(0f, flashAt(0.30f), 1e-6f, "the flash is over by the end of the 120ms")
        assertEquals(
            0f, flashAt(0.75f), 0f,
            "the flash must be zero through the dissolve, or the cap stays lifted for " +
                "the whole 400ms envelope and the white-hot core stops being momentary",
        )

        // At the peak the base hue keeps a trace rather than being erased.
        val palette = Palette.asVec3Array()
        val baseLuma = 0.299f * palette[0] + 0.587f * palette[1] + 0.114f * palette[2]
        val glowLuma = 0.299f * 1.0f + 0.587f * 0.957f + 0.114f * 0.878f // #FFF4E0
        val added = Tunables.IGNITION_CAP_RATIO * baseLuma * Tunables.GLOW_GAIN * glowLuma
        val baseShare = baseLuma / (baseLuma + added)
        assertTrue(
            baseShare in 0.10f..0.30f,
            "at the flash's peak the base hue keeps ${baseShare * 100}%; below ~10% the " +
                "flash clips to flat white on a 1400-nit OLED, above ~30% it is not a " +
                "white-hot core at all",
        )
    }

    /**
     * The band feather. `band-glow.md` wants the emissive mask softened at band
     * boundaries "by roughly 10-15% of band height (soft falloff, not a hard
     * cutoff)" — a hard edge "reads as a debug overlay or a HUD line", which the
     * client rejected.
     *
     * The first implementation lerped between band centres, feathering across a
     * whole band height. That is ~7x the spec and it matters more than it
     * sounds: a piece is 2.40 world units tall against a 1.0-unit band, so an
     * over-feathered mask blurs three bands' values into one gradient across a
     * single piece and the horizontal zone stops reading as a zone.
     */
    @Test
    fun `the band boundary feather stays inside the specified width`() {
        val source = fragment()
        val feather = Regex("const float BAND_FEATHER = ([0-9.]+);")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.toFloat()
        assertTrue(feather != null, "BAND_FEATHER is no longer declared in the shader")

        // Declared as a half-width either side of the boundary.
        val total = feather!! * 2f
        assertTrue(
            total in 0.10f..0.15f,
            "the band feather spans ${total * 100}% of band height; band-glow.md asks for " +
                "roughly 10-15%, and a full-band feather smears the horizontal banding that " +
                "is the entire coverage signal",
        )
    }

    /**
     * The tier ladder must stay monotone and reach the flat baseline, because
     * it is the cut list: if the top tier does not run everything, or the
     * bottom does not restore Stage 1's shader, the five frame times the client
     * reports are not measuring what they are said to measure.
     */
    @Test
    fun `the shading dial spans the full ladder`() {
        assertEquals(Tunables.SHADE_TIER_MAX, Tunables.SHADE_TIER_DEFAULT)
        for (tier in 0..Tunables.SHADE_TIER_MAX) {
            assertTrue(
                fragment().contains("uShadeTier >= $tier") || tier == 0,
                "no shader term is gated at tier $tier, so the dial has a dead position",
            )
        }
    }
}
