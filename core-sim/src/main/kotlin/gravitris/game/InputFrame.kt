package gravitris.game

/**
 * One tick of player intent. `:app` translates gestures into this; the core
 * applies them to the active piece. The core never sees a touch event.
 *
 * Reused (mutable) to avoid per-tick allocation.
 *
 * ### One controlled descent (ADR 0017)
 *
 * A piece falls under real, accelerating gravity from the moment it spawns, and
 * the player steers **and** rotates it for the *whole* descent, until it
 * contacts other material (or the floor) and settles. There is no phase where
 * control is taken away, so both intents mean exactly one thing and apply on
 * every tick the piece is live — the core does not gate them by phase:
 *
 * - [dragX] steers the active piece horizontally, clamped to the well.
 * - [rotate] turns it a quarter step, rejected if it would overlap settled
 *   material.
 *
 * There is no release-to-drop and no hard drop (ADR 0017 supersedes ADR 0016);
 * the fall is plain gravity, neither hastened nor committed by a gesture.
 *
 * **`:app` owns clearing the one-shot flag.** [Simulation.step] reads this
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
    /**
     * Horizontal steering delta this tick, in well units. Applied to the active
     * piece every tick it is live — the whole descent — clamped to the well by
     * the core. Kinematic: it moves the piece without injecting velocity.
     */
    var dragX: Float = 0f

    /**
     * Tap → rotate a quarter turn. Applied to the active piece every tick it is
     * live — the whole descent. Applies to the tick on which it is read; `:app`
     * must clear it.
     */
    var rotate: Boolean = false

    /** Resets every field. Convenience for `:app`'s per-tick clear. */
    fun clear() {
        dragX = 0f
        rotate = false
    }
}
