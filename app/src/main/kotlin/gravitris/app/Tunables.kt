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

    /** 1:1 — 1dp of finger travel moves the piece 1dp in world space, while
     *  the piece is being positioned. */
    const val DRAG_SENSITIVITY = 1.0f

    // The hard-drop threshold family (min displacement, angle cone, min
    // velocity, and the ~60ms velocity window) is gone with the control
    // redesign of 2026-07-21: release IS the drop and the piece then falls
    // under real gravity, so there is no swipe-down flick to recognise and no
    // flick speed to measure. VelocityWindow was deleted with them. The tap
    // debounce below stays — a tap still rotates.

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

    // --- simulation pacing (ADR 0006 / 0013) -------------------------------

    // The fixed tick, the no-clamp accumulator and the catch-up bound now live
    // in :core-sim's FrameDriver (ADR 0013) — the shell used to keep its own
    // TICK_NANOS / MAX_CATCH_UP_TICKS here and clamp the delta itself, which is
    // exactly the wall-clock-dilating behaviour ADR 0013 removed. Those
    // constants are gone with the toy; the app reads Simulation.TICK through the
    // driver and never paces the sim on its own again.

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
     * reads as a ring of hue around a dark middle — a tube, not a solid.
     *
     * Round 3 (`visual-direction.md` §14) leans on this term harder: with true
     * alpha transparency rejected on cost grounds, the subsurface gradient is
     * the *primary* "this is translucent jelly" cue, so it is raised from round
     * 2's subtle 0.55 to 0.80 — still short of the tube, but now the depth
     * gradient is a read the client can see rather than a whisper. It now runs
     * on body-wide UV (§15), so the gradient sweeps the whole tetromino's
     * silhouette instead of resetting per cell. First-pass value; tune on-device.
     */
    const val SUBSURFACE_GAIN = 0.80f

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
     *
     * Round 3 (§14) nudges it up from 0.30 → 0.38 and warms the rim colour
     * (see `RIM_COLOR` in `Shaders.kt`) so the silhouette also reads as the
     * glassy edge-catch of a wet candy, its second dual-purpose role. Still
     * short of white. First-pass value; tune on-device.
     */
    const val RIM_GAIN = 0.38f

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

    // --- Stage 3C glossy jelly candy (docs/ux/visual-direction.md §14/§16) ---

    /**
     * Peak strength of the gloss highlight streak, added in near-white
     * (`color-specular`). §14's reference is ONE hard, high-contrast highlight —
     * so this is deliberately strong, not a subtle sheen: the streak is small
     * and sharp-edged, and its punch is what makes the body read as wet glass
     * rather than matte gel. 0.70 is a first-pass value the client will steer;
     * the real tune is on-device, where the highlight's contrast against the
     * saturated base is what actually decides "glossy" vs "washed out".
     */
    const val SPECULAR_GAIN = 0.70f

    /**
     * Half-width of the gloss streak's feather, in body-UV units (the UV spans
     * ~0..1 across the whole piece, §15). Small keeps the streak a sharp bright
     * line that feathers, not a soft lobe — §14 is emphatic that the SHAPE is
     * what sells the material. 0.16 is a thin band; raise it for a fatter,
     * softer highlight, lower it for a harder glint. First-pass; tune on-device.
     */
    const val SPECULAR_SHARPNESS = 0.16f

    /**
     * How hard a true outer-silhouette corner fades toward `color-tray` (§16).
     *
     * The shader cubes `vCorner` (which already ramps 1→0 over one lattice
     * spacing) before applying this, so the visible fade is pulled tight to the
     * corner tip — the client's "slightly rounded, not a die/cube". 1.0 fades
     * the very tip fully to the tray; below 1 leaves the tip partly the piece's
     * own colour (a softer, less-rounded read). The apparent radius
     * (`radius-piece-corner`, ~10–15% of a cell) is reached by tuning this and
     * the shader's cube exponent together, on-device — not a raw geometry value.
     */
    const val CORNER_GAIN = 1.0f

    // --- soft contact shadow (docs/ux/visual-direction.md §18) --------------

    /**
     * The piece contact shadow's colour: `color-shadow` = `color-tray` darkened
     * 35%. NOT black — a black shadow on a saturated candy world reads as a
     * hole; a darkened-tray tone reads as the tray in shade, which is what makes
     * the candy look like it is resting *in* the world (§18). `color-tray`
     * #7C93A6 × 0.65.
     */
    const val SHADOW_R = 0.486f * 0.65f
    const val SHADOW_G = 0.576f * 0.65f
    const val SHADOW_B = 0.651f * 0.65f

    /** Opacity of the contact shadow (`color-shadow` @ 40%). The shadow is the
     *  one pass in the renderer that turns `GL_BLEND` on, and only for itself. */
    const val SHADOW_ALPHA = 0.40f

    /**
     * Shadow offset in world units, down and slightly right (`shadow-offset-
     * piece`, §18). World units so it scales with the piece rather than the
     * screen. Positive Y is up in world space, so "down" is negative Y.
     */
    const val SHADOW_OFFSET_X = 0.05f
    const val SHADOW_OFFSET_Y = -0.08f

    // --- antialiasing (docs/ux/visual-direction.md §17) --------------------

    /**
     * MSAA sample count requested on the EGL surface config (§17): hardware
     * multisampling, resolved by the driver, smoothing the opaque silhouette
     * edges of the pieces, the §16 rounded corners and the well frame all at
     * once — with NO shader change and no violation of the "no blend, no
     * discard" rules (`Shaders.kt`), because it lives entirely at the
     * rasteriser/resolve stage.
     *
     * This is a build-time dial, NOT the runtime `shadeLevel` ladder: the sample
     * count is fixed when the EGL surface is created and cannot change per
     * frame, so §17's "fold it into uShadeTier" is honoured in spirit — one
     * cuttable dial ordered with the rest — but implemented as a single constant
     * here (set to 0 to disable) rather than a runtime step. `GameView`'s config
     * chooser falls back to no-MSAA if the driver offers no matching config, so
     * a device (or the software emulator) without 4× MSAA still runs.
     *
     * COST, named plainly (§17): 4× MSAA roughly quadruples colour/depth
     * attachment bandwidth and adds a resolve pass, on a Fairphone 6 already
     * measured at 15.0ms mean against 16.67ms with a nearly-flat shader. Stacked
     * on the §18 shadow pass and the §19 full-screen background, this is a real
     * frame-budget risk, not decoration — it is the single most expensive item
     * this round adds and the first to cut (lower to 2, then 0) if the on-device
     * budget disagrees. Only the client's phone can price it; the emulator
     * cannot.
     */
    const val MSAA_SAMPLES = 4

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

    /**
     * World units across the well.
     *
     * Widened 10 → 20 for the tetromino redesign (2026-07-21). A cell is
     * `2 * particleRadius * lattice` ≈ 2.25 world units wide at lattice 5, so a
     * 4-cell I-piece spans ~9.0; 10 units across left only ~5 columns, far too
     * tight to slide a horizontal I-piece past a stack. 20 gives ~9 columns of
     * play — between classic Tetris' 10 and the old 5 — which is the field the
     * Backend Engineer sized the coverage bands (`bandColumns`) and the clear
     * threshold against. Agreed with them, 2026-07-21.
     *
     * The app always passes this to `SimConfig.wellWidth` explicitly (via
     * `WellLayout`), so it need not equal `SimConfig`'s own default; the core
     * owns aligning that default for its tests and benchmark.
     */
    const val WELL_WIDTH_WORLD = 20f

    /**
     * Clamps on the derived well height, so a very tall or very square safe
     * area cannot produce a degenerate playfield.
     *
     * Raised with the width (2026-07-21). `WellLayout` derives height from
     * `width * safeAspect` and keeps world units square, so at width 20 the
     * client's ~20:9 panel wants ~41 units of height; a 30 cap would clamp that
     * and stretch every piece vertically (the anisotropy `WellLayoutTest`'s
     * "world units are square" case guards against). 48 clears the client
     * device unclamped; the min rises in step so a squat/landscape safe area
     * stays a sane field rather than a 20×12 sliver.
     */
    const val WELL_HEIGHT_MIN_WORLD = 24f
    const val WELL_HEIGHT_MAX_WORLD = 48f

    // --- mechanic tuning shipped to the client ------------------------------

    /**
     * The clear threshold the app ships with — the fraction of a coverage band
     * that must fill before it clears, fed to `SimConfig.clearThreshold` and so
     * to the initial `MechanicTuning.clearThreshold`. **A provisional starting
     * point the client tunes by eye, not a fixed rule**, and live-tunable at
     * runtime (ADR 0004); the dev panel (Stage 4C) will write to it.
     *
     * Set from play-through data, not a guess: a couple of squashed bodies
     * already cover a low band to ~0.50, so any threshold below that clears
     * constantly and the well never builds; the core's own default (0.90) needs
     * a band packed almost solid. 0.80 sits between the proven-good 0.70 (real
     * piles accumulate then clear) and the brief's ~0.90, so the mechanic is
     * visible and satisfying on first play. Product Lead's call, 2026-07-21.
     */
    const val CLEAR_THRESHOLD = 0.80f

    // --- positioning-window urgency cue (ADR 0016) --------------------------

    /**
     * Thickness, in world units, of the draining countdown bar that shows how
     * long the active piece may still be slid before it drops on its own
     * (`SimState.positioningTicksRemaining`). Drawn across the top of the well,
     * shrinking toward the centre as the window runs out — the client's *"much
     * less long able to move"* made visible.
     *
     * A deliberately plain placeholder cue: legible enough to feel the pressure,
     * left for the visual pass to refine (colour, easing, whether it lives at the
     * top edge or under the piece). 0.6 units is twice the wall thickness
     * ([gravitris.app.gl.WellFrame]), so it reads as a distinct band rather than
     * a second wall, against a 20-unit-wide well.
     */
    const val POSITIONING_BAR_THICKNESS_WORLD = 0.6f

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
