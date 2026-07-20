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

    // --- well geometry (ADR 0010 — derived from insets at runtime) ---------

    /** World units across the well. Matches `SimConfig.wellWidth`'s default so
     *  the shell and the core agree without a conversion factor. */
    const val WELL_WIDTH_WORLD = 10f

    /** Clamps on the derived well height, so a very tall or very square safe
     *  area cannot produce a degenerate playfield. */
    const val WELL_HEIGHT_MIN_WORLD = 12f
    const val WELL_HEIGHT_MAX_WORLD = 30f
}
