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
     * Reused input for the per-tick-drain overload, so live play allocates
     * nothing per frame. Cleared before each drain.
     */
    private val tickInput = InputFrame()

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
     * Feeds one rendered frame's worth of real elapsed time, runs as many whole
     * ticks as that affords with the **same** [input] on each, and returns the
     * render interpolation alpha in `[0, 1)` for `lerp(prevPosition, position,
     * alpha)`.
     *
     * Pass the **real** delta. Clamping it before it gets here reintroduces
     * exactly the defect this class exists to remove.
     *
     * **This overload is for fixed input — replay fixtures, tests, an idle
     * frame.** Applying one [InputFrame] to every catch-up tick is wrong for
     * *live* play: a per-frame drag delta ([InputFrame.dragX]) would move the
     * piece once per tick, so a hitch that runs three catch-up ticks moves it
     * three times (breaking the 1:1 mapping `docs/ux/gestures.md` promises), and
     * a one-shot ([InputFrame.rotate]/[InputFrame.drop]) would fire on every
     * one of those ticks — the exact "spins every tick" failure [InputFrame]
     * warns about, now triggered by a dropped frame. For live gestures use the
     * `drainTick` overload, which asks for fresh intent per tick.
     */
    fun advance(frameDeltaSeconds: Float, input: InputFrame): Float {
        requireRealDelta(frameDeltaSeconds)
        accumulator += frameDeltaSeconds

        var ticks = 0
        while (accumulator >= Simulation.TICK && ticks < config.maxCatchupTicks) {
            sim.step(input)
            accumulator -= Simulation.TICK
            ticks++
        }

        return finishFrame()
    }

    /**
     * Live-play variant: runs as many whole ticks as the delta affords, asking
     * [drainTick] for **fresh intent before each one**, and returns the render
     * interpolation alpha.
     *
     * Before every tick the driver clears a reused [InputFrame] and hands it to
     * [drainTick] to fill from the caller's pending intent, then steps the sim
     * with it. So under a dropped frame that runs N catch-up ticks, a held drag
     * is delivered as the per-tick share the caller drains (1:1, not N×), and a
     * one-shot the caller drains once fires on exactly one tick — the following
     * ticks see an empty frame because the caller has nothing left to give.
     * That keeps `docs/ux/gestures.md`'s 1:1 mapping and [InputFrame]'s
     * "consumed on the tick it is read" honest across frame hitches.
     *
     * **Pass a stable [drainTick]** — a field-held lambda or method reference,
     * not a freshly-captured closure each frame — or the allocation lands on the
     * caller's per-frame path. The driver itself allocates nothing here.
     *
     * The delta policy is identical to the fixed-input overload: no clamp,
     * catch-up by more ticks not bigger ones, overrun past
     * [SimConfig.maxCatchupTicks] counted into [droppedTicks].
     */
    fun advance(frameDeltaSeconds: Float, drainTick: (InputFrame) -> Unit): Float {
        requireRealDelta(frameDeltaSeconds)
        accumulator += frameDeltaSeconds

        var ticks = 0
        while (accumulator >= Simulation.TICK && ticks < config.maxCatchupTicks) {
            tickInput.clear()
            drainTick(tickInput)
            sim.step(tickInput)
            accumulator -= Simulation.TICK
            ticks++
        }

        return finishFrame()
    }

    // A non-finite or negative delta is a caller bug, not a device condition: it
    // would poison the accumulator permanently, and every subsequent frame would
    // be wrong with no sign of where it started.
    private fun requireRealDelta(frameDeltaSeconds: Float) {
        require(frameDeltaSeconds.isFinite() && frameDeltaSeconds >= 0f) {
            "frame delta must be finite and non-negative, was $frameDeltaSeconds"
        }
    }

    /**
     * Discards any whole-tick remainder beyond [SimConfig.maxCatchupTicks] into
     * [droppedTicks] and returns the sub-tick interpolation alpha. The one and
     * only place wall-clock honesty breaks, and it is counted.
     */
    private fun finishFrame(): Float {
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
