package gravitris.app

/**
 * Every tunable number the shell owns, in one place.
 *
 * docs/ux/gestures.md and docs/ux/feel-feedback.md both state that their
 * numbers are "prototype-milestone starting points, explicitly tunable —
 * expose them as named constants, not literals, so they can be retuned
 * against the client's real device without a rebuild-from-spec." This file is
 * that requirement, honoured literally. Nothing in `:app` may inline one of
 * these values.
 *
 * Units are stated in every name or comment. Gesture thresholds are in dp and
 * dp/s deliberately (never pixels, never frames) so they behave identically
 * on the client's adaptive 10-120Hz panel regardless of the rate the panel
 * happens to be running at — see gestures.md "Frame-rate independence".
 */
object Tunables {

    // --- gestures (docs/ux/gestures.md §"Numeric thresholds") ---------------

    /**
     * 8dp. gestures.md is emphatic that this must match Android's
     * `ViewConfiguration` default rather than being invented, so that it
     * already matches the player's muscle memory from every other app. We
     * read the real platform value at runtime (see [gravitris.app.input.GestureConfig])
     * and use this only as the documented fallback if the platform value is
     * unavailable.
     */
    const val TOUCH_SLOP_DP = 8f

    /** 1:1 — 1dp of finger travel moves the piece 1dp in world space. */
    const val DRAG_SENSITIVITY = 1.0f

    /** 16dp of downward travel before the hard-drop velocity test is even evaluated. */
    const val HARD_DROP_MIN_DISPLACEMENT_DP = 16f

    /** +/-25 degrees from straight down. Stored as the cosine to keep the test
     *  a dot product — no trigonometry per touch sample. */
    const val HARD_DROP_ANGLE_COS = 0.906307787f // cos(25 degrees)

    /** 1000dp/s, measured over the trailing velocity window below. */
    const val HARD_DROP_MIN_VELOCITY_DP_PER_S = 1000f

    /**
     * ~60ms trailing window of TIMESTAMPED samples. docs/contracts.md is
     * explicit that this must not degrade to a per-frame delta: Android
     * samples touch above the refresh rate and the core must not lose that
     * resolution to a 60Hz tick.
     */
    const val VELOCITY_WINDOW_NANOS = 60_000_000L

    /** 60ms. Absorbs touch-controller bounce / double-report after a tap. */
    const val ROTATE_DEBOUNCE_NANOS = 60_000_000L

    // --- impact haptics (docs/ux/feel-feedback.md §"Impact haptics") --------

    /**
     * Below this energy no haptic fires at all. feel-feedback.md: without a
     * floor, continuous small settling contacts during a busy stack turn into
     * a constant low buzz, which reads as noise, not feedback.
     */
    const val HAPTIC_ENERGY_FLOOR = 0.15f

    const val HAPTIC_MIN_DURATION_MS = 10L
    const val HAPTIC_MAX_DURATION_MS = 40L

    /** Android `VibrationEffect` amplitude scale is 1..255. */
    const val HAPTIC_MIN_AMPLITUDE = 60
    const val HAPTIC_MAX_AMPLITUDE = 255

    // --- simulation pacing (ADR 0006) --------------------------------------

    /** Exactly 1/60s. Never variable, never scaled by wall-clock delta. */
    const val TICK_SECONDS = 1f / 60f
    const val TICK_NANOS = 16_666_667L

    /**
     * Clamp a frame delta to at most 4 ticks so a stall cannot cascade into a
     * death spiral of catch-up steps (ADR 0006 §3).
     */
    const val MAX_CATCH_UP_TICKS = 4

    /** ADR 0006 §4: ask the LTPO panel for 60Hz explicitly. Not asking is
     *  itself a decision, and it is the wrong one. */
    const val TARGET_REFRESH_HZ = 60f

    // --- compression darkening (ADR 0007's vCompression) -------------------

