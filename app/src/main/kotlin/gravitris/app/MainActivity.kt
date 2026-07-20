package gravitris.app

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import gravitris.app.haptics.ImpactHaptics
import gravitris.app.input.PlayerIntent
import gravitris.app.perf.FrameTimeReadout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        goEdgeToEdge()

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
                renderContext.hapticsScaled = haptics.isScaled
                readout.view.post { readout.update(snapshot, renderContext) }
            },
            onLayout = { worldPerDp ->
                gameView.post { gameView.configureGestures(worldPerDp) }
            },
        )
        gameView.setRenderer(renderer)

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
