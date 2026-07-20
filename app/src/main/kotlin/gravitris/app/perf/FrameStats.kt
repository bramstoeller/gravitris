package gravitris.app.perf

/**
 * The project's measuring instrument.
 *
 * `docs/build-order.md` names GPU cost as the largest unmeasured risk in the
 * product, and this container has no GPU and no usable emulator — so the
 * client's own phone is the only place a real number can come from. That makes
 * this class, and the readout it feeds, load-bearing rather than a debug
 * extra.
 *
 * ## Why it reports what it reports
 *
 * A single smoothed "fps" number is actively misleading for this product. An
 * average of 60fps is perfectly compatible with one 40ms hitch every second,
 * and a physics game whose whole proposition is *feel* is ruined by exactly
 * that hitch while the average stays green. So this reports the distribution,
 * not a headline:
 *
 * - **cur** — the most recent frame. Jitters, and is supposed to.
 * - **p95** — the frame time 95% of frames beat. The honest "how it runs"
 *   number.
 * - **max** — the single worst frame in the window. The stutter, undisguised.
 * - **jank** — how many frames in the last second missed the 16.7ms budget.
 *   A count, because "how often" is the question a player actually feels.
 * - **cpu** — time this thread spent stepping the simulation and filling the
 *   vertex buffer, i.e. everything before we hand off to the driver.
 *
 * ## The cpu-vs-total split is the point
 *
 * `cur - cpu` is the part of the frame we spend waiting on the driver and the
 * GPU (the `eglSwapBuffers` block plus command submission). It is not a clean
 * GPU timing — OpenGL ES 3.0 has no timer queries in core, and
 * `GL_EXT_disjoint_timer_query` is not dependable across mobile drivers — but
 * it is the difference between "the solver is too slow" and "the renderer is
 * too slow", which is precisely the question ADR 0006 and ADR 0007 are
 * arguing about. Read it as a bound, not a measurement, and see the handoff
 * for what it can and cannot prove.
 *
 * Not thread-safe: written and read on the GL thread. The readout copies a
 * snapshot across to the UI thread.
 */
class FrameStats(
    private val windowNanos: Long = 1_000_000_000L,
    capacity: Int = 256,
) {
    /** Frame-to-frame wall time, nanoseconds. */
    private val frameNanos = LongArray(capacity)

    /** Time spent in our own work on the GL thread, nanoseconds. */
    private val cpuNanos = LongArray(capacity)

    /** When each sample was recorded, for the trailing-window filter. */
    private val atNanos = LongArray(capacity)

    private var head = 0
    private var size = 0

    private var previousFrameStart = 0L

    /** Scratch for percentile computation. Reused so the readout allocates
     *  nothing on the GL thread. */
    private val scratch = LongArray(capacity)

    /**
     * Record one frame.
     *
     * @param frameStartNanos monotonic time at the top of this frame.
     * @param workNanos time spent this frame stepping the simulation and
     *   filling the vertex buffer.
     */
    fun record(frameStartNanos: Long, workNanos: Long) {
        val previous = previousFrameStart
        previousFrameStart = frameStartNanos

        // The first frame after start or resume has no predecessor, and the
        // first frame after a resume would otherwise record the entire paused
        // duration as one catastrophic frame. Skip it rather than poison the
        // window with a number that means nothing.
        if (previous == 0L) return

        val delta = frameStartNanos - previous
        if (delta <= 0L) return

        frameNanos[head] = delta
        cpuNanos[head] = workNanos
        atNanos[head] = frameStartNanos
        head = (head + 1) % frameNanos.size
        if (size < frameNanos.size) size++
    }

    /** Discards history. Called on resume so a pause is not reported as a stall. */
    fun reset() {
        head = 0
        size = 0
        previousFrameStart = 0L
    }

    /**
     * Fill [out] with the statistics over the trailing window. Returns false
     * when there is not yet enough history to say anything, in which case the
     * readout shows a placeholder rather than a plausible-looking zero.
     */
    fun snapshot(nowNanos: Long, out: FrameSnapshot): Boolean {
        if (size == 0) return false

        val cutoff = nowNanos - windowNanos
        var count = 0
        var frameSum = 0L
        var cpuSum = 0L
        var worst = 0L
        var jank = 0

        for (step in 0 until size) {
            val index = (head - 1 - step + frameNanos.size) % frameNanos.size
            if (atNanos[index] < cutoff) break

            val frame = frameNanos[index]
            scratch[count] = frame
            count++
            frameSum += frame
            cpuSum += cpuNanos[index]
            if (frame > worst) worst = frame
            if (frame > BUDGET_NANOS) jank++
        }

        if (count == 0) return false

        val newestIndex = (head - 1 + frameNanos.size) % frameNanos.size

        out.currentMs = frameNanos[newestIndex].toMillis()
        out.cpuMs = cpuNanos[newestIndex].toMillis()
        out.meanMs = (frameSum / count).toMillis()
        out.meanCpuMs = (cpuSum / count).toMillis()
        out.maxMs = worst.toMillis()
        out.p95Ms = percentile(count, 0.95f).toMillis()
        out.jankPerSecond = jank
        out.sampleCount = count
        // Derived from the mean rather than the latest frame: an instantaneous
        // fps reading from one frame is noise, and unlike frame time, fps has
        // no honest instantaneous meaning.
        out.fps = if (frameSum > 0L) count * 1_000_000_000f / frameSum else 0f
        return true
    }

    /**
     * Insertion sort over the window, then index. The window is at most 256
     * entries and this runs at the readout's refresh rate (a few times a
     * second), not per frame — so the simple thing is the right thing, and it
     * allocates nothing.
     */
    private fun percentile(count: Int, fraction: Float): Long {
        for (i in 1 until count) {
            val value = scratch[i]
            var j = i - 1
            while (j >= 0 && scratch[j] > value) {
                scratch[j + 1] = scratch[j]
                j--
            }
            scratch[j + 1] = value
        }
        val index = ((count - 1) * fraction).toInt().coerceIn(0, count - 1)
        return scratch[index]
    }

    private fun Long.toMillis(): Float = this / 1_000_000f

    companion object {
        /** One 60Hz frame. ADR 0006 fixes both the tick and the render rate at
         *  60Hz, so this is the budget in both senses. */
        const val BUDGET_NANOS = 16_666_667L
    }
}

/**
 * A plain mutable holder so [FrameStats.snapshot] allocates nothing on the GL
 * thread. All times are milliseconds.
 */
class FrameSnapshot {
    var currentMs = 0f
    var cpuMs = 0f
    var meanMs = 0f
    var meanCpuMs = 0f
    var p95Ms = 0f
    var maxMs = 0f
    var fps = 0f
    var jankPerSecond = 0
    var sampleCount = 0
}
