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
