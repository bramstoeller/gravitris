package gravitris.app

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import gravitris.app.gl.BodyMesh
import gravitris.app.gl.GlProgram
import gravitris.app.gl.Shaders
import gravitris.app.gl.WellFrame
import gravitris.app.harness.RenderHarness
import gravitris.app.haptics.ImpactHaptics
import gravitris.app.input.PlayerIntent
import gravitris.app.perf.FrameSnapshot
import gravitris.app.perf.FrameStats
import gravitris.app.sim.InputFrame
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

    private val harness = RenderHarness(layout)
    private val inputFrame = InputFrame()
    private val mesh = BodyMesh(maxBodies = MAX_BODIES, lattice = LATTICE)
    private val wellFrame = WellFrame()
    private val stats = FrameStats()
    private val snapshot = FrameSnapshot()

    private var program = 0
    private var scaleUniform = -1
    private var offsetUniform = -1
    private var paletteUniform = -1

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
            onLayout(layout.worldPerDp)
        }

        val alpha = advanceSimulation(frameStart)

        val particles = mesh.upload(harness, alpha)

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
    private fun advanceSimulation(frameStart: Long): Float {
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
            intent.drainInto(inputFrame)
            harness.step(inputFrame)
            haptics.accumulate(harness.impacts)
            accumulatorNanos -= Tunables.TICK_NANOS
        }

        return accumulatorNanos.toFloat() / Tunables.TICK_NANOS
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

    fun bodyCount(): Int = harness.bodyCount

    fun particleCount(): Int = harness.particleCount

    fun dynamicBytesPerFrame(): Int = harness.particleCount * 2 * Float.SIZE_BYTES

    private companion object {
        /** Matches the harness and ADR 0007's default-tier budget: 60 bodies
         *  x 25 particles = 1500 particles. */
        const val MAX_BODIES = 60
        const val LATTICE = 5

        /** ~4Hz. Fast enough to watch a number move, slow enough not to
         *  pollute the measurement. */
        const val STATS_PUBLISH_INTERVAL_NANOS = 250_000_000L
    }
}
