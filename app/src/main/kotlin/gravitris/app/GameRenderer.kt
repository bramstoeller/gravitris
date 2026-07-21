package gravitris.app

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import gravitris.app.gl.Background
import gravitris.app.gl.BodyMesh
import gravitris.app.gl.ClearFlash
import gravitris.app.gl.EmberBurst
import gravitris.app.gl.GlProgram
import gravitris.app.gl.PieceShadow
import gravitris.app.gl.Shaders
import gravitris.app.gl.UrgencyBar
import gravitris.app.gl.WellFrame
import gravitris.app.haptics.ImpactHaptics
import gravitris.app.input.PlayerIntent
import gravitris.app.perf.FrameSnapshot
import gravitris.app.perf.FrameStats
import gravitris.game.InputFrame
import gravitris.game.Phase
import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The render loop: the ADR 0006 accumulator, the ADR 0007 upload, the frame
 * instrument, and the haptics drain.
 *
 * Everything here runs on the `GLSurfaceView` render thread. The only traffic
 * from other threads is [PlayerIntent] (UI thread in, drained here) and
 * [FrameSnapshot] (published here, read on the UI thread by the readout).
 */
class GameRenderer(
    private val intent: PlayerIntent,
    private val haptics: ImpactHaptics,
    private val onStats: (FrameSnapshot) -> Unit,
    /**
     * Called on the GL thread whenever the well has been laid out, with the
     * world-units-per-dp factor the gesture recogniser needs.
     *
     * A callback rather than the UI thread reading [layout] after posting the
     * insets across: the layout is only recomputed here, on the GL thread, and
     * a UI-thread read racing that would configure the recogniser with the
     * default scale. That is not a subtle failure — drag would run at roughly
     * forty times its intended speed until the next inset change.
     */
    private val onLayout: (Float) -> Unit,
    /**
     * Called on the GL thread the first time the simulation reaches
     * [Phase.GameOver] (the well topped out and the settle grace expired —
     * ADR 0005, landed in the core by the Backend Engineer). The shell posts
     * this to the UI thread to raise the game-over overlay; [restart] clears it.
     *
     * Without this the app would sit frozen on a topped-out stack with no way
     * out — worse than the toy's honest reset, which is exactly why it is wired
     * the moment `Simulation.start()` makes the phase reachable.
     *
     * Carries the final score so the game-over screen shows the value captured on
     * this (GL) thread rather than racing a UI-thread read of the session.
     */
    private val onGameOver: (Int) -> Unit,
    /**
     * Debug-only clear-threshold override, or null for the [SimConfig] default.
     * Threaded into every [GameSession] this builds; `MainActivity` supplies it
     * only on a debuggable build. See [GameSession]'s parameter for why it
     * exists (so `make playthrough` can force a clear on the software emulator).
     */
    private val clearThresholdOverride: Float? = null,
) : GLSurfaceView.Renderer {

    /** Owned by the GL thread. Never read from the UI thread — see [onLayout]. */
    private val layout = WellLayout()

    /**
     * The simulation, which cannot exist until the well does.
     *
     * `SimConfig` is immutable on purpose (ADR 0006: a change means a new
     * `Simulation`, not mutation, and that is what keeps determinism intact),
     * but the well's world height is derived from the device's safe area and is
     * therefore not known until the GL thread has laid out for the first time.
     * So this is null until then, and is replaced — not mutated — if the safe
     * area changes under a rotation or a multi-window resize.
     */
    private var session: GameSession? = null

    /** The config [session] was built from, so a layout pass that produces the
     *  same well does not throw the player's stack away. */
    private var sessionConfig: SimConfig? = null

    /**
     * [gravitris.game.SimState.tick] as last seen. [FrameDriver] runs the tick
     * loop now, so haptics are drained once per rendered frame from the last
     * tick's impacts; comparing the published tick is how the GL thread knows
     * whether the simulation actually advanced this frame, so a frame that ran
     * no tick does not re-accumulate the previous one.
     */
    private var lastTick = 0

    /** Whether [onGameOver] has already fired for the current session, so it is
     *  raised once per game-over rather than every frame the phase holds. Reset
     *  when a session is (re)built. */
    private var wasGameOver = false

    // --- mechanic instrumentation -------------------------------------------
    // Counters that turn "watch gel blobs on a software renderer" into a
    // definite yes/no: a clear is unambiguously a [Phase.Clearing] entry, a
    // spawn an activePieceBody going from -1 to a real index. Surfaced in the
    // readout and logged (with bodies + the fill that triggered the clear) so a
    // play-through can prove a band actually ignites and dissolves rather than
    // the well quietly emptying for some other reason.
    private var clearsSeen = 0
    private var spawnsSeen = 0
    private var wasClearing = false
    private var prevActivePiece = -1

    fun clearCount(): Int = clearsSeen
    fun spawnCount(): Int = spawnsSeen

    /**
     * The per-tick input drain handed to [GameSession.advance]. Field-held so
     * the per-frame render path allocates nothing (ADR 0007). [FrameDriver]
     * calls it with a reused frame immediately before each tick; draining the
     * intent buffer here is what delivers a tap or a drag to exactly one tick,
     * even when catch-up runs several — see [GameSession].
     */
    private val drainTick: (InputFrame) -> Unit = { intent.drainInto(it) }

    /**
     * The largest well this app can produce, built once only to read the core's
     * own published capacity and lattice — never stepped.
     *
     * The real well comes from the display insets at runtime, but its width is
     * fixed [Tunables.WELL_WIDTH_WORLD] and its height capped at
     * [Tunables.WELL_HEIGHT_MAX_WORLD], so this session's [SimState.particleCapacity]
     * is the most any real session can reach.
     *
     * **Consumed, not re-derived.** A tetromino is four cells of material
     * (ADR 0015), so a body holds `4 * lattice²` particles; the core derives its
     * body capacity from the well area, and a shell formula over single-cell
     * `pieceExtent²` under-counts it four-fold and would overflow the vertex
     * buffer on the first piece (backend handoff 0029). So the mesh is sized off
     * [SimState.particleCapacity] and [SimState.bodyLattice] directly, and the
     * game takes the core's pinned lattice (ADR 0014) rather than naming its own.
     * [rebuildSimulationIfWellChanged] asserts each real session fits this bound.
     */
    private val worstCaseState: SimState = Simulation(
        SimConfig(
            wellWidth = Tunables.WELL_WIDTH_WORLD,
            wellHeight = Tunables.WELL_HEIGHT_MAX_WORLD,
        ),
    ).state
    private val maxParticles: Int = worstCaseState.particleCapacity
    private val mesh = BodyMesh(maxParticles = maxParticles, lattice = worstCaseState.bodyLattice)
    private val wellFrame = WellFrame()
    private val urgencyBar = UrgencyBar()

    // --- the visual layer (docs/ux/visual-direction.md) ---------------------
    // The procedural environment (§3), and the two GPU-side halves of the
    // band-clear juice (§7.1 luminance beat, §7.2 ember burst). The HUD and the
    // score pop are the Android View layer's job (§6), not this thread's.
    private val background = Background()
    private val clearFlash = ClearFlash()
    private val emberBurst = EmberBurst()

    /** The §18 soft contact shadow: a second cheap draw of the body geometry,
     *  offset and blended, so pieces read as resting in the world. */
    private val pieceShadow = PieceShadow()

    /** Wall-clock start of the current luminance beat, or 0 when none is
     *  running. Set on a clear onset, read back into an intensity envelope. */
    private var clearFlashStartNanos = 0L
    private val stats = FrameStats()
    private val snapshot = FrameSnapshot()

    private var program = 0
    private var scaleUniform = -1
    private var offsetUniform = -1
    private var compressionGainUniform = -1
    private var shadeTierUniform = -1
    private var timeUniform = -1
    private var bandFillUniform = -1
    private var bandClearProgressUniform = -1
    private var bandBottomYUniform = -1
    private var bandInvHeightUniform = -1

    /**
     * How much of the art direction is running, 0..4. **The measurement dial.**
     *
     * Stage 3B's whole risk is fragment cost — the client's device measures
     * 15.0 ms mean at 17 bodies against a 16.67 ms budget with a nearly-flat
     * shader — and the only honest way to price the art direction is to walk
     * down it on that device, in one session, on one stack, and read the frame
     * time at each step.
     *
     * So this is one control with five positions rather than a set of
     * independent toggles. Independent toggles would give 16 combinations, most
     * of them meaningless, and no ordering; this gives a monotone ladder where
     * each step removes exactly one group of terms and every reading is
     * comparable to the one above it.
     *
     * | level | what runs | what it prices |
     * | ----- | --------- | -------------- |
     * | 4 | tier 3 — everything | the shipped look |
     * | 3 | tier 2 — no band glow | the cost of the glow, incl. its two dynamic uniform-array reads |
     * | 2 | tier 1 — no grain | the cost of the grain's two sines |
     * | 1 | tier 0 — flat + compression | Stage 1's `shade:on`, unchanged |
     * | 0 | tier 0, compression gain 0 | the true floor: one flat lookup, one write |
     *
     * **Levels 4 down to 1 are also the cut list**, in the order things get
     * cut. If the measurement overruns, the response is to lower the default,
     * not to invent a new plan under time pressure — and levels 1 and 0 are the
     * same two baselines Stage 1 built for exactly this subtraction.
     */
    @Volatile
    var shadeLevel = SHADE_LEVEL_MAX
        private set

    /** Wall-clock origin for the shader's animation clock. Set on the first
     *  frame rather than at construction, so time does not start accumulating
     *  while the surface is still being created. */
    private var shaderClockOriginNanos = 0L

    private val scale = FloatArray(2)
    private val offset = FloatArray(2)

    private var lastFrameNanos = 0L
    private var lastStatsPublishNanos = 0L

    /** Set from the UI thread via `queueEvent`, so it is only ever read here. */
    private var paused = false

    // Insets and surface size, written by the UI thread through queueEvent.
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    private var pxPerDp = 1f
    private var layoutDirty = true

    // --- called from the UI thread, marshalled onto the GL thread ------------

    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, density: Float) {
        insetLeft = left
        insetTop = top
        insetRight = right
        insetBottom = bottom
        pxPerDp = density
        layoutDirty = true
    }

    /**
     * Step the shading dial down one level, wrapping back to the top, and
     * discard the frame history.
     *
     * **Downward**, so one repeated key press walks the cut list in the order
     * things would actually be cut, and the client reads five decreasing frame
     * times in sequence rather than having to remember which way the numbers
     * should move.
     *
     * The history reset is the same reason Stage 1 reset it: without it the
     * first second after a change reports a blend of both configurations, which
     * is the one reading that means nothing.
     */
    fun cycleShadeLevel() {
        shadeLevel = if (shadeLevel == 0) SHADE_LEVEL_MAX else shadeLevel - 1
        stats.reset()
    }

    /** Levels 0 and 1 are both shader tier 0; they differ only in whether the
     *  compression gain is zeroed. Above that, level and tier move together. */
    private fun shadeTier(): Int = (shadeLevel - 1).coerceAtLeast(0)

    /**
     * Seconds since the first frame, wrapped at
     * [Tunables.SHADER_TIME_WRAP_SECONDS].
     *
     * The wrap is not cosmetic. `uTime` is read by a `mediump` fragment shader,
     * and mediump cannot represent consecutive integers above 2048 — a session
     * left running would first quantise the pulse into visible steps and then
     * freeze it outright. Wrapping keeps the value small enough that its
     * fractional resolution stays far finer than a frame.
     */
    private fun shaderClock(nowNanos: Long): Float {
        if (shaderClockOriginNanos == 0L) shaderClockOriginNanos = nowNanos
        val elapsed = (nowNanos - shaderClockOriginNanos) / 1_000_000_000.0
        return (elapsed % Tunables.SHADER_TIME_WRAP_SECONDS).toFloat()
    }

    /**
     * Draw the procedural environment for this frame.
     *
     * Aspect is guarded against a not-yet-sized surface (the very first frames):
     * a zero height would divide to infinity and stretch the glows off-screen,
     * so a degenerate surface falls back to a square aspect, which is only ever
     * seen for a frame.
     */
    private fun drawBackground(frameStart: Long) {
        val aspect = if (surfaceHeight > 0) surfaceWidth.toFloat() / surfaceHeight else 1f
        background.draw(aspect, shaderClock(frameStart))
    }

    /**
     * Intensity (0..1) of the screen-wide luminance beat at [nowNanos], or 0
     * when none is running (visual-direction.md §7.1).
     *
     * A triangle envelope over [CLEAR_FLASH_DURATION_NANOS] — up over the first
     * half, down over the second — so the beat rises with the ignition flash and
     * is gone by the time the hold ends, matching the 120ms flash the shader
     * already ramps. Wall-clock nanoseconds, frame-rate independent like every
     * other timing in the spec set.
     */
    private fun clearFlashIntensity(nowNanos: Long): Float {
        if (clearFlashStartNanos == 0L) return 0f
        val elapsed = nowNanos - clearFlashStartNanos
        if (elapsed < 0L || elapsed >= CLEAR_FLASH_DURATION_NANOS) {
            clearFlashStartNanos = 0L
            return 0f
        }
        return gravitris.app.gl.clearFlashEnvelope(elapsed, CLEAR_FLASH_DURATION_NANOS)
    }

    /**
     * Throw away the accumulated frame history and the pending simulated time.
     *
     * Called on the GL thread after the benchmark has blocked it for several
     * seconds. Those frames are an artefact of the measurement, not a property
     * of the game, and leaving them in would show the client a multi-second
     * `max` and a burst of `jank` that describe nothing real. The accumulator is
     * reset for the same reason the pause path resets it: without that, the
     * first frame back would run the full catch-up budget and the piece would
     * jump.
     */
    fun discardFrameHistory() {
        lastFrameNanos = 0L
        session?.resetAccumulator()
        stats.reset()
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            haptics.cancel()
        } else {
            // Discard the accumulated pause duration. Without this, resuming
            // would run the accumulator's full catch-up budget on the first
            // frame and the piece would jump.
            lastFrameNanos = 0L
            session?.resetAccumulator()
            stats.reset()
        }
    }

    // --- GLSurfaceView.Renderer ----------------------------------------------

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Reached on first start AND after context loss, so everything GL is
        // built from scratch here — ADR 0010 §6. There are no texture assets
        // to reload, which is what makes that cheap.
        program = GlProgram.build(
            Shaders.VERTEX,
            Shaders.fragment(Palette.SIZE, Palette.PIECE_COUNT, BAND_COUNT),
        )
        scaleUniform = GLES30.glGetUniformLocation(program, "uScale")
        offsetUniform = GLES30.glGetUniformLocation(program, "uOffset")
        compressionGainUniform = GLES30.glGetUniformLocation(program, "uCompressionGain")
        shadeTierUniform = GLES30.glGetUniformLocation(program, "uShadeTier")
        timeUniform = GLES30.glGetUniformLocation(program, "uTime")
        bandFillUniform = GLES30.glGetUniformLocation(program, "uBandFill")
        bandClearProgressUniform = GLES30.glGetUniformLocation(program, "uBandClearProgress")
        bandBottomYUniform = GLES30.glGetUniformLocation(program, "uBandBottomY")
        bandInvHeightUniform = GLES30.glGetUniformLocation(program, "uBandInvHeight")
        uploadConstantUniforms()

        mesh.create()
        wellFrame.create()
        urgencyBar.create()
        background.create()
        clearFlash.create()
        emberBurst.create()
        pieceShadow.create()

        // The environment pass (Background) now paints the frame every frame, so
        // this clear colour is only ever seen for the one frame before the well
        // is laid out (the session-null path). Kept true black so that frame does
        // not flash a lighter value on the OLED panel before the gradient lands.
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        // No depth test and no blending. The geometry is 2D, drawn painter's
        // order, and fully opaque — enabling either would cost bandwidth on a
        // tile-based GPU for no visible difference.
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        layoutDirty = true
        lastFrameNanos = 0L
        session?.resetAccumulator()
        shaderClockOriginNanos = 0L
        stats.reset()
    }

    /**
     * Push the uniforms that never change after the program is built.
     *
     * They are set here rather than in [onDrawFrame] because a uniform's value
     * lives in the program object and survives until the program is relinked,
     * so re-uploading the palette and eleven shading constants every frame
     * would be sixty driver calls a second that write values already there.
     * Everything left in the draw path genuinely varies per frame.
     *
     * Reached on first start and after context loss, and correct in both cases:
     * context loss destroys the program, and this runs immediately after the
     * new one is built.
     */
    private fun uploadConstantUniforms() {
        GLES30.glUseProgram(program)
        GLES30.glUniform3fv(
            GLES30.glGetUniformLocation(program, "uPalette"),
            Palette.SIZE, Palette.asVec3Array(), 0,
        )
        GLES30.glUniform1fv(
            GLES30.glGetUniformLocation(program, "uGrainScale"),
            Palette.SIZE, foldedGrainScales(), 0,
        )
        fun set(name: String, value: Float) =
            GLES30.glUniform1f(GLES30.glGetUniformLocation(program, name), value)

        set("uCompressionMax", Tunables.COMPRESSION_MAX_DARKEN)
        set("uSubsurfaceGain", Tunables.SUBSURFACE_GAIN)
        set("uSubsurfaceSaturate", Tunables.SUBSURFACE_SATURATE)
        set("uSubsurfaceDarken", Tunables.SUBSURFACE_DARKEN)
        set("uContactGain", Tunables.CONTACT_GAIN)
        set("uRimGain", Tunables.RIM_GAIN)
        set("uGrainGain", Tunables.GRAIN_GAIN)
        set("uGrainFrequency", Tunables.GRAIN_FREQUENCY)
        set("uDitherGain", Tunables.DITHER_GAIN)
        // §14/§16 glossy jelly candy material constants.
        set("uSpecularGain", Tunables.SPECULAR_GAIN)
        set("uSpecularSharpness", Tunables.SPECULAR_SHARPNESS)
        set("uCornerRound", Tunables.CORNER_ROUND)
        set("uGlowGain", Tunables.GLOW_GAIN)
        set("uGlowCapRatio", Tunables.GLOW_CAP_RATIO)
        set("uIgnitionCapRatio", Tunables.IGNITION_CAP_RATIO)
        GLES30.glUniform3f(
            GLES30.glGetUniformLocation(program, "uIgnitionColor"),
            // color-glow-hot #FFF4E0 — the ignition flash (docs/ux/tokens.md).
            1.0f, 0.957f, 0.878f,
        )
        set("uPulseRateSlow", Tunables.PULSE_RATE_SLOW)
        set("uPulseRateFast", Tunables.PULSE_RATE_FAST)
        set("uPulseAmplitude", Tunables.PULSE_AMPLITUDE)
        set("uShimmerGain", Tunables.SHIMMER_GAIN)
    }

    /**
     * The per-archetype grain scale actually uploaded: the palette's identity
     * grain (`piece-identity.md`'s tertiary cue) times the core's footprint
     * compensation (`SimState.grainScaleCompensation`, §15 / backend handoff
     * 0036), exactly `uGrainScale[a] = paletteGrainScale[a] *
     * grainScaleCompensation[a]`.
     *
     * With body-wide UV a long piece would otherwise carry coarser grain than a
     * compact one; the compensation cancels the footprint term so every piece
     * regains the SAME per-cell grain frequency it had before, now continuous
     * across the whole piece. Static — the compensation is constant for the run
     * (it depends only on the frozen `PieceShapes` cell layouts, not on the
     * well), so this is folded once here with the palette, never per frame, and
     * read from the never-stepped [worstCaseState].
     *
     * Index-aligned because [Palette.pieceHue] is the identity map for every
     * archetype the core deals (0..6 → 0..6, see `Palette`): hue index `i` is
     * archetype `i`, so `grainScaleCompensation[i]` is the right factor for
     * `uGrainScale[i]`. Slot 7 (the well surface) has no archetype and keeps its
     * grain of 1.0 unmultiplied — it is never read anyway (the frame draws at UV
     * (0,0) where the grain term is zero).
     */
    private fun foldedGrainScales(): FloatArray {
        val base = Palette.grainScales()
        val comp = worstCaseState.grainScaleCompensation
        return FloatArray(base.size) { i ->
            base[i] * if (i < comp.size) comp[i] else 1f
        }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES30.glViewport(0, 0, width, height)
        layoutDirty = true
    }

    override fun onDrawFrame(unused: GL10?) {
        val frameStart = System.nanoTime()

        if (layoutDirty) {
            layout.update(
                surfaceWidth, surfaceHeight,
                insetLeft, insetTop, insetRight, insetBottom,
                pxPerDp,
            )
            wellFrame.layout(layout)
            layoutDirty = false
            rebuildSimulationIfWellChanged()
            onLayout(layout.worldPerDp)
        }

        val session = this.session
        if (session == null) {
            // No well yet, so nothing to simulate. Draw the environment anyway,
            // so the very first frame the player sees is the world, not black.
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            drawBackground(frameStart)
            return
        }

        val alpha = advanceSimulation(session, frameStart)

        val st = session.state

        // Instrument the mechanic. A clear is exactly a [Phase.Clearing] entry;
        // counting the *transition* (not the frames it holds) and logging the
        // body count and the fill that triggered it is the unambiguous "a band
        // ignited and dissolved" signal. A spawn is activePieceBody leaving -1.
        val phase = st.phase
        val clearing = phase is Phase.Clearing
        if (clearing && !wasClearing) {
            clearsSeen++
            android.util.Log.i(
                LOG_TAG,
                "clear #$clearsSeen tick=${st.tick} bodies=${st.bodyCount} " +
                    "maxFill=${maxBandFill(st.bandFill)}",
            )
            // Fire the band-clear juice (visual-direction.md §7): the screen-wide
            // luminance beat synced to the ignition flash, and an ember burst
            // spawned across every clearing band's width. Both are one-shot
            // events read off the same Phase.Clearing the counter above logs, so
            // they cannot drift from "a band actually ignited". Read the bands
            // now, do not retain the phase — Clearing is a reused, mutated
            // instance (its contract).
            clearFlashStartNanos = frameStart
            for (band in phase.bands) {
                val centerY = st.bandBottomY + (band + 0.5f) * st.bandHeight
                emberBurst.spawn(centerY, layout.widthWorld, frameStart)
            }
        }
        wasClearing = clearing
        val active = st.activePieceBody
        if (prevActivePiece < 0 && active >= 0) {
            spawnsSeen++
            android.util.Log.i(LOG_TAG, "spawn #$spawnsSeen tick=${st.tick} bodies=${st.bodyCount}")
        }
        prevActivePiece = active

        // Game over is terminal until a restart, so raise the overlay once
        // rather than every frame the phase holds. Read-don't-retain: the phase
        // is read fresh here and never captured (Phase.Overflow is a reused,
        // mutated instance — see its contract). Overflow itself needs no handling
        // — the stack simply renders while its grace counts down, then either
        // settles back to Playing or crosses into GameOver here.
        if (st.phase is Phase.GameOver) {
            if (!wasGameOver) {
                wasGameOver = true
                android.util.Log.i(LOG_TAG, "game over tick=${st.tick} after $clearsSeen clears, $spawnsSeen spawns")
                onGameOver(st.score)
            }
        }

        // A clear removes whole bodies, so the set of bodies shrinks mid-session.
        // The archetype cache is keyed on the body count and BodyMesh.upload
        // rewrites the statics whenever that count changes in *either* direction
        // (BodyMesh.kt: `state.bodyCount != uploadedBodies`), so a clear needs no
        // separate signal here. The toy's whole-well reset, which did, is gone
        // with the toy; archetypes are still invalidated on a well-geometry
        // rebuild in rebuildSimulationIfWellChanged.
        val particles = mesh.upload(session.state, alpha)

        // Everything above is our own work: stepping the simulation and
        // building the vertex buffer. Everything below is submission. The
        // split is the measurement — see FrameStats.
        val workNanos = System.nanoTime() - frameStart

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        // The environment first, before the well frame and the bodies — the base
        // layer everything else is painted over (visual-direction.md §3).
        drawBackground(frameStart)

        GLES30.glUseProgram(program)

        layout.clipScale(scale)
        layout.clipOffset(offset)
        GLES30.glUniform2f(scaleUniform, scale[0], scale[1])
        GLES30.glUniform2f(offsetUniform, offset[0], offset[1])

        // Level 0 is the only one that zeroes the gain; every level above it
        // carries the compression term at its tuned strength. The term is the
        // client-approved weight cue, so it survives every cut except the one
        // whose entire purpose is to measure the floor without it.
        GLES30.glUniform1f(
            compressionGainUniform,
            if (shadeLevel > 0) Tunables.COMPRESSION_GAIN else 0f,
        )
        GLES30.glUniform1f(timeUniform, shaderClock(frameStart))
        GLES30.glUniform1i(shadeTierUniform, shadeTier())

        val state = session.state
        GLES30.glUniform1f(bandBottomYUniform, state.bandBottomY)
        GLES30.glUniform1f(bandInvHeightUniform, 1f / state.bandHeight)
        GLES30.glUniform1fv(bandFillUniform, BAND_COUNT, state.bandFill, 0)
        GLES30.glUniform1fv(
            bandClearProgressUniform, BAND_COUNT, state.bandClearProgress, 0,
        )

        wellFrame.draw()

        // §18 soft contact shadow: a second draw of the same body geometry,
        // offset down+right and blended in a darkened tray tone, BEFORE the real
        // bodies so each piece paints over its own shadow. This is the one pass
        // that turns GL_BLEND on, and only for itself — restored to the global
        // "blend off" immediately after (onSurfaceCreated). It reuses the mesh's
        // VAO/IBO through mesh.draw() while the shadow program is bound. Gated on
        // the shadow tier being live so it rides the cut ladder (§18): the well
        // is empty of shadows to draw when there are no bodies anyway, so the
        // indexCount==0 guard in mesh.draw() also makes this free on an empty
        // well.
        if (shadeLevel >= SHADOW_MIN_SHADE_LEVEL) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            pieceShadow.use(scale, offset)
            mesh.draw()
            GLES30.glDisable(GLES30.GL_BLEND)
            // Re-bind the gel program the shadow pass switched away from; its
            // uniforms still live in the program object, so nothing is re-set.
            GLES30.glUseProgram(program)
        }

        // §16 rounded corners: alpha-to-coverage is enabled ONLY for the body
        // draw (the gel shader writes a corner-coverage alpha; the walls write
        // 1.0). It is a no-op without MSAA, and must stay off for the blended
        // shadow pass above, so it is scoped tightly here.
        GLES30.glEnable(GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE)
        mesh.draw()
        GLES30.glDisable(GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE)

        // The positioning-window countdown (ADR 0016), drawn last so it reads
        // above the stack. Its own flat program — the next frame rebinds the gel
        // program before anything else, so leaving it bound here is harmless.
        // Zero fraction (the piece is falling, not positioning) is a no-op.
        val urgency = PositioningUrgency.fraction(
            state.positioningTicksRemaining, state.positioningWindowTicks,
        )
        urgencyBar.draw(urgency, layout.widthWorld, layout.heightWorld, scale, offset)

        // The band-clear juice, drawn last so it sits over the stack: the
        // screen-wide luminance beat (§7.1) and the ember burst (§7.2). Both
        // manage their own additive blending and restore the renderer's global
        // "blend off"; a frame with no active clear draws neither.
        clearFlash.draw(clearFlashIntensity(frameStart))
        emberBurst.draw(frameStart, scale, offset)

        haptics.flush()

        stats.record(frameStart, workNanos)
        publishStats(frameStart, particles)
    }

    /**
     * Feed this frame's real elapsed time to [FrameDriver] (through the
     * [GameSession]), run whatever whole ticks it affords, and return the render
     * interpolation factor for `lerp(prev, current, alpha)`.
     *
     * The accumulator, the no-clamp policy and the catch-up drop all live in
     * [FrameDriver] now (ADR 0013), not here. The old shell loop clamped the
     * delta — `min(frameDelta, MAX_FRAME_DELTA)` — which discards wall-clock time
     * on an overrun and dilates the game, exactly what the client ruled out
     * (*"frames skippen is prima ... maar niet vertragen"*). That clamp is gone
     * with the toy: the real delta is passed straight through, and FrameDriver
     * answers a slow frame by running more ticks — or dropping time it cannot
     * afford — never by running a bigger one.
     *
     * Input is drained *per tick* through [drainTick], so a tap or a drag delta
     * lands on exactly one tick even when catch-up runs several; passing one
     * frame across the whole catch-up (the plain `advance(delta, input)`) would
     * multiply the drag and fire the one-shot on every tick.
     *
     * @return the interpolation factor for this frame.
     */
    private fun advanceSimulation(session: GameSession, frameStart: Long): Float {
        if (paused) return 0f

        val previous = lastFrameNanos
        lastFrameNanos = frameStart
        if (previous == 0L) return 0f

        // Real elapsed time, never clamped (ADR 0013). coerceAtLeast(0) guards
        // only against a non-monotonic clock reading — FrameDriver rejects a
        // negative delta outright, treating it as the caller bug it is.
        val deltaSeconds = (frameStart - previous).coerceAtLeast(0L).toFloat() / 1_000_000_000f
        val alpha = session.advance(deltaSeconds, drainTick)

        // Haptics once per frame from the last tick's impacts — FrameDriver owns
        // the tick loop now. Gated on the published tick advancing, so a frame
        // that ran no tick does not re-accumulate the previous one; the honest
        // cost is that a catch-up frame that ran several contributes only its
        // last. Missing an impact pulse while the device is below its hardware
        // floor (droppedTicks rising) is acceptable degradation — the readout
        // already surfaces that as jank.
        val tick = session.state.tick
        if (tick != lastTick) {
            lastTick = tick
            haptics.accumulate(session.state.impacts)
        }

        return alpha
    }

    /**
     * Build or rebuild the simulation for the current well geometry.
     *
     * Rebuilt only when the derived config actually differs, so an inset change
     * that leaves the well the same size — a status-bar icon appearing, say —
     * does not silently empty the player's well. When it does differ, the stack
     * is lost, which is the honest outcome: the world the material settled in
     * no longer exists, and carrying positions across would drop pieces
     * outside the new walls.
     */
    private fun rebuildSimulationIfWellChanged() {
        val config = SimConfig(
            // Lattice is not named here: the core pins the shipping tier (4 for
            // tetrominoes, ADR 0014) as its own default, and the mesh sized above
            // consumed that same default via the worst-case state. Naming it here
            // would let the shell and the core disagree on the tier.
            wellWidth = layout.widthWorld,
            wellHeight = layout.heightWorld,
            // The app's shipped difficulty (live-tunable), which becomes the
            // initial MechanicTuning.clearThreshold. Not the core's 0.90 default
            // — that stays as the brief's reference for tests. See Tunables.
            clearThreshold = Tunables.CLEAR_THRESHOLD,
        )
        // The fragment shader was compiled with BAND_COUNT baked in as an array
        // bound, before any simulation existed. If a future config changed the
        // band count, uBandFill would be uploaded at the wrong length and the
        // shader would index out of range — undefined behaviour, which on a
        // real driver is not a wrong colour but whatever that GPU does.
        check(config.bandCount == BAND_COUNT) {
            "the shader was compiled for $BAND_COUNT bands but SimConfig asks for " +
                "${config.bandCount}; recompile the fragment shader or stop overriding bandCount"
        }

        if (config == sessionConfig) return
        buildSession(config)
    }

    /**
     * Restart after a game over: rebuild the session for the current well and
     * clear the game-over latch.
     *
     * Reconstruct rather than mutate — `SimConfig` is immutable by design
     * (ADR 0006) and [GameSession] centralises `Simulation` + `start()` +
     * `FrameDriver`, so a fresh one *is* the restart, the same shape the toy's
     * reset had. Called on the GL thread (`MainActivity` queues it) once the
     * player dismisses the overlay.
     */
    fun restart() {
        buildSession(sessionConfig ?: return)
    }

    /** Build a fresh [GameSession] for [config] and reset the per-session render
     *  state. Shared by the well-geometry rebuild and [restart]. */
    private fun buildSession(config: SimConfig) {
        val session = GameSession(config, clearThresholdOverride)
        // The mesh is sized once, for the largest well; every real well is
        // smaller, so its capacity must fit. Asserted rather than trusted: both
        // the mesh bound and this session read SimState.particleCapacity, so this
        // only fails if a real well is somehow larger than the worst-case one —
        // which would mean the worst-case config above stopped being the worst
        // case, not that a shell formula drifted from the core.
        check(session.state.particleCapacity <= maxParticles) {
            "the mesh is sized for $maxParticles particles but this well's sim capacity is " +
                "${session.state.particleCapacity}; the worst-case well is no longer the largest " +
                "(check Tunables.WELL_WIDTH_WORLD / WELL_HEIGHT_MAX_WORLD against the layout)"
        }
        check(session.state.bodyLattice == worstCaseState.bodyLattice) {
            "this session's lattice ${session.state.bodyLattice} differs from the mesh's " +
                "${worstCaseState.bodyLattice}; the index topology was built for one tier only"
        }
        this.session = session
        sessionConfig = config
        lastTick = 0
        wasGameOver = false
        // Transition trackers reset with the session; the cumulative clear/spawn
        // counters do not — they are a lifetime instrument across a restart.
        wasClearing = false
        prevActivePiece = -1
        // A burst or beat from the previous well must not survive into the next.
        emberBurst.reset()
        clearFlashStartNanos = 0L
        mesh.invalidateArchetypes()
        session.resetAccumulator()
        lastFrameNanos = 0L
        stats.reset()
    }

    /**
     * Push a snapshot to the readout a few times a second.
     *
     * The *statistics* are computed from every frame — nothing is smoothed or
     * skipped, so a single 40ms hitch still shows up in `max` and `jank`. Only
     * the on-screen text is refreshed at a lower rate, because updating a
     * `TextView` at 60Hz would put measurable UI-thread work into the frame we
     * are trying to measure. Sampling the display, not the data.
     */
    private fun publishStats(nowNanos: Long, particles: Int) {
        if (nowNanos - lastStatsPublishNanos < STATS_PUBLISH_INTERVAL_NANOS) return
        lastStatsPublishNanos = nowNanos

        if (!stats.snapshot(nowNanos, snapshot)) return
        onStats(snapshot)
    }

    /** Geometry actually submitted this frame, for the readout. */
    fun trianglesDrawn(): Int = mesh.trianglesDrawn()

    fun bodyCount(): Int = session?.state?.bodyCount ?: 0

    /** Current score, for the HUD. Wired to the real field, which is 0 until
     *  scoring (D8) lands — the HUD presents the frame, it does not fake it. */
    fun score(): Int = session?.state?.score ?: 0

    /** Current level, for the HUD. 1 until the difficulty ramp (D8) lands. */
    fun level(): Int = session?.state?.level ?: 1

    fun dynamicBytesPerFrame(): Int =
        (session?.state?.particleCount ?: 0) * gravitris.app.gl.BodyMesh.VERTEX_STRIDE_BYTES

    /** The fullest band right now, two decimals, for the clear log. Hand-rolled
     *  rather than `.max()` to avoid an allocation and a nullable on the array. */
    private fun maxBandFill(fill: FloatArray): String {
        var m = 0f
        for (f in fill) if (f > m) m = f
        return String.format(java.util.Locale.US, "%.2f", m)
    }

    companion object {
        /** logcat tag for the mechanic instrumentation (clear/spawn/game-over). */
        private const val LOG_TAG = "GravitrisPlay"

        /** ~4Hz. Fast enough to watch a number move, slow enough not to
         *  pollute the measurement. */
        private const val STATS_PUBLISH_INTERVAL_NANOS = 250_000_000L

        /** Top of the shading dial — the full art direction. See [shadeLevel]. */
        const val SHADE_LEVEL_MAX = 4

        /**
         * Lowest [shadeLevel] at which the §18 contact-shadow pass runs, folding
         * it into the same cut ladder as the shader tiers (§18's recommendation).
         *
         * 2 keeps the shadow on for the three highest levels (the shipped look
         * and the two steps below it) and drops it for levels 1 and 0 — the two
         * Stage-1 baselines whose whole purpose is to measure the floor without
         * the round-3 additions. The shadow is high legibility value (it is much
         * of why the candy reads as physical) but a real vertex + blend cost, so
         * it belongs in the measured ladder, not assumed free. Where exactly it
         * should sit is an on-device call once the frame time is known.
         */
        const val SHADOW_MIN_SHADE_LEVEL = 2

        /**
         * Lifetime of the screen-wide luminance beat, 120ms (visual-direction.md
         * §7.1), in nanoseconds. Synced to the shader's own 120ms ignition flash
         * so the two read as one event.
         */
        const val CLEAR_FLASH_DURATION_NANOS = 120_000_000L

        /**
         * Bands the shader is compiled for, taken from the core's own default
         * rather than restated as a shell constant.
         *
         * The shader declares `uBandFill` as a fixed-size array and indexing it
         * out of range is undefined behaviour, so this figure has to be the one
         * the simulation actually uses. The shell never overrides
         * `SimConfig.bandCount`, and [rebuildSimulationIfWellChanged] asserts
         * that rather than trusting it — the shader is compiled once, before
         * any simulation exists, so a config that disagreed would be discovered
         * as corrupted glow rather than as an error.
         */
        val BAND_COUNT = SimConfig().bandCount
    }
}
