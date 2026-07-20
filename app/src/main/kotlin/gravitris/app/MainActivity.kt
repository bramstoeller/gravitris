package gravitris.app

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import gravitris.app.haptics.ImpactHaptics
import gravitris.app.input.PlayerIntent
import gravitris.app.perf.FrameTimeReadout
import gravitris.app.perf.SolverBenchmark
import nl.brainbuilders.gravitris.R

/**
 * The game shell: one GL surface, one frame-time readout, edge-to-edge.
 *
 * Plain `Activity`, no AndroidX, no Compose — ADR 0010 §7. The game is a
 * single GL surface and the only other element on screen is a debug readout,
 * so a UI framework would earn nothing and would be the first third-party
 * dependency in a project whose merged manifest is deliberately auditable.
 *
 * ## Edge-to-edge (ADR 0010 §1)
 *
 * At `targetSdk 35+` edge-to-edge is enforced and cannot be opted out of. The
 * GL surface therefore fills the display including under the status bar,
 * navigation bar and camera cutout — **but the playfield does not**. Insets are
 * read here and handed to [WellLayout] through the renderer, which lays the
 * well out inside them while the surface keeps bleeding to the screen edge.
 *
 * ADR 0010 flags this as "the item most likely to cause rework if it is not
 * designed in from the first screen", which is why the well's world geometry
 * is derived from insets at runtime rather than being a constant anywhere.
 */
class MainActivity : Activity() {

    private lateinit var gameView: GameView
    private lateinit var renderer: GameRenderer
    private lateinit var readout: FrameTimeReadout
    private lateinit var haptics: ImpactHaptics

    private val playerIntent = PlayerIntent()
    private val renderContext = FrameTimeReadout.RenderContext()

    private var paused = false

    /** Guards against a second benchmark being queued while one is running.
     *  Touched only on the UI thread. */
    private var benchmarkRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        goEdgeToEdge()
        stopTouchBoostingTheRefreshRate()

        gameView = GameView(this, playerIntent)
        haptics = ImpactHaptics(this, gameView)
        readout = FrameTimeReadout(this)

