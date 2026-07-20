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
 * Values are from `docs/ux/piece-identity.md`, which is the source of truth.
 * Six cool jewel tones, ~37 degrees apart, chosen for CVD separation first and
 * aesthetics second.
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
     * Six piece hues, then the well surface. Indices 0..5 are addressed by
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
        // 6 color-surface #1B1E29 — well walls and floor (docs/ux/tokens.md).
        //   Deliberately NOT #000000: tokens.md notes that if the background
        //   and the walls were both true black, the well would have no legible
        //   boundary on this device's OLED panel.
        0x1B / 255f, 0x1E / 255f, 0x29 / 255f,
    )

    /** Number of piece archetypes. */
    const val PIECE_COUNT = 6

    /** Palette slot for the well walls and floor. */
    const val SURFACE_INDEX = 6

    /** Total slots uploaded to the shader. */
    const val SIZE = 7

    /** The flat `vec3` array for `glUniform3fv`. Returned directly rather than
     *  copied: it is read-only by convention, exactly like `SimState`'s arrays. */
    fun asVec3Array(): FloatArray = RGB
}
