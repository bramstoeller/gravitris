package gravitris.app

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewConfiguration
import gravitris.app.gl.MsaaConfigChooser
import gravitris.app.input.GestureConfig
import gravitris.app.input.GestureRecognizer
import gravitris.app.input.PlayerIntent

/**
 * The GL surface and the touch entry point.
 *
 * `GLSurfaceView` with `RENDERMODE_CONTINUOUSLY` and
 * `preserveEGLContextOnPause` — ADR 0007 §8 calls this "the boring choice",
 * which it is, and records the escape hatch: if frame pacing proves uneven at
 * Milestone 1, move to a plain `SurfaceView` with our own EGL setup driven by
 * `Choreographer`. The frame-time readout is what will tell us whether that
 * trigger has fired, so the two decisions are deliberately connected.
 */
@SuppressLint("ViewConstructor")
class GameView(
    context: Context,
    private val intent: PlayerIntent,
) : GLSurfaceView(context) {

    private var recognizer: GestureRecognizer? = null

    /** The density used to build the current [recognizer], so it is rebuilt
     *  when the well layout changes rather than silently keeping stale
     *  conversion factors after a rotation. */
    private var recognizerWorldPerDp = Float.NaN

    init {
        setEGLContextClientVersion(3)
        // §17 antialiasing: request a hardware-MSAA surface config before the
        // renderer is attached (setEGLConfigChooser must precede setRenderer).
        // The chooser falls back to single-sample if the driver — or the
        // software emulator — offers no multisample config, so this never costs
        // a black screen. MSAA_SAMPLES = 0 skips it entirely (the §17 cut dial).
        if (Tunables.MSAA_SAMPLES >= 2) {
            setEGLConfigChooser(MsaaConfigChooser(Tunables.MSAA_SAMPLES))
        }
        // ADR 0010 §6: keep the context across a pause where the device allows
        // it, so returning from the home screen does not rebuild every buffer.
        preserveEGLContextOnPause = true
        // NOTE: renderMode is deliberately NOT set here. GLSurfaceView creates
        // its GLThread inside setRenderer(), and setRenderMode() dereferences
        // that thread — so touching it from this constructor throws NPE and the
        // activity never starts. It is set in MainActivity immediately after
        // setRenderer(). Found on a real device, not by review: nothing in this
        // project could render a frame until the client installed the APK.

        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                requestSixtyHertz(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                requestSixtyHertz(holder.surface)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
        })
    }

    /**
     * Rebuild the gesture recogniser for the current well geometry.
     *
     * @param worldPerDp how many well units one dp of finger travel is worth —
     *   the factor that makes gestures.md's 1:1 drag mapping literally 1:1 on
     *   this device's safe area.
     */
    fun configureGestures(worldPerDp: Float) {
        if (worldPerDp == recognizerWorldPerDp) return
        recognizerWorldPerDp = worldPerDp

        val density = resources.displayMetrics.density
        // gestures.md: reuse the platform's own slop rather than inventing a
        // value, so it already matches the player's muscle memory from every
        // other app. ViewConfiguration reports it in pixels.
        val slopDp = ViewConfiguration.get(context).scaledTouchSlop / density

        recognizer = GestureRecognizer(
            GestureConfig(
                pxPerDp = density,
                worldPerDp = worldPerDp,
                touchSlopDp = if (slopDp > 0f) slopDp else Tunables.TOUCH_SLOP_DP,
            ),
            intent,
        )
    }

    /**
     * Touch arrives here for the **whole surface**. The brief and gestures.md
     * both require drag-anywhere rather than drag-on-the-piece, so that the
     * thumb can rest low and out of the way of what the player is watching.
     * There is deliberately no hit-testing against the piece.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val recognizer = this.recognizer ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recognizer.onPointerDown(
                    event.getPointerId(0), event.x, event.y, eventTimeNanos(event),
                )
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Passed through so the recogniser can apply its own
                // first-pointer-wins rule rather than having it duplicated
                // here. It ignores this.
                val index = event.actionIndex
                recognizer.onPointerDown(
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index),
                    eventTimeNanos(event),
                )
            }

            MotionEvent.ACTION_MOVE -> {
                // Historical samples first, in order, then the current
                // position. docs/contracts.md §2 requires this: Android
                // batches touch samples taken above the refresh rate into one
                // MotionEvent, and reading only getX()/getY() would throw away
                // exactly the drag resolution the 1:1 steering mapping needs.
                for (h in 0 until event.historySize) {
                    val t = historicalTimeNanos(event, h)
                    for (p in 0 until event.pointerCount) {
                        recognizer.onPointerMove(
                            event.getPointerId(p),
                            event.getHistoricalX(p, h),
                            event.getHistoricalY(p, h),
                            t,
                        )
                    }
                }
                val t = eventTimeNanos(event)
                for (p in 0 until event.pointerCount) {
                    recognizer.onPointerMove(
                        event.getPointerId(p), event.getX(p), event.getY(p), t,
                    )
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                recognizer.onPointerUp(
                    event.getPointerId(index),
                    event.getX(index),
                    event.getY(index),
                    eventTimeNanos(event),
                )
            }

            MotionEvent.ACTION_CANCEL -> recognizer.onCancel()
        }
        return true
    }

    /**
     * ADR 0006 §4 and ADR 0010 §3: ask the LTPO panel for 60Hz explicitly.
     *
     * On a 10-120Hz adaptive panel, not asking is itself a decision — the
     * system would pick, silently, per device and per battery state, and we
     * would pay double GPU cost for frames containing no new simulation state.
     * ADR 0006 rejects 120Hz rendering on fragment-shader cost, not solver
     * cost, and that rejection is only real if this call happens.
     *
     * `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE` is the correct constant: it says
     * "this content has a fixed 60Hz cadence", which is exactly true of a
     * fixed-timestep simulation, and lets the platform choose a compatible
     * panel rate rather than forcing one.
     */
    private fun requestSixtyHertz(surface: Surface) {
        if (!surface.isValid) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(
                    Tunables.TARGET_REFRESH_HZ,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface.setFrameRate(
                    Tunables.TARGET_REFRESH_HZ,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
            }
            // API 29 has no frame-rate API at all. The app still runs; it just
            // takes whatever the panel offers. ADR 0010 is explicit that the
            // API 29 claim is supported by construction, not by testing.
        } catch (e: IllegalStateException) {
            // The surface can be released between the isValid check and the
            // call. A missed frame-rate hint is not worth crashing over.
        }
    }

    private fun eventTimeNanos(event: MotionEvent): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            event.eventTimeNanos
        } else {
            event.eventTime * 1_000_000L
        }

    private fun historicalTimeNanos(event: MotionEvent, position: Int): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            event.getHistoricalEventTimeNanos(position)
        } else {
            event.getHistoricalEventTime(position) * 1_000_000L
        }
}
