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
import gravitris.app.toy.SquishToy
import gravitris.game.InputFrame
import gravitris.game.SimConfig
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
    private var toy: SquishToy? = null

    /** The config [toy] was built from, so a layout pass that produces the same
     *  well does not throw the player's stack away. */
    private var toyConfig: SimConfig? = null

    private val inputFrame = InputFrame()
    private val mesh = BodyMesh(maxBodies = Tunables.TOY_MAX_BODIES, lattice = Tunables.TOY_LATTICE)
    private val wellFrame = WellFrame()
    private val stats = FrameStats()
    private val snapshot = FrameSnapshot()

    private var program = 0
    private var scaleUniform = -1
    private var offsetUniform = -1
    private var paletteUniform = -1
    private var compressionGainUniform = -1
    private var compressionMaxUniform = -1

    /**
     * Whether compressed material darkens. Toggled at runtime so the same
     * device, in the same session, can be measured with and without the one
     * shading term Stage 1 carries — Stage 3's frame time minus Stage 1's is
     * only the true price of the art direction if we know what this already
     * costs. Off restores the true floor: one flat varying, one uniform
     * lookup, one write.
     */
    @Volatile
    var compressionDarkening = true
        private set

    private val scale = FloatArray(2)
    private val offset = FloatArray(2)

    private var accumulatorNanos = 0L
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
     * Flip the compression term and discard the frame history, so the readout
     * starts measuring the new configuration immediately instead of averaging
     * across the change. Without the reset the first second after a toggle
     * would report a blend of both, which is the one reading that means
     * nothing.
     */
    fun toggleCompressionDarkening() {
        compressionDarkening = !compressionDarkening
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
            accumulatorNanos = 0L
            stats.reset()
        }
    }

    // --- GLSurfaceView.Renderer ----------------------------------------------

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Reached on first start AND after context loss, so everything GL is
        // built from scratch here — ADR 0010 §6. There are no texture assets
        // to reload, which is what makes that cheap.
        program = GlProgram.build(Shaders.VERTEX, Shaders.fragment(Palette.SIZE))
        scaleUniform = GLES30.glGetUniformLocation(program, "uScale")
        offsetUniform = GLES30.glGetUniformLocation(program, "uOffset")
        paletteUniform = GLES30.glGetUniformLocation(program, "uPalette")
        compressionGainUniform = GLES30.glGetUniformLocation(program, "uCompressionGain")
        compressionMaxUniform = GLES30.glGetUniformLocation(program, "uCompressionMax")

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
        accumulatorNanos = 0L
        stats.reset()
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

        val toy = this.toy
        if (toy == null) {
            // No well yet, so nothing to simulate or draw. Clearing keeps the
            // surface defined rather than showing whatever the buffer held.
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        val alpha = advanceSimulation(toy, frameStart)

        val particles = mesh.upload(toy.state, alpha)

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
        GLES30.glUniform3fv(paletteUniform, Palette.SIZE, Palette.asVec3Array(), 0)
        GLES30.glUniform1f(
            compressionGainUniform,
            if (compressionDarkening) Tunables.COMPRESSION_GAIN else 0f,
        )
        GLES30.glUniform1f(compressionMaxUniform, Tunables.COMPRESSION_MAX_DARKEN)

        wellFrame.draw()
        mesh.draw()

        haptics.flush()

        stats.record(frameStart, workNanos)
        publishStats(frameStart, particles)
    }

    /**
     * The ADR 0006 accumulator, verbatim in structure:
     *
     * ```
     * accumulator += min(frameDelta, MAX_FRAME_DELTA)
     * while (accumulator >= TICK) { sim.step(input); accumulator -= TICK }
     * alpha = accumulator / TICK
     * ```
     *
     * @return the interpolation factor for this frame.
     */
    private fun advanceSimulation(toy: SquishToy, frameStart: Long): Float {
        if (paused) return 0f

        val previous = lastFrameNanos
        lastFrameNanos = frameStart
        if (previous == 0L) return 0f

        val maxDelta = Tunables.TICK_NANOS * Tunables.MAX_CATCH_UP_TICKS
        // The clamp is the anti-spiral-of-death guard: after a stall, we drop
        // simulated time rather than trying to catch up on it, because
        // catching up costs more time and deepens the stall.
        accumulatorNanos += (frameStart - previous).coerceIn(0L, maxDelta)

        while (accumulatorNanos >= Tunables.TICK_NANOS) {
            // drainInto writes all four fields every tick, so the one-shot
            // flags are cleared by construction. The core deliberately does not
            // clear them itself — mutating the caller's frame would make a
            // recorded input sequence behave differently on replay
            // (handoff 0006) — so a shell that reused a frame without
            // rewriting it would spin the piece every tick. This one does not.
            intent.drainInto(inputFrame)
            toy.step(inputFrame)
            haptics.accumulate(toy.state.impacts)
            accumulatorNanos -= Tunables.TICK_NANOS
        }

        return accumulatorNanos.toFloat() / Tunables.TICK_NANOS
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
        if (config == toyConfig) return

        toyConfig = config
        toy = SquishToy(config, maxBodies = Tunables.TOY_MAX_BODIES)
        mesh.invalidateArchetypes()
        accumulatorNanos = 0L
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

    fun bodyCount(): Int = toy?.state?.bodyCount ?: 0

    fun dynamicBytesPerFrame(): Int =
        (toy?.state?.particleCount ?: 0) * gravitris.app.gl.BodyMesh.VERTEX_STRIDE_BYTES

    private companion object {
        /** ~4Hz. Fast enough to watch a number move, slow enough not to
         *  pollute the measurement. */
        const val STATS_PUBLISH_INTERVAL_NANOS = 250_000_000L
    }
}
