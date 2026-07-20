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
 * The drag / tap / hard-drop state machine from `docs/ux/gestures.md`.
 *
 * Pure Kotlin on purpose — it takes coordinates and timestamps, not
 * `MotionEvent`. That keeps the whole of gesture recognition testable on the
 * JVM with no device and no Robolectric, which matters because this is the
 * component whose failure modes (a tap read as a micro-drag, a drag read as a
 * hard drop) are the ones gestures.md exists to prevent and the ones a human
 * would have to feel to notice.
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

    private enum class Mode {
        /** Pointer is down but has committed to nothing yet. */
        UNDECIDED,

        /** Horizontal drag; piece x follows the finger 1:1. */
        DRAGGING,

        /** Hard drop already fired for this gesture; ignore the rest of it. */
        SPENT,
    }

    private val velocity = VelocityWindow()
    private val velocityOut = FloatArray(2)

    private var activePointerId = INVALID_POINTER
    private var mode = Mode.UNDECIDED

    private var downXDp = 0f
    private var downYDp = 0f
    private var lastXDp = 0f

    /** Largest distance from the down point reached at any time this gesture. */
    private var maxDisplacementDp = 0f

    /**
     * Timestamp of the last committed tap, for [Tunables.ROTATE_DEBOUNCE_NANOS].
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
        mode = Mode.UNDECIDED

        val xDp = xPx / config.pxPerDp
        val yDp = yPx / config.pxPerDp
        downXDp = xDp
        downYDp = yDp
        lastXDp = xDp
        maxDisplacementDp = 0f

        velocity.clear()
        velocity.addSample(tNanos, xDp, yDp)
    }

    /**
     * Feed one movement sample. Call this for every historical sample in a
     * `MotionEvent` as well as its current position — see [VelocityWindow].
     */
    fun onPointerMove(pointerId: Int, xPx: Float, yPx: Float, tNanos: Long) {
        if (pointerId != activePointerId) return

        val xDp = xPx / config.pxPerDp
        val yDp = yPx / config.pxPerDp
        velocity.addSample(tNanos, xDp, yDp)

        val dxFromDown = xDp - downXDp
        val dyFromDown = yDp - downYDp
        val displacement = sqrt(dxFromDown * dxFromDown + dyFromDown * dyFromDown)
        if (displacement > maxDisplacementDp) maxDisplacementDp = displacement

        if (mode == Mode.SPENT) {
            lastXDp = xDp
            return
        }

        // 1. Hard-drop check runs first and continuously, and can fire before
        //    release — gestures.md: "this is the one gesture that should feel
        //    instant." Checking it ahead of drag is what stops a fast downward
        //    flick that drifts a little sideways from being eaten as a drag.
        if (checkHardDrop(dyFromDown)) {
            lastXDp = xDp
            return
        }

        // 2. Drag. Enter drag mode once horizontal displacement passes slop.
        if (mode == Mode.UNDECIDED && abs(dxFromDown) > config.touchSlopDp) {
            mode = Mode.DRAGGING
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

        if (mode == Mode.DRAGGING) {
            // Delta since the LAST SAMPLE, not since touch-down. gestures.md
            // is explicit: "so the thumb's absolute position on screen never
            // matters — a drag started anywhere behaves the same." This is
            // also what makes "drag anywhere on screen" work without the
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

        // Fold the release position in before resolving: a gesture whose only
        // movement arrives with the UP event must still be able to disqualify
        // itself as a tap.
        val xDp = xPx / config.pxPerDp
        val yDp = yPx / config.pxPerDp
        val dxFromDown = xDp - downXDp
        val dyFromDown = yDp - downYDp
        val displacement = sqrt(dxFromDown * dxFromDown + dyFromDown * dyFromDown)
        if (displacement > maxDisplacementDp) maxDisplacementDp = displacement

        // 3. Tap, resolved on release. Slop only, NO duration limit —
        //    gestures.md rejects a duration gate outright: "a hesitant,
        //    slow-but-still-small press must still register as a rotate, not
        //    silently do nothing because it took too long."
        if (mode == Mode.UNDECIDED && maxDisplacementDp <= config.touchSlopDp) {
            intent.requestRotate()
            lastRotateNanos = tNanos
        }

        // 4. Released while dragging without ever crossing the hard-drop
        //    thresholds: nothing to emit. The piece keeps the x it was dragged
        //    to and carries on falling, which needs no signal at all.

        reset()
    }

    /**
     * The gesture was taken away from us — the window lost focus, a system
     * gesture won, the view was detached. Emit nothing: an interrupted gesture
     * expressed no intent, and inventing one here is how a phone call turns
     * into a hard drop.
     */
    fun onCancel() {
        reset()
    }

    /**
     * @param dyFromDown downward travel since touch-down, dp (positive = down).
     * @return true when a hard drop was committed by this sample.
     */
    private fun checkHardDrop(dyFromDown: Float): Boolean {
        // Minimum travel gate first: it is the cheapest test and it is what
        // stops a fast micro-jitter (a thumb settling) from firing a drop.
        if (dyFromDown < Tunables.HARD_DROP_MIN_DISPLACEMENT_DP) return false
        if (!velocity.velocityDpPerSecond(velocityOut)) return false

        val vx = velocityOut[0]
        val vy = velocityOut[1]
        if (vy < Tunables.HARD_DROP_MIN_VELOCITY_DP_PER_S) return false

        // Angle cone, as a dot product against straight-down (0, 1) rather
        // than an atan2 — same test, no trigonometry per touch sample.
        val speed = sqrt(vx * vx + vy * vy)
        if (speed <= 0f) return false
        if (vy / speed < Tunables.HARD_DROP_ANGLE_COS) return false

        intent.requestHardDrop(vy * config.worldPerDp)
        mode = Mode.SPENT
        return true
    }

    private fun reset() {
        activePointerId = INVALID_POINTER
        mode = Mode.UNDECIDED
        velocity.clear()
    }

    private companion object {
        const val INVALID_POINTER = -1

        /** "No tap has been committed yet." Never compared arithmetically. */
        const val NO_ROTATE_YET = Long.MIN_VALUE
    }
}
