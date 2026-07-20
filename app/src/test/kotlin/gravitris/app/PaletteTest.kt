package gravitris.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * The palette against `docs/ux/piece-identity.md`.
 *
 * These are spec-conformance tests, not implementation tests, and they exist
 * because the palette is the one part of the art direction whose correctness is
 * checkable **without a GPU** — every other judgement Stage 3B makes needs
 * eyes on a device. The hue rules in particular are load-bearing for
 * accessibility and were, until now, protected by nothing but a comment.
 */
class PaletteTest {

    private fun rgb(index: Int): Triple<Float, Float, Float> {
        val v = Palette.asVec3Array()
        return Triple(v[index * 3], v[index * 3 + 1], v[index * 3 + 2])
    }

    /** Hue in degrees, 0..360. */
    private fun hue(index: Int): Float {
        val (r, g, b) = rgb(index)
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val c = hi - lo
        if (c == 0f) return 0f
        val h = when (hi) {
            r -> ((g - b) / c + 6f) % 6f
            g -> (b - r) / c + 2f
            else -> (r - g) / c + 4f
        }
        return h * 60f
    }

    /**
     * The single rule the whole glow language rests on.
     *
     * `piece-identity.md`: "Hue 15-65 degrees (amber/orange/gold/yellow) is
     * reserved for the glow signal and must never be used as a piece base hue.
     * No piece, in the current set or any added later, may sit in that band."
     *
     * If this is ever violated the player cannot tell "this piece is orange"
     * from "this band is nearly full" — the identity channel and the coverage
     * channel collide, and the mechanic the game exists to teach stops being
     * readable. It is the kind of rule that gets broken by someone adding a
     * seventh piece in good faith, which is exactly why it is a test.
     */
    @Test
    fun `no piece hue intrudes on the band reserved for the glow`() {
        for (piece in 0 until Palette.PIECE_COUNT) {
            val h = hue(piece)
            assertTrue(
                h < 15f || h > 65f,
                "piece $piece sits at hue $h, inside the 15-65 degree band reserved for " +
                    "the band-glow signal; a player could not tell it from a glowing piece",
            )
        }
    }

    /**
     * The CVD separation guarantee. `piece-identity.md` spaces the six hues
     * "~37 degrees apart... hand-spaced for hue-angle separation alone", and
     * the scaling rule for a seventh is ">= 30 degrees from every existing
     * hue". 30 is therefore the floor this asserts.
     */
    @Test
    fun `every pair of piece hues is at least thirty degrees apart`() {
        for (a in 0 until Palette.PIECE_COUNT) {
            for (b in a + 1 until Palette.PIECE_COUNT) {
                val raw = abs(hue(a) - hue(b))
                val separation = min(raw, 360f - raw)
                assertTrue(
                    separation >= 30f,
                    "pieces $a and $b are only $separation degrees apart; the palette's " +
                        "colour-vision-deficiency separation depends on hue angle alone",
                )
            }
        }
    }

    private fun lightness(index: Int): Float {
        val (r, g, b) = rgb(index)
        return (max(r, max(g, b)) + min(r, min(g, b))) / 2f
    }