    /**
     * How much compressed material darkens. `darken = (1 - compression) * gain`.
     *
     * The single shading term Stage 1 carries, approved against the milestone's
     * purpose: with one flat colour per body the interior deformation is
     * invisible and only the silhouette shows the squash, so the demo could not
     * answer the "does it feel heavy?" question it exists to ask.
     *
     * **The boundary is compression to darkness and nothing else.** No rim
     * light, no gradient, no grain. A second term means this is Stage 3.
     *
     * **Retuned at Stage 2 against the real solver. The Stage 1 value of 1.2
     * was wrong by more than 3x, in the direction that would have shipped the
     * term invisible.**
     *
     * Stage 1 tuned this against the kinematic harness, whose squash spanned
     * roughly 0.57..1.16. The real solver's area constraints are compliant but
     * *stiff* (`areaCompliance` 1e-6), so it deforms far less in area than the
     * harness pretended. Measured on a hard drop, lattice 5, 10x20 well
     * (`CompressionRangeTest` re-measures it and fails if it moves):
     *
     * | quantity | value |
     * | -------- | ----- |
     * | deepest compression at impact | ~0.888 |
     * | 5th percentile at that frame | ~0.895 |
     * | 25th percentile at that frame | ~0.941 |
     * | median at that frame | ~0.991 |
     * | settled material in a 20-body pile | 0.957..1.00 |
     *
     * So the usable signal is `1 - compression` in roughly 0..0.11, not 0..0.43
     * — and at gain 1.2 the deepest impact in the game would have darkened by
     * 13%, with settled material at 5%. That is the failure Stage 1 predicted:
     * "if it is narrower the effect will be invisible and someone will conclude
     * the term does not work."
     *
     * 4.0 maps the deepest impact to ~45% darkening and the 25th percentile to
     * ~24%, leaving [COMPRESSION_MAX_DARKEN] unreached. Settled material lands
     * near 2%, which is correct and worth stating: a resting pile should read
     * as its own flat colour, and the darkening should be an *event* — it
     * blooms at the contact surface on impact and fades as the piece relaxes.
     * That event is the weight cue.
     *
     * The measurement is of ten of twenty-five particles, i.e. the lower rows
     * of the piece, not one isolated vertex — so the term reads as a coherent
     * dark band across the contact face rather than as speckle.
     */
    const val COMPRESSION_GAIN = 4.0f

    /**
     * Ceiling on the darkening, 0..1. Piece identity is carried by hue
     * (docs/ux/piece-identity.md) and has to survive deformation — letting this
     * reach 1 would take heavily squashed material to black and destroy the
     * primary identity cue exactly where pieces pile up and need it most.
     *
     * **With the real solver measured, this no longer binds.** At
     * [COMPRESSION_GAIN] 4.0 the deepest impact in the game reaches ~45%, so
     * the cap is a genuine safety rail against a future solver or material
     * change rather than a number shaping the look. Stage 1 flagged the 55%
     * figure as an open question for UX; on the measured range there is
     * currently nothing for them to decide, and the question properly belongs
     * to the gain instead.
     */
    const val COMPRESSION_MAX_DARKEN = 0.55f

    // --- Stage 3B gel shading (docs/ux/piece-identity.md) -------------------

    /**
     * Default shading tier. See `Shaders` for the tier table.
     *
     * 3 — everything — is the default because the client is being asked to
     * judge the art direction, and shipping them a reduced tier by default
     * would have them judge something we did not build. The tier is walked
     * *down* on the device with volume-up, and the frame time at each step is
     * the measurement this stage owes.
     */
    const val SHADE_TIER_DEFAULT = 3
    const val SHADE_TIER_MAX = 3

    /**
     * How far the deep subsurface tone is mixed in at the body's core.
     *
     * Not 1.0: at full strength the centre of every piece reaches the deep tone
     * exactly, and since that tone is both darker and more saturated the piece
     * reads as a ring of hue around a dark middle — a tube, not a solid. 0.55
     * leaves the core recognisably the piece's own colour while still putting a
     * visible gradient across it, which is what sells thickness.
     */
    const val SUBSURFACE_GAIN = 0.55f

    /**
     * Saturation of the deep tone, as a multiplier away from the colour's own
     * luma. Above 1 saturates.
     *
     * `piece-identity.md`: the deep interior colour is "a darkened,
     * more-saturated version of the *same* hue — never shifted toward brown or
     * grey. Muddying the hue at low light is the same failure mode as tinting
     * the rim." 1.35 is the saturation half of that sentence; [SUBSURFACE_DARKEN]
     * is the darkening half. Neither may rotate the hue, and mixing away from
     * luma cannot.
     */
    const val SUBSURFACE_SATURATE = 1.35f

