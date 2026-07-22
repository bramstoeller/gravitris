package gravitris.app.input

import gravitris.app.Tunables
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Conversion factors and thresholds the recogniser needs, resolved once from
 * the display metrics and the well layout.
 *
 * @param pxPerDp display density.
 * @param worldPerDp how many well units one dp of finger travel is worth.
 *   gestures.md specifies `dragSensitivity` as "1dp of finger movement = 1dp
 *   of piece movement in world space", so this is the factor that makes that
 *   sentence true: it is the well's world width divided by its width on screen
 *   in dp. It is supplied rather than computed here because the well is laid
 *   out inside the safe insets (ADR 0010) and is therefore device-dependent.
 * @param touchSlopDp the platform's own `ViewConfiguration` slop. gestures.md
 *   insists we reuse the platform value rather than invent one, so that it
 *   already matches the player's muscle memory from every other app.
 */
data class GestureConfig(
    val pxPerDp: Float,
    val worldPerDp: Float,
    val touchSlopDp: Float = Tunables.TOUCH_SLOP_DP,
)

/**
 * The gesture state machine for the **single controlled descent** control model
 * (ADR 0017, docs/contracts.md §2).
 *
 * A piece falls under real, accelerating gravity from the moment it spawns, and
 * the player steers it left/right **and** rotates it for the *whole* descent,
 * until it contacts other material and settles. There is no positioning window,
 * no release-to-drop and no hard drop — no phase in which control is taken away.
 *
 * ## A pointer-up means exactly one thing
 *
 * ADR 0016's recogniser had to be phase-agnostic: "release = drop" and "tap =
 * rotate" were both a pointer-up, so it emitted *both* intents and left the core
 * to disambiguate by phase. Under ADR 0017 there is no release-to-drop and no
 * phase, so the mapping collapses to one meaning per gesture:
 *
 * - horizontal travel past the touch slop → **dragX** — continuous steering,
 *   the whole descent;
 * - a pointer-up whose gesture never left the slop (a tap) → **rotate** — the
 *   whole descent;
 * - a pointer-up ending a drag → **nothing**. The steering already happened
 *   continuously as `dragX`; the release commits nothing.
 *
 * The recogniser is therefore simpler than ADR 0016's: no always-emit `drop`,
 * no phase to marshal onto the UI thread, and the core no longer gates input by
 * phase (there is no phase). It stays pure Kotlin, tested on the JVM with no
 * device.
 *
 * It takes coordinates and timestamps, not `MotionEvent`, for the same reason:
 * the failure modes that matter here (a tap read as a micro-drag, a decisive
 * drag swallowing its own release) are the ones a human has to *feel*, so they
 * are pinned down in `GestureRecognizerTest` instead.
 *
 * Coordinates arrive in **pixels**, screen convention: +y is downward.
 *
 * ## Multi-touch
 *
 * Only the first pointer down is tracked; every other pointer is ignored
 * entirely until it is released. gestures.md's reason is worth keeping in
 * view: a second accidental finger — common one-handed, with the phone
 * resting against a palm — must not be able to hijack or cancel a gesture in
 * progress.
 */
