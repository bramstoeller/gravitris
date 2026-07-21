package gravitris.app

/**
 * The positioning-window countdown as a 0..1 urgency value (ADR 0016).
 *
 * The core parks a freshly-spawned piece at the spawn row for a short window in
 * which the player slides it, then it drops on its own — the client's *"much
 * less long able to move"*. `SimState.positioningTicksRemaining /
 * positioningWindowTicks` is that window's remaining fraction, and the shell
 * draws it as a "move now" pressure cue (see [gravitris.app.gl.UrgencyBar]).
 *
 * Kept as a pure function, apart from the GL code that draws it, so the one bit
 * of logic here — the clamp and the divide-by-zero guard — is checkable on the
 * JVM without a GPU. The visual it feeds is a Stage-visual-pass concern; this
 * arithmetic is not.
 */
object PositioningUrgency {

    /**
     * Fraction of the positioning window still remaining, in `0f..1f`.
     *
     * `1` at the instant a piece is dealt, falling to `0` as the window runs
     * out. Returns `0` whenever the piece is not positioning: the contract makes
     * [gravitris.game.SimState.positioningTicksRemaining] `0` off the window, and
     * a non-positive [windowTicks] (which the core never emits, but which a dev
     * panel could momentarily produce) is treated as "no window" rather than
     * dividing by it.
     */
    fun fraction(remainingTicks: Int, windowTicks: Int): Float {
        if (windowTicks <= 0 || remainingTicks <= 0) return 0f
        val f = remainingTicks.toFloat() / windowTicks.toFloat()
        return if (f > 1f) 1f else f
    }
}
