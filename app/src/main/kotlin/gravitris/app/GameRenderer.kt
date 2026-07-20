package gravitris.app

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import gravitris.app.gl.BodyMesh
import gravitris.app.gl.GlProgram
import gravitris.app.gl.Shaders
import gravitris.app.gl.WellFrame
import gravitris.app.haptics.ImpactHaptics
import gravitris.app.input.PlayerIntent
import gravitris.app.perf.FrameSnapshot
import gravitris.app.perf.FrameStats
import gravitris.game.InputFrame
import gravitris.game.SimConfig
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import kotlin.math.max

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

    /**
     * The per-tick input drain handed to [GameSession.advance]. Field-held so
     * the per-frame render path allocates nothing (ADR 0007). [FrameDriver]
     * calls it with a reused frame immediately before each tick; draining the
     * intent buffer here is what delivers a tap or a drag to exactly one tick,
     * even when catch-up runs several — see [GameSession].
     */
    private val drainTick: (InputFrame) -> Unit = { intent.drainInto(it) }

    /**
     * Most bodies the mesh must ever hold — the core's own derived cap for the
     * largest well this app can produce.
     *
     * The toy sized this at a fixed 40 because it reset the well before then;
     * the real game deals pieces until the spawn region is blocked, so it fills
     * to `SoftBodyWorld.maxBodies` — `max(64, 2*ceil(wellArea/pieceExtent^2))`.
     * The well width is fixed and its height is capped at
     * [Tunables.WELL_HEIGHT_MAX_WORLD], so the worst case is a known config,
     * computed here once rather than resizing GL buffers on every rotation.
     *
     * This mirrors the core formula; [rebuildSimulationIfWellChanged] asserts the
     * actual session's capacity fits, so a drift in either formula fails loudly
     * at build-a-well time instead of overflowing the vertex buffer mid-draw.
     */
    private val maxBodies: Int = run {
        val worst = SimConfig(
            lattice = Tunables.TOY_LATTICE,
            wellWidth = Tunables.WELL_WIDTH_WORLD,
            wellHeight = Tunables.WELL_HEIGHT_MAX_WORLD,
        )
        val ext = worst.pieceExtent
        max(64, 2 * ceil(worst.wellWidth * worst.wellHeight / (ext * ext).toDouble()).toInt())
    }
    private val mesh = BodyMesh(maxBodies = maxBodies, lattice = Tunables.TOY_LATTICE)
    private val wellFrame = WellFrame()
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

        // color-bg #000000 (docs/ux/tokens.md). True black, not near-black:
        // on this device's OLED panel only true black costs near-zero power
        // per pixel, and it gives the Stage 3 glow maximum contrast headroom.
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
            Palette.SIZE, Palette.grainScales(), 0,
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
            // No well yet, so nothing to simulate or draw. Clearing keeps the
            // surface defined rather than showing whatever the buffer held.
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        val alpha = advanceSimulation(session, frameStart)

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
        mesh.draw()

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
            lattice = Tunables.TOY_LATTICE,
            wellWidth = layout.widthWorld,
            wellHeight = layout.heightWorld,
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

        sessionConfig = config
        val session = GameSession(config, clearThresholdOverride)
        // The mesh is sized once, for the largest well; every real well is
        // smaller, so its capacity must fit. Asserted rather than trusted: if
        // the worst-case estimate above and SoftBodyWorld.maxBodies ever drift
        // apart, this fails loudly here instead of overflowing the vertex buffer
        // the first time a well fills past the mesh's size.
        val particlesPerBody = Tunables.TOY_LATTICE * Tunables.TOY_LATTICE
        check(session.state.particleCapacity <= maxBodies * particlesPerBody) {
            "the mesh is sized for $maxBodies bodies ($particlesPerBody particles each) but this " +
                "well's sim capacity is ${session.state.particleCapacity} particles; the worst-case " +
                "body estimate has drifted from SoftBodyWorld.maxBodies"
        }
        this.session = session
        lastTick = 0
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

    fun dynamicBytesPerFrame(): Int =
        (session?.state?.particleCount ?: 0) * gravitris.app.gl.BodyMesh.VERTEX_STRIDE_BYTES

    companion object {
        /** ~4Hz. Fast enough to watch a number move, slow enough not to
         *  pollute the measurement. */
        private const val STATS_PUBLISH_INTERVAL_NANOS = 250_000_000L

        /** Top of the shading dial — the full art direction. See [shadeLevel]. */
        const val SHADE_LEVEL_MAX = 4

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