        renderer = GameRenderer(
            intent = playerIntent,
            haptics = haptics,
            onStats = { snapshot ->
                // Published from the GL thread; a View may only be touched on
                // the UI thread, so the values are read here and the View work
                // is posted.
                renderContext.triangles = renderer.trianglesDrawn()
                renderContext.bodies = renderer.bodyCount()
                renderContext.dynamicBytesPerFrame = renderer.dynamicBytesPerFrame()
                renderContext.hapticsMode = haptics.mode
                renderContext.impactsSeen = haptics.impactsSeen
                renderContext.pulsesRequested = haptics.pulsesRequested
                renderContext.lastImpactEnergy = haptics.lastEnergy
                renderContext.masterVibrateOn = haptics.masterVibrateOn
                renderContext.touchFeedbackOn = haptics.touchFeedbackOn
                renderContext.shadeLevel = renderer.shadeLevel
                readout.view.post { readout.update(snapshot, renderContext) }
            },
            onLayout = { worldPerDp ->
                gameView.post { gameView.configureGestures(worldPerDp) }
            },
        )
        gameView.setRenderer(renderer)
        // Must follow setRenderer(): that is what creates the GLThread this
        // dereferences. See the note in GameView's init block.
        gameView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // playing.md: the canvas is exposed to TalkBack as one static-labelled
        // element. That is a stated limitation of a physics canvas, not a gap
        // to close later — there is no meaningful per-element structure to
        // expose to a screen reader.
        gameView.contentDescription = getString(R.string.game_board)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                gameView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(readout.view)
        }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets ->
            applyInsets(insets)
            // Consume nothing: the surface is meant to extend behind the bars,
            // and swallowing the insets here would deny them to anything added
            // later.
            insets
        }

        registerBackHandling()
    }

    /**
     * Ask for a genuinely edge-to-edge window rather than relying on the
     * `targetSdk` default alone, so behaviour is the same on API 29-34 where
     * that default does not apply.
     *
     * Deliberately **not** immersive/sticky fullscreen. ADR 0010 rejects that
     * as the default: it fights predictive back and gesture navigation, and
     * this game's control scheme is a full-screen drag, which conflicts with
     * edge gestures. Respecting the insets and designing the play area around
     * them is the chosen answer.
     *
     * Everything here is deprecated at `compileSdk 36` **because** the platform
     * now does it by default — and every one of these calls is still load-
     * bearing at `minSdk 29`, where it does not. ADR 0010 accepts that we only
     * ever test on API 36 and that the API 29 claim is supported by
     * construction; deleting these would quietly make that claim false. They
     * come out when `minSdk` reaches 35, not before.
     */
    @Suppress("DEPRECATION")
    private fun goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        // The system would otherwise paint a translucent scrim behind the bars
        // to guarantee contrast. Against tokens.md's true-black background
        // that scrim is both unnecessary and visible.
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    /**
     * Opt this window out of the platform's touch boost, so the 60Hz request in
     * [GameView.requestSixtyHertz] can actually take effect.
     *
     * Milestone 1 ran at 90.2 fps on the client's device despite that request,
     * and their logcat named the reason: `RefreshRateSelector: 90.00 Hz (Touch
     * Boost)`. The `setFrameRate` call was not being ignored — it is a *vote*,
     * and Android's adaptive-refresh-rate policy boosts the render rate to
     * "high" whenever the screen is touched and holds it there for a while
     * after release. The final rate is the highest vote, so touch boost simply
     * outranked ours. This game's control scheme is drag-anywhere, so a finger
     * is on the glass for essentially the whole session and the boost never
     * lapses.
     *
     * Google's documentation says disabling touch boost "is not recommended, as
     * it could significantly impact user experience", and for a scrolling UI
     * that is right — the boost exists so lists track the finger. It does not
     * apply here. The simulation advances on a fixed 60Hz timestep (ADR 0006),
     * so frames beyond 60 contain no new state: rendering them costs full
     * fragment-shader work to display a duplicate. ADR 0006 rejected 120Hz on
     * exactly that fragment cost, and that rejection is only real if this call
     * happens.
     *
     * Two honest caveats. First, this is a per-window request and the platform
     * may still override it on thermal or battery grounds, so it makes 60 much
     * more likely, not certain. Second, it has never been run on hardware —
     * this container has no device. If the client's next build still reports
     * ~90 fps, the stronger lever is `LayoutParams.preferredDisplayModeId`
     * pinned to a 60Hz mode, which was rejected here as the first move because
     * it forces a panel mode switch rather than expressing a preference, and
     * can produce a visible flash on mode change.
     *
     * API 35+. Below that there is no way to decline the boost.
     */
    private fun stopTouchBoostingTheRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setFrameRateBoostOnTouchEnabled(false)
        }
    }

    /**
     * Read the safe insets and hand them to the renderer.
     *
     * The union of system bars **and** the display cutout, because ADR 0010's
     * requirement is that nothing playable sits under either. Using only
     * `systemBars()` would put the well under a punch-hole camera.
     */
    private fun applyInsets(insets: WindowInsets) {
        val density = resources.displayMetrics.density

        val left: Int
        val top: Int
        val right: Int
        val bottom: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            left = safe.left
            top = safe.top
            right = safe.right
            bottom = safe.bottom
        } else {
            @Suppress("DEPRECATION")
            run {
                left = insets.systemWindowInsetLeft
                top = insets.systemWindowInsetTop
                right = insets.systemWindowInsetRight
                bottom = insets.systemWindowInsetBottom
            }
        }

        readout.applyInsets(left, bottom, density)

        // The gesture recogniser is configured from the renderer's onLayout
        // callback, not here — the well's world-per-dp factor does not exist
        // until the GL thread has recomputed the layout from these insets.
        gameView.queueEvent {
            renderer.setInsets(left, top, right, bottom, density)
        }
    }

    /**
     * ADR 0010 §2: register an `OnBackInvokedCallback`. Back pauses the game
     * rather than destroying it, and must not exit mid-run without
     * confirmation.
     *
     * Stage 1 has no pause screen — menus are explicitly out of scope — so
     * "paused" here means the simulation stops advancing and back resumes it.
     * That is deliberately the smallest thing satisfying the ADR's actual
     * requirement (back must not silently destroy a run) without building UI
     * that Stage 5 owns. `docs/ux/screens/paused.md` replaces this.
     */
    private fun registerBackHandling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { togglePause() }
        }
        // Below API 33 the deprecated onBackPressed override handles it.
    }

    @Deprecated("Superseded by OnBackInvokedCallback on API 33+, still required below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            togglePause()
        }
    }

    /**
     * Run the ADR 0009 reference workload and show the result.
     *
     * Hidden, one tap, and **debug builds only** — gated on `FLAG_DEBUGGABLE`
     * rather than on a generated `BuildConfig`, which would mean turning on a
     * build feature to learn something the manifest already states.
     *
     * The running state is posted from here, before the work is queued, because
     * the GL thread then blocks for several seconds and the screen stops
     * updating. A hidden key that silently freezes the game is indistinguishable
     * from a crash, and the client is the one holding the phone.
     *
     * It runs on the GL thread rather than a background thread on purpose: that
     * is the thread the real solver runs on, at the same priority, so the
     * number is taken under the conditions it is meant to describe. It also
     * means no frame is being drawn while it runs, so nothing competes with the
     * measurement.
     */
    private fun runSolverBenchmark() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (benchmarkRunning) return
        benchmarkRunning = true

        readout.showBenchmarkRunning()
        gameView.queueEvent {
            val result = SolverBenchmark.run()
            readout.view.post {
                readout.showBenchmark(result)
                benchmarkRunning = false
            }
            // The benchmark froze the render thread for seconds. Those frames
            // are not a property of the game, so they are discarded rather than
            // left to poison the live figures the client is reading.
            renderer.discardFrameHistory()
        }
    }

    /**
     * Volume-up steps the shading dial down one level; volume-down runs the
     * hidden solver benchmark.
     *
     * At Stage 1 volume-up was a two-state toggle on the compression term. It
     * is now a five-position ladder — full art direction down to a flat colour
     * — because fragment cost is the remaining performance risk and the only
     * way to price the art direction is to walk it down on the client's own
     * device, on one stack, in one session. Five presses, five frame times,
     * and the difference between consecutive readings is the price of exactly
     * one group of shading terms. See `GameRenderer.shadeLevel`.
     *
     * A hardware key rather than a screen control, deliberately. The brief's
     * control scheme is drag-anywhere, so any on-screen toggle would carve a
     * dead zone out of the play area — and the frame-time readout sits in the
     * bottom-left corner, exactly where a thumb rests. A volume key costs the
     * gameplay nothing, is trivial to describe to the client ("press volume up
     * to turn the shading off and read the numbers again"), and the game has
     * no audio for the keys to interfere with.
     *
     * This is a measurement affordance for the prototype, not a feature. It
     * goes when the performance question is answered, along with the readout
     * itself (docs/ux/screens/playing.md).
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                gameView.queueEvent { renderer.cycleShadeLevel() }
                true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                runSolverBenchmark()
                true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun togglePause() {
        paused = !paused
        val value = paused
        gameView.queueEvent { renderer.setPaused(value) }
    }

    override fun onResume() {
        super.onResume()
        gameView.onResume()
        paused = false
        gameView.queueEvent { renderer.setPaused(false) }
    }

    override fun onPause() {
        super.onPause()
        haptics.cancel()
        gameView.queueEvent { renderer.setPaused(true) }
        gameView.onPause()
    }
}
