package gravitris.app.input

import gravitris.game.InputFrame

/**
 * The hand-off between the UI thread (which recognises gestures) and the GL
 * thread (which steps the simulation).
 *
 * Gesture recognition is driven by `MotionEvent`, which arrives on the UI
 * thread at whatever rate the touch controller samples — commonly well above
 * the display refresh rate. The simulation consumes exactly one [InputFrame]
 * per fixed 60Hz tick on the GL thread. Those two rates are unrelated, so the
 * bridge between them has to say what happens to intent produced between
 * ticks. It is not the same answer for all three fields:
 *
 * - **dragX accumulates.** Every dp of finger travel must survive to the next
 *   tick or fast slides would silently lose distance and the 1:1 mapping
 *   gestures.md specifies would quietly become "1:1 unless you move quickly".
 * - **rotate latches.** A tap between two ticks must not be dropped. It is
 *   consumed on the tick it is read, per docs/contracts.md §2.
 * - **drop latches.** A release between two ticks must not be lost. Like
 *   rotate it is a single committed event; latching means the tick it is read
 *   sees it exactly once.
 *
 * Synchronisation is a plain lock rather than atomics. It is taken at most a
 * few hundred times a second for a handful of field writes, it is never
 * contended for more than nanoseconds, and the alternative — three separate
 * atomics — would not give a consistent snapshot across the three fields, which
 * is exactly what [drainInto] needs.
 */
class PlayerIntent {

    private val lock = Any()

    private var dragX = 0f
    private var rotate = false
    private var drop = false

    fun addDrag(deltaWorldUnits: Float) = synchronized(lock) {
        dragX += deltaWorldUnits
    }

    fun requestRotate() = synchronized(lock) {
        rotate = true
    }

    /**
     * The player released the piece. The core turns this into "end positioning,
     * begin the fall" while POSITIONING, and ignores it while FALLING — a piece
     * already falling cannot be dropped again. See docs/contracts.md §2.
     */
    fun requestDrop() = synchronized(lock) {
        drop = true
    }

    /**
     * Moves all pending intent into [frame] and resets this accumulator, so
     * each unit of intent is delivered to exactly one tick. Allocation-free:
     * [frame] is the reused mutable instance the contract asks for.
     */
    fun drainInto(frame: InputFrame) = synchronized(lock) {
        frame.dragX = dragX
        frame.rotate = rotate
        frame.drop = drop

        dragX = 0f
        rotate = false
        drop = false
    }
}
