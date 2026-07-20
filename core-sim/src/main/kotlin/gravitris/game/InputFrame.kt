package gravitris.game

/**
 * One tick of player intent. `:app` translates gestures into this; the core
 * decides what they mean. The core never sees a touch event.
 *
 * Reused (mutable) to avoid per-tick allocation.
 *
 * **`:app` owns clearing the one-shot flags.** [Simulation.step] reads this
 * object and never writes to it. "Consumed on the tick it is read" describes
 * the semantics — a tap affects exactly one tick — not who does the clearing.
 *
 * Having the core clear the flags was considered and rejected: it would make
 * `step` mutate its argument, so replaying a *recorded* sequence of
 * `InputFrame`s would behave differently the second time through as the flags
 * were consumed in place. That would break the replay determinism ADR 0006
 * exists to guarantee, in a way that is very hard to see. The cost of the
 * chosen side is that a frontend which forgets to clear [rotate] spins the
 * piece every tick — loud and obvious, which is the failure mode to prefer.
 */
class InputFrame {
    /** Horizontal drag delta this tick, in well units. */
    var dragX: Float = 0f

    /** Tap. Applies to the tick on which it is read; `:app` must clear it. */
    var rotate: Boolean = false

    /** Swipe down. Applies to the tick on which it is read; `:app` must clear it. */
    var hardDrop: Boolean = false

    /**
     * Flick speed for the hard drop, well units/sec. Computed by `:app` from a
     * trailing ~60ms window of timestamped touch samples (including
     * `MotionEvent` historical samples), NOT from a per-frame delta — Android
     * samples touch above the refresh rate and the core must not lose that
     * resolution to a 60Hz tick. See docs/ux/gestures.md.
     */
    var hardDropVelocity: Float = 0f

    /** Resets every field. Convenience for `:app`'s per-tick clear. */
    fun clear() {
        dragX = 0f
        rotate = false
        hardDrop = false
        hardDropVelocity = 0f
    }
}