    /** Brightness of the deep tone. Deep material absorbs more light on the way
     *  back out, so the core is darker than the surface. */
    const val SUBSURFACE_DARKEN = 0.62f

    /**
     * How much the contact seam darkens, at full occlusion.
     *
     * This is the term `piece-identity.md` ranks as the **primary**
     * small-screen boundary cue — "the player is never relying on a colour edge
     * alone to tell where one piece ends and another begins" — so it is tuned
     * to be clearly visible rather than tastefully subtle. 0.45 at full contact
     * is a strong crease, and full contact is rare: the solver's occlusion is a
     * penetration ratio, so ordinary resting contact lands well below it.
     *
     * It is a *crease*, not a stroke. The spec asks for "a thin AO-darkened
     * line, not a hard black stroke", and because this darkens a physical
     * quantity that falls off across the outer lattice ring it is soft by
     * construction — there is no way to make it an outline without adding
     * geometry.
     */
    const val CONTACT_GAIN = 0.45f

    /**
     * Strength of the rim light on the free surface.
     *
     * Kept below the point where the rim reaches white, for the same reason
     * [COMPRESSION_MAX_DARKEN] exists at the other end: hue is the identity
     * cue, and a blown-out rim erases it exactly at the silhouette, which is
     * where the eye goes first.
     */
    const val RIM_GAIN = 0.30f

    /**
     * Amplitude of the grain, as a fraction of the material colour.
     *
     * Small on purpose. Grain is a texture cue and a tertiary identity cue, not
     * a pattern — at a strength where you notice it as a pattern it competes
     * with the compression darkening, which is the term the client has already
     * approved as the weight cue.
     */
    const val GRAIN_GAIN = 0.09f

    /** Base grain frequency in cycles across a body, before the per-archetype
     *  multiplier in [Palette.grainScales]. */
    const val GRAIN_FREQUENCY = 7.0f

    /**
     * Dither amplitude, in units of the 0..1 colour channel.
     *
     * `1.4 / 255` — a little over one 8-bit code value, peak to peak. That is
     * the whole design: a dither has to be at least one code value to move a
     * pixel across a quantisation boundary, and anything much more is visible
     * as noise rather than as smoothness.
     *
     * `tokens.md` and `band-glow.md` both require this against `color-bg`
     * #000000 on the client's OLED, and both require it to be driven by the
     * existing procedural field rather than by a second dithering system.
     * **Design against an 8-bit output surface as the baseline** even though the
     * panel does 10-bit — that is the specs' explicit instruction, and it is
     * why this is on regardless of what the surface turns out to be.
     */
    const val DITHER_GAIN = 1.4f / 255f

    // --- band glow (docs/ux/band-glow.md) ----------------------------------

    /**
     * Emissive gain on the amber glow colour.
     *
     * The curve in `band-glow.md` produces an intensity in 0..0.85, and this
     * scales it to the additive term. It is capped per-fragment by
     * [GLOW_CAP_RATIO] regardless.
     */
    const val GLOW_GAIN = 1.0f

    /**
     * The cap that keeps a glowing piece legible as its own hue.
     *
     * `band-glow.md`: "Cap the blend so the base hue always contributes at
     * least ~35% of the final colour... Never let sustained glow fully white-out
     * a piece — that would erase the identity signal at exactly the moment
     * multiple differently-hued pieces are sharing a band, which is when
     * identity matters most."
     *
     * Base share >= 0.35 means the added luma may not exceed
     * `(0.65 / 0.35) = 1.857` times the base luma. Dividing by the glow
     * colour's own luma (#FFB347 -> 0.744 under Rec.601) folds the whole
     * constraint into one number the shader applies with a multiply and a min:
     * `1.857 / 0.744 = 2.50`.
     *
     * The 120ms ignition flash is the one moment the spec permits this to be
     * exceeded, and it is deliberately not implemented — ignition fires on a
     * settle against a clear threshold, which is Stage 3A. Raising this value
     * for the duration of the flash is the only change that needs.
     */
    const val GLOW_CAP_RATIO = 2.50f

