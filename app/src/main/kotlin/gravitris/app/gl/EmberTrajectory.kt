package gravitris.app.gl

/**
 * The pure analytic motion of a clear-burst ember (`visual-direction.md`
 * §7.2), separated from [EmberBurst]'s GL buffer machinery so the trajectory
 * and fade can be unit-tested without a GPU — which is the only way any of this
 * shell's rendering maths gets a real test, since the build container has no GL.
 *
 * Every ember follows `p = origin + v·t + ½g·t²` — no simulation, no solver —
 * and fades on an alpha ramp over [LIFE_SECONDS]. All timing is wall-clock
 * seconds, like every duration in the spec set (`tokens.md`), never a frame
 * count.
 */
internal object EmberTrajectory {

    /** ~350 ms burst lifetime (§7.2). */
    const val LIFE_SECONDS = 0.35f

    // Trajectory constants, world units per second (and per second²). Tuned
    // against the 20-unit-wide well and 1-unit bands: a quick upward spray that
    // fans out and just begins to fall inside its lifetime.
    const val MIN_VY = 6f
    const val MAX_VY = 12f
    const val MAX_VX = 4f
    const val GRAVITY = -24f

    /** Half-extent of an ember quad in world units at full size. Small — an
     *  ember, not a block. */
    const val EMBER_SIZE = 0.22f

    /** Fast rise over the first 40 ms, then a linear fall to zero at [LIFE_SECONDS]. */
    private const val FADE_IN_SECONDS = 0.04f

    /** Horizontal position at age [t] seconds. */
    fun x(originX: Float, velX: Float, t: Float): Float = originX + velX * t

    /** Vertical position at age [t] seconds — the upward-then-falling arc. */
    fun y(originY: Float, velY: Float, t: Float): Float =
        originY + velY * t + 0.5f * GRAVITY * t * t

    /**
     * Opacity at age [t] seconds: a fast fade-in, then a linear fade-out,
     * clamped to zero outside `[0, LIFE_SECONDS)`. Used premultiplied into the
     * additive amber so an ember brightens in and dims out.
     */
    fun alpha(t: Float): Float {
        if (t < 0f || t >= LIFE_SECONDS) return 0f
        val fadeIn = (t / FADE_IN_SECONDS).coerceAtMost(1f)
        val fadeOut = 1f - t / LIFE_SECONDS
        return fadeIn * fadeOut
    }

    /** Ember half-extent at age [t]: shrinks toward zero as it fades, scaled by
     *  the per-ember [seedScale] size jitter. */
    fun size(seedScale: Float, t: Float): Float {
        val fadeOut = (1f - t / LIFE_SECONDS).coerceIn(0f, 1f)
        return EMBER_SIZE * seedScale * (0.5f + 0.5f * fadeOut)
    }
}