    /**
     * **The lightness ladder described in `piece-identity.md` is not present in
     * the hex values that document ships.** This test pins the gap rather than
     * asserting the spec, because the fix is a UX decision and not the
     * renderer's to make. Raised in handoff 0016.
     *
     * The spec asks for lightness to *alternate* down the table
     * (dark/light/dark/light) as the colour-vision-deficiency backup cue, and
     * quotes a "~10-15% lightness step between adjacent-hue neighbours" as "the
     * cheapest thing to check at real size". Its own L column reads
     * 40/54/44/56/42/55, which alternates correctly.
     *
     * The hex values in the same table do not. Measured:
     *
     * | piece | spec L | actual L | step from previous |
     * | ----- | ------ | -------- | ------------------ |
     * | Jade | 40% | 41.2% | — |
     * | Teal | 54% | 42.9% | **+1.8%** |
     * | Azure | 44% | 53.5% | +10.6% |
     * | Violet | 56% | 58.0% | +4.5% |
     * | Magenta | 42% | 51.4% | -6.7% |
     * | Rose | 55% | 53.7% | +2.4% |
     *
     * So the actual palette climbs almost monotonically with one dip, rather
     * than alternating, and three of the six hexes are 9-11 points away from
     * their own stated lightness.
     *
     * **Why this matters more than a documentation slip.** The spec names
     * **Jade vs Teal** as the single highest-risk pair under deuteranopia, and
     * names the lightness ladder as the cue that catches exactly that pair when
     * hue compresses. Between Jade and Teal the actual step is **1.8%**, not
     * 10-15% — so for the pair that most needs the backup cue, the backup cue
     * is effectively absent.
     *
     * Stage 3B makes this more relevant, not less: subsurface, rim and contact
     * shading now vary apparent lightness continuously across each piece's own
     * surface, which is the very thing the spec says the ladder must survive.
     *
     * Not fixed here. Changing the hexes is UX's call — they were desk-checked
     * against two published CVD-safe references on *hue angle*, and re-picking
     * lightness could disturb that. This test exists so the gap is visible, and
     * so that whoever closes it gets told when these numbers move.
     */
    @Test
    fun `the lightness ladder does not alternate as the spec claims`() {
        val steps = (0 until Palette.PIECE_COUNT - 1)
            .map { lightness(it + 1) - lightness(it) }

        val alternates = steps.withIndex().all { (i, step) -> (step > 0f) == (i % 2 == 0) }
        assertFalse(
            alternates,
            "the lightness ladder now alternates as piece-identity.md specifies — the gap " +
                "recorded in handoff 0016 has been closed, so replace this test with the " +
                "real assertion it was standing in for",
        )

        assertTrue(
            steps[0] < 0.05f,
            "the Jade-to-Teal lightness step is now ${steps[0] * 100}%, no longer the ~1.8% " +
                "recorded in handoff 0016; if this was fixed deliberately, close the gap " +
                "properly and assert the full ladder instead",
        )
    }

    /**
     * The property the ladder was *meant* to guarantee, asserted at the level
     * the current palette can actually defend: no two pieces anywhere in the
     * set are simultaneously close in hue and indistinguishable in lightness.
     *
     * This is genuinely weaker than the spec's ladder — it permits the
     * Jade/Teal pair above, which clears it only on hue angle. It is here so
     * that the palette cannot silently get *worse* while the gap above is open.
     */
    @Test
    fun `no two pieces collapse in both hue and lightness at once`() {
        for (a in 0 until Palette.PIECE_COUNT) {
            for (b in a + 1 until Palette.PIECE_COUNT) {
                val raw = abs(hue(a) - hue(b))
                val hueGap = min(raw, 360f - raw)
                val lightnessGap = abs(lightness(a) - lightness(b))
                assertTrue(
                    hueGap >= 30f || lightnessGap >= 0.10f,
                    "pieces $a and $b are $hueGap degrees and ${lightnessGap * 100}% apart; " +
                        "with both cues collapsed there is nothing left but the grain scale, " +
                        "which piece-identity.md says not to rely on at this screen size",
                )
            }
        }
    }

    /** Grain scale is the tertiary cue, and the spec's table is 0.8x through
     *  1.8x in even steps paired row-for-row with the hues. */
    @Test
    fun `grain scales match the spec table and are all distinct`() {
        val grain = Palette.grainScales()
        assertEquals(Palette.SIZE, grain.size, "grain must index identically to the palette")
        val pieces = grain.take(Palette.PIECE_COUNT)
        assertEquals(listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f), pieces)
        assertEquals(pieces.size, pieces.toSet().size, "two pieces share a grain frequency")
    }

    /** The well surface is not a piece and must never be handed out as one —
     *  the shader also relies on `archetype < PIECE_COUNT` to decide what may
     *  glow. */
    @Test
    fun `the surface slot sits outside the piece range`() {
        assertTrue(Palette.SURFACE_INDEX >= Palette.PIECE_COUNT)
        assertEquals(Palette.SIZE, Palette.SURFACE_INDEX + 1)
        assertEquals(Palette.SIZE * 3, Palette.asVec3Array().size)
    }
}