    /**
     * The lifted cap during the 120ms ignition flash.
     *
     * `band-glow.md` grants exactly one exception to [GLOW_CAP_RATIO]: "except
     * during the 120ms ignition flash where a genuine white-hot flash is
     * intentional and momentary." So the cap lifts rather than disappearing —
     * an uncapped flash would clip to white and, on a 1400-nit OLED, do it
     * hard.
     *
     * 6.0 puts the base hue at roughly 18% of the final colour at the flash's
     * peak, which is a white-hot core that still carries a trace of the piece
     * underneath it, against 35% for sustained glow. It is momentary by
     * construction: the shader drives it from the clear envelope's first 120ms
     * and the term is identically zero outside it.
     */
    const val IGNITION_CAP_RATIO = 6.0f

    /** 2.4s breathing period at 70-85% fill, as an angular rate. */
    const val PULSE_RATE_SLOW = 2.6179939f // 2*PI / 2.4

    /**
     * 0.9s breathing period at the 85-90% final approach, as an angular rate.
     *
     * `accessibility.md` floors this at a 1.2s period under reduced motion —
     * "brightness ramp logic is unchanged, only the oscillation *rate* is
     * slowed" — which is a matter of passing 5.236 (2*PI / 1.2) here instead.
     * Settings do not exist yet, so nothing selects between them today; the
     * shader reads whichever rate it is given.
     *
     * `accessibility.md` also fixes the floor under both: no visual element may
     * flash faster than 3 times per second (WCAG 2.3.1). 0.9s is ~1.1Hz, well
     * clear, and that ceiling is why this must not be "tuned" faster later while
     * chasing more urgency.
     */
    const val PULSE_RATE_FAST = 6.9813170f // 2*PI / 0.9

    /** Depth of the breathing pulse, as a fraction of the emissive term. */
    const val PULSE_AMPLITUDE = 0.22f

    /** Depth of the ember shimmer. Reuses the grain field per `band-glow.md`,
     *  scrolled in time — the motion is what tells the player the warmth
     *  belongs to the zone rather than to the piece. */
    const val SHIMMER_GAIN = 0.30f

    /**
     * Period, in seconds, at which the shader's clock wraps.
     *
     * `uTime` is a `mediump` float in the fragment stage and mediump loses
     * whole-number resolution above 2048, so an app left running for an hour
     * would quantise the pulse into steps and then stop it entirely. 60s is a
     * whole multiple of neither pulse period, so the wrap puts a phase
     * discontinuity in the breathing once a minute — invisible against a pulse
     * this slow, and the honest alternative (a highp clock) costs precision in
     * the stage that can least afford it.
     */
    const val SHADER_TIME_WRAP_SECONDS = 60f

    // --- well geometry (ADR 0010 — derived from insets at runtime) ---------

    /** World units across the well. Matches `SimConfig.wellWidth`'s default so
     *  the shell and the core agree without a conversion factor. */
    const val WELL_WIDTH_WORLD = 10f

    /** Clamps on the derived well height, so a very tall or very square safe
     *  area cannot produce a degenerate playfield. */
    const val WELL_HEIGHT_MIN_WORLD = 12f
    const val WELL_HEIGHT_MAX_WORLD = 30f

    // --- the Milestone 1 toy ------------------------------------------------

    /**
     * Particles per piece edge. `SimConfig.lattice`'s own default and ADR
     * 0007's default quality tier — 25 particles and 32 triangles per piece.
     *
     * Fixed for Milestone 1 rather than selected by ADR 0009's startup quality
     * calibration, which does not exist yet. The renderer takes the lattice as
     * a parameter and the topology is built per lattice size, so supporting
     * 4/5/6 is small once something exists to choose between them — and the
     * hidden benchmark is what will tell us whether anything needs to.
     */
    const val TOY_LATTICE = 5

    /**
     * Upper rail on bodies in the well.
     *
     * Sizes the renderer's vertex and index buffers, so it is a hard bound and
     * not a preference. A 10-wide well holds roughly four pieces per row, so
     * this is about ten rows — more than fills any well height the layout can
     * produce, which means the toy resets on material reaching the top rather
     * than on this number. It is the backstop, not the rule.
     */
    const val TOY_MAX_BODIES = 40
}
