package gravitris.app.input

/**
 * A trailing window of timestamped touch samples, used to estimate pointer
 * velocity in dp/s.
 *
 * `docs/contracts.md` §2 is unusually specific about this and the reason is
 * worth restating where the code lives: the hard-drop velocity must be
 * "computed by :app from a trailing ~60ms window of TIMESTAMPED touch samples
 * (including MotionEvent historical samples), NOT from a per-frame delta —
 * Android samples touch above the refresh rate and the core must not lose that
 * resolution to a 60Hz tick."
 *
 * So this class is fed from `MotionEvent`'s historical samples as well as its
 * current one, and it derives velocity from real elapsed nanoseconds. It never
 * sees a frame.
 *
 * Not thread-safe; it is touched only from the UI thread.
 */
class VelocityWindow(
    private val windowNanos: Long = gravitris.app.Tunables.VELOCITY_WINDOW_NANOS,
    capacity: Int = 64,
) {
    private val timeNanos = LongArray(capacity)
    private val xDp = FloatArray(capacity)
    private val yDp = FloatArray(capacity)

    /** Index one past the newest sample, modulo capacity. */
    private var head = 0
    private var size = 0

    val sampleCount: Int get() = size

    fun clear() {
        head = 0
        size = 0
    }

    fun addSample(tNanos: Long, x: Float, y: Float) {
        timeNanos[head] = tNanos
        xDp[head] = x
        yDp[head] = y
        head = (head + 1) % timeNanos.size
        if (size < timeNanos.size) size++
    }

    /**
     * Velocity in dp/s over the trailing window, written into [out] as
     * `[vx, vy]`. Returns false and leaves [out] untouched when there is not
     * enough signal to estimate — fewer than two samples, or a degenerate
     * time span.
     *
     * Deliberately a two-point estimate across the window rather than a
     * least-squares fit: the window is short, the thresholds it feeds
     * (1000dp/s) are coarse, and a fit would add cost and a tuning surface to
     * a decision that is already a hard cutoff. If hard-drop detection proves
     * jumpy on the real device this is the first thing to revisit.
     */
    fun velocityDpPerSecond(out: FloatArray): Boolean {
        if (size < 2) return false

        val newestIndex = (head - 1 + timeNanos.size) % timeNanos.size
        val newestTime = timeNanos[newestIndex]
        val cutoff = newestTime - windowNanos

        // Walk backwards to the oldest sample still inside the window. There is
        // always at least one older sample or we returned above.
        var oldestIndex = newestIndex
        for (step in 1 until size) {
            val candidate = (newestIndex - step + timeNanos.size) % timeNanos.size
            if (timeNanos[candidate] < cutoff) break
            oldestIndex = candidate
        }
        if (oldestIndex == newestIndex) return false

        val elapsedNanos = newestTime - timeNanos[oldestIndex]
        if (elapsedNanos <= 0L) return false

        val perSecond = 1_000_000_000f / elapsedNanos
        out[0] = (xDp[newestIndex] - xDp[oldestIndex]) * perSecond
        out[1] = (yDp[newestIndex] - yDp[oldestIndex]) * perSecond
        return true
    }
}