class GestureRecognizer(
    private val config: GestureConfig,
    private val intent: PlayerIntent,
) {

    private var activePointerId = INVALID_POINTER

    /** True once horizontal travel has passed the slop and drag is committed. */
    private var dragging = false

    /** True once the gesture has been cancelled; it then emits nothing on up. */
    private var cancelled = false

    private var downXDp = 0f
    private var downYDp = 0f
    private var lastXDp = 0f

    /** Largest distance from the down point reached at any time this gesture. */
    private var maxDisplacementDp = 0f

    /**
     * Timestamp of the last committed **tap** (rotate), for
     * [Tunables.ROTATE_DEBOUNCE_NANOS].
     *
     * Only a tap arms this, never a drag-release: the debounce exists to
     * absorb a touch-controller bounce double-reporting a tap as two rotates,
     * which is the one duplicated intent that matters. A drag no longer emits
     * anything on release, so a drag → tap flow has no debounce delay on its
     * rotate; only tap → tap does, and 60 ms there is imperceptible.
     *
     * [NO_ROTATE_YET] rather than [Long.MIN_VALUE], and tested for explicitly
     * below. `tNanos - Long.MIN_VALUE` overflows for every realistic timestamp,
     * producing a negative result that reads as "inside the debounce window" —
     * which would have made the recogniser ignore the first touch of a session
     * and then, since the ignored touch never sets this field, every touch
     * after it. Silent, total input failure from a sentinel value.
     */
    private var lastRotateNanos = NO_ROTATE_YET

    fun onPointerDown(pointerId: Int, xPx: Float, yPx: Float, tNanos: Long) {
        // gestures.md "Multi-touch": additional simultaneous pointers are
        // ignored entirely, including their down events.
        if (activePointerId != INVALID_POINTER) return

        // rotateDebounce: absorb touch-controller bounce / double-report by
        // ignoring a touch-down that lands within the debounce window of a
        // committed tap. The whole gesture is dropped, not just the tap, so a
        // bounced down/up pair cannot produce a stray drag either.
        if (lastRotateNanos != NO_ROTATE_YET &&
            tNanos - lastRotateNanos < Tunables.ROTATE_DEBOUNCE_NANOS
        ) {
            return
        }

        activePointerId = pointerId
        dragging = false
        cancelled = false

        val xDp = xPx / config.pxPerDp
        val yDp = yPx / config.pxPerDp
        downXDp = xDp
        downYDp = yDp
        lastXDp = xDp
        maxDisplacementDp = 0f
    }

    /**
     * Feed one movement sample. Call this for every historical sample in a
     * `MotionEvent` as well as its current position, so a batched fast drag
     * keeps every dp of travel (docs/contracts.md §2).
     */
    fun onPointerMove(pointerId: Int, xPx: Float, yPx: Float, tNanos: Long) {
        if (pointerId != activePointerId || cancelled) return

        val xDp = xPx / config.pxPerDp
        val yDp = yPx / config.pxPerDp

        val dxFromDown = xDp - downXDp
        val dyFromDown = yDp - downYDp
        val displacement = sqrt(dxFromDown * dxFromDown + dyFromDown * dyFromDown)
        if (displacement > maxDisplacementDp) maxDisplacementDp = displacement

        // Enter drag mode once horizontal displacement passes the slop.
        if (!dragging && abs(dxFromDown) > config.touchSlopDp) {
            dragging = true
            // Start measuring from the point where slop was crossed, not from
            // touch-down, so the slop distance itself does not move the piece.
            //
            // gestures.md specifies the 1:1 mapping and the slop but not what
            // happens to the slop distance when drag engages. Spending it would
            // make the piece jump by up to 8dp at the instant a drag starts —
            // the more noticeable defect, and one no native Android scroller
            // has. Since the whole reason for reusing the platform's slop value
            // is that it "already matches the player's muscle memory from every
            // other app", matching the platform's *behaviour* around it follows.
            //
            // Only the slop is discarded, never the full displacement: a fast
            // flick can cross far more than slop in a single sample, and
            // resetting to that sample's position would silently swallow the
            // whole movement.
            lastXDp = downXDp + if (dxFromDown > 0f) config.touchSlopDp else -config.touchSlopDp
        }

        if (dragging) {
            // Delta since the LAST SAMPLE, not since touch-down. gestures.md
            // is explicit: "so the thumb's absolute position on screen never
            // matters — a drag started anywhere behaves the same." This is
            // also what makes "slide anywhere on screen" work without the
            // piece teleporting to the finger.
            val deltaDp = xDp - lastXDp
            if (deltaDp != 0f) {
                intent.addDrag(deltaDp * Tunables.DRAG_SENSITIVITY * config.worldPerDp)
            }
        }

        lastXDp = xDp
    }

    fun onPointerUp(pointerId: Int, xPx: Float, yPx: Float, tNanos: Long) {
        if (pointerId != activePointerId) return

        // A cancel already ended the gesture and emitted nothing; the trailing
        // up must stay silent.
        if (cancelled) {
            reset()
            return
        }

        // Fold the release position in before resolving: a gesture whose only
        // movement arrives with the UP event must still be able to disqualify
        // itself as a tap.
        val xDp = xPx / config.pxPerDp
        val yDp = yPx / config.pxPerDp
        val dxFromDown = xDp - downXDp
        val dyFromDown = yDp - downYDp
        val displacement = sqrt(dxFromDown * dxFromDown + dyFromDown * dyFromDown)
        if (displacement > maxDisplacementDp) maxDisplacementDp = displacement

        // A pointer-up means exactly one thing (ADR 0017). A tap — the gesture
        // never left the slop — latches a rotate. A pointer-up ending a drag
        // commits nothing: the steering already happened continuously as dragX,
        // and there is no release-to-drop under this model.
        if (maxDisplacementDp <= config.touchSlopDp) {
            intent.requestRotate()
            lastRotateNanos = tNanos
        }

        reset()
    }

    /**
     * The gesture was taken away from us — the window lost focus, a system
     * gesture won, the view was detached. Emit nothing: an interrupted gesture
     * expressed no intent, and inventing one here is how a phone call turns
     * into a spurious rotation.
     *
     * The gesture is marked cancelled rather than fully reset so that the
     * trailing up which usually follows a cancel cannot be read as a fresh tap.
     */
    fun onCancel() {
        cancelled = true
        dragging = false
    }

    private fun reset() {
        activePointerId = INVALID_POINTER
        dragging = false
        cancelled = false
    }

    private companion object {
        const val INVALID_POINTER = -1

        /** "No tap has been committed yet." Never compared arithmetically. */
        const val NO_ROTATE_YET = Long.MIN_VALUE
    }
}
