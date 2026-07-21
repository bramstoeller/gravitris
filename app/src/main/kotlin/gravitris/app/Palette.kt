package gravitris.app

/**
 * The piece palette, owned by `:app` per `docs/contracts.md` §5.
 *
 * `SimState.bodyArchetype` is an **index, not a hue** — ADR 0007 argues that
 * point at length and it is the reason this table lives here rather than in
 * `:core-sim`. The shell can add a seventh piece, or swap the whole palette
 * for a colour-vision-deficiency variant, without a shader change and without
 * touching the simulation.
 *
 * Values are from `docs/ux/piece-identity.md` and `docs/ux/visual-direction.md`,
 * which are the source of truth. Seven jewel tones, >= 30 degrees apart, chosen
 * for CVD separation first and aesthetics second — six cool tones plus Emerald,
 * added with the tetromino redesign so each of the seven archetypes owns a hue.
 *
 * **Hue 15-65 degrees (amber/orange/gold/yellow) is reserved for the band-glow
 * signal and must never be used as a piece base hue** — otherwise the player
 * cannot tell "this piece is orange" from "this band is nearly full". No band
 * glow exists yet at Stage 1, but the constraint is recorded here because the
 * palette is the thing that would violate it.
 *
 * Colours are stored as linear-ish sRGB triples in 0..1, ready to upload as a
 * `vec3` uniform array. No gamma correction is applied: Stage 1 draws flat
 * colours with no lighting, so there is nothing to be correct *about* yet, and
 * pretending otherwise would bake a decision the shading pass should own.
 */
object Palette {

    /**
     * Seven piece hues, then the well surface. Indices 0..6 are addressed by
     * `bodyArchetype`; [SURFACE_INDEX] is used by the well frame geometry.
     */
    private val RGB = floatArrayOf(
        // 0 Jade    #1FB37A  H150 S62% L40%
        0x1F / 255f, 0xB3 / 255f, 0x7A / 255f,
        // 1 Teal    #1B9FC0  H187 S68% L54%
        0x1B / 255f, 0x9F / 255f, 0xC0 / 255f,
        // 2 Azure   #3162E0  H224 S68% L44%
        0x31 / 255f, 0x62 / 255f, 0xE0 / 255f,
        // 3 Violet  #7148E0  H261 S64% L56%
        0x71 / 255f, 0x48 / 255f, 0xE0 / 255f,
        // 4 Magenta #B23FC7  H298 S52% L42%
        0xB2 / 255f, 0x3F / 255f, 0xC7 / 255f,
        // 5 Rose    #D63C6E  H335 S62% L55%
        0xD6 / 255f, 0x3C / 255f, 0x6E / 255f,
        // 6 Emerald #3BA12B  H112 S58% L40% — the seventh hue, appended for the
        //   seven tetromino archetypes (docs/ux/visual-direction.md). Additive:
        //   the existing six are unmoved, so no CVD re-derivation. 112 degrees
        //   is 47 clear of the reserved 15-65 glow band and continues the dark
        //   rung of the lightness alternation at L40%.
        0x3B / 255f, 0xA1 / 255f, 0x2B / 255f,
        // 7 color-surface #1B1E29 — well walls and floor (docs/ux/tokens.md).
        //   Deliberately NOT #000000: tokens.md notes that if the background
        //   and the walls were both true black, the well would have no legible
        //   boundary on this device's OLED panel.
        0x1B / 255f, 0x1E / 255f, 0x29 / 255f,
    )

    /**
     * Per-archetype grain frequency multiplier — `piece-identity.md`'s
     * **tertiary** identity cue, the one that survives full colour blindness or
     * a monochrome screenshot.
     *
     * Values are the spec's table verbatim: 0.8x through 2.0x in even 0.2x
     * steps, paired with the hue on the same row — Emerald continues the ladder
     * at 2.0x. It costs nothing to carry — the gel
     * shader already needs a noise field for the material itself, so giving
     * each piece its own frequency is a multiply, not a second pass.
     *
     * **Do not promote this to a primary cue.** `piece-identity.md` is blunt
     * that on the client's 6.31" panel it is "the most at-risk cue, not the
     * most reliable one": a heavily-squashed sliver a few millimetres tall may
     * not have the physical pixels to resolve a frequency difference at all.
     * The boundary seam and the lightness ladder are what legibility actually
     * rests on. This is available if both of those fail a given viewer.
     *
     * The surface slot carries 1.0 and is never read — the well frame draws
     * with a body UV of (0, 0), where the grain term evaluates to zero. It is
     * present only so this array and [RGB] index identically.
     */
    private val GRAIN = floatArrayOf(0.8f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 1.0f)

    /** Number of piece archetypes. */
    const val PIECE_COUNT = 7

    /** Palette slot for the well walls and floor. */
    const val SURFACE_INDEX = 7

    /** Total slots uploaded to the shader. */
    const val SIZE = 8

    /**
     * Maps a core piece archetype (`0 until Simulation.ARCHETYPE_COUNT` = 7)
     * onto one of the [PIECE_COUNT] = 7 piece hues.
     *
     * **The collision this used to work around is gone.** Until the tetromino
     * redesign the core dealt seven shape-archetypes over only six hues
     * (`piece-identity.md`), so this folded archetype 6 onto hue 0 to stop it
     * indexing [SURFACE_INDEX] and painting a piece the well-surface grey. UX
     * resolved it by adding a seventh hue (Emerald, `visual-direction.md`), so
     * the counts now match and this is the identity map for every archetype the
     * core deals.
     *
     * The `floorMod` is kept, not as a fold but as a guard: an archetype that
     * ever arrived out of range would wrap to a real hue rather than index the
     * surface slot and vanish into the wall. It is a one-instruction backstop on
     * a value that comes from another module, not an abstraction with a second
     * caller — see [gravitris.app.gl.BodyMesh], its only one.
     */
    fun pieceHue(archetype: Int): Int = Math.floorMod(archetype, PIECE_COUNT)

    /** The flat `vec3` array for `glUniform3fv`. Returned directly rather than
     *  copied: it is read-only by convention, exactly like `SimState`'s arrays. */
    fun asVec3Array(): FloatArray = RGB

    /** The flat grain-scale array for `glUniform1fv`, indexed identically to
     *  [asVec3Array]. Read-only by the same convention. */
    fun grainScales(): FloatArray = GRAIN
}
