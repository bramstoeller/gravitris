package gravitris.game

/**
 * One tick of player intent. `:app` translates gestures into this; the core
 * decides what they mean. The core never sees a touch event.
 *
 * Reused (mutable) to avoid per-tick allocation.
 *
 * ### The intents are dumb; the core gates them by phase (ADR 0016)
 *
 * `:app`'s gesture recognizer stays phase-agnostic and emits raw intents:
 * a pointer-up sets [drop], a pointer-up that stayed within touch-slop (a tap)
 * *also* sets [rotate], and a horizontal drag accumulates [dragX]. The **core**
 * decides what each means from the active piece's phase — a tap while
 * positioning drops, a tap while falling rotates — so "the core decides what a
 * gesture means" (`docs/contracts.md` §2) holds and the recognizer needs no
 * phase signal. See `Simulation.applyInput` for the gate:
 *
 * - **POSITIONING**: [dragX] slides, [drop] releases the piece to fall,
 *   [rotate] is ignored.
 * - **FALLING**: [rotate] turns the piece, [dragX] and [drop] are ignored.
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
    /** Horizontal drag delta this tick, in well units. Applied only while positioning. */
    var dragX: Float = 0f

    /** Tap. Applies to the tick on which it is read; `:app` must clear it. Rotates only while falling. */
    var rotate: Boolean = false

    /**
     * Release: commit the positioning piece to its fall. Applies to the tick on
     * which it is read; `:app` must clear it. Ignored while falling — the fall
     * is real gravity (ADR 0016), so there is no hard-drop velocity boost.
     */
    var drop: Boolean = false

    /** Resets every field. Convenience for `:app`'s per-tick clear. */
    fun clear() {
        dragX = 0f
        rotate = false
        drop = false
    }
}
