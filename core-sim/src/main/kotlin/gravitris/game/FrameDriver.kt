package gravitris.game

/**
 * Owns the accumulator and the frame-overrun policy (ADR 0013).
 *
 * ### Why this is in `:core-sim` and not in the render loop
 *
 * **"Wall-clock time is never dilated" is a client requirement, recorded
 * verbatim in ADR 0013:**
 *
 * > *"Ja, dat is wel zo, hij moet het gewoon goed doen overal. Frames skippen
 * > is prima, of een lagere frame rate, maar niet vertragen."*
 *
 * A block that takes 3.5 seconds to fall must take 3.5 seconds on every
 * device. Dropped frames, judder and a lower frame rate are all acceptable; a
 * slow-motion game is not. Putting the policy here rather than in the GL
 * thread makes it JVM-testable, and means it cannot be quietly reverted by
 * someone tidying the render loop. `FrameDriverTest` is the enforcement.
 *
 * ### Do not clamp the delta
 *
 * ADR 0006 originally read `accumulator += min(frameDelta, MAX_FRAME_DELTA)`.
 * That clamp **discards wall-clock time by construction** — on a device that
 * overruns, real time passes that the simulation never receives — which is
 * precisely the behaviour the client ruled out. It is gone, and it must not
 * come back in any form: no clamping, no smoothing, no averaging of the
 * incoming delta.
 *
 * ### Catch-up cannot destabilise the solver
 *
 * This is the safety property that makes the whole policy affordable. Falling
 * behind is answered by running *more ticks*, never by running a *bigger* one.
 * Each catch-up tick is a normal 1/60 s tick with the normal substep count, so
 * the substep size `h` — the only quantity XPBD's stability depends on — is
 * invariant under everything in this class.
 */
class FrameDriver(private val sim: Simulation, private val config: SimConfig) {

    private var accumulator: Float = 0f

    /**
     * Ticks dropped because [SimConfig.maxCatchupTicks] was hit.
     *
     * **The one and only place wall-clock honesty breaks.** Unbounded catch-up
     * was rejected because it death-spirals into a freeze, which serves the
     * client worse than judder — so there is a valve, and it is counted rather
     * than silent. A non-zero value here does not mean the game hiccuped; it
     * means the device is below the hardware floor. A session with a non-zero
     * count is not replayable, because ticks that never ran cannot be
     * replayed.
     */
    var droppedTicks: Long = 0L
        private set

    /**
     * Feeds one rendered frame's worth of real elapsed time, runs as many
     * whole ticks as that affords, and returns the render interpolation alpha
     * in `[0, 1)` for `lerp(prevPosition, position, alpha)`.
     *
     * Pass the **real** delta. Clamping it before it gets here reintroduces
     * exactly the defect this class exists to remove.
     */
    fun advance(frameDeltaSeconds: Float, input: InputFrame): Float {
        // A non-finite or negative delta is a caller bug, not a device
        // condition: it would poison the accumulator permanently, and every
        // subsequent frame would be wrong with no sign of where it started.
        require(frameDeltaSeconds.isFinite() && frameDeltaSeconds >= 0f) {
            "frame delta must be finite and non-negative, was $frameDeltaSeconds"
        }

        accumulator += frameDeltaSeconds

        var ticks = 0
        while (accumulator >= Simulation.TICK && ticks < config.maxCatchupTicks) {
            sim.step(input)
            accumulator -= Simulation.TICK
            ticks++
        }

        if (accumulator >= Simulation.TICK) {
            val discarded = (accumulator / Simulation.TICK).toInt()
            droppedTicks += discarded
            accumulator -= discarded * Simulation.TICK
        }

        return accumulator / Simulation.TICK
    }

    /**
     * Drops accrued time. Call on resume.
     *
     * Backgrounding pauses the game rather than accumulating against it, so
     * the ten-minutes-in-the-background case never becomes ten minutes of
     * catch-up. This is what let the ADR 0006 clamp be removed without
     * reintroducing the spiral it was there to prevent — and it means a run
     * cannot top out while the player is not looking at it.
     */
    fun resetAccumulator() {
        accumulator = 0f
    }
}
