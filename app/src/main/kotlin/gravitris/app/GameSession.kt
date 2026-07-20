package gravitris.app

import gravitris.game.FrameDriver
import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation

/**
 * The running game.
 *
 * This is what replaced the Milestone-1 `SquishToy` as the thing `:app` drives.
 * The toy called [Simulation.addPiece] / [Simulation.clearActivePiece] by hand
 * and never switched the mechanic on, so a band could fill and glow but never
 * clear. This does the one thing that was missing: it calls [Simulation.start],
 * which is what makes [Simulation] deal pieces and run its own lock, clear and
 * re-settle rules (`Simulation.advanceMechanic`). Everything the renderer reads
 * still comes straight off [SimState], so nothing downstream changed.
 *
 * ## Frame timing is the core's, not the render loop's
 *
 * Time is advanced through [FrameDriver] (ADR 0013), not a hand-rolled
 * accumulator in the GL thread. The distinction is a hard client requirement —
 * *"frames skippen is prima ... maar niet vertragen"* — so the no-clamp,
 * run-more-ticks-never-a-bigger-one policy lives in `:core-sim` where it is
 * tested and cannot be quietly reverted by someone tidying the renderer. The
 * old shell accumulator clamped the delta, which discards wall-clock time on an
 * overrun — exactly the dilation ADR 0013 forbids. That clamp is gone with the
 * toy.
 *
 * ## Input reaches exactly one tick, even under catch-up
 *
 * [advance] drains fresh intent *per tick* via the [FrameDriver] overload, so a
 * tap or a drag delta lands on one tick and the catch-up ticks after it see an
 * empty frame. Passing one [InputFrame] across several catch-up ticks — which
 * the plain `advance(delta, input)` does, and which is correct for a fixed
 * replay — would multiply a drag by the tick count and fire a one-shot on every
 * tick (`InputFrame`'s "spins every tick" failure, triggered by a frame drop).
 * The software emulator runs many ticks per frame, so this is not a corner
 * case there; it is the common case.
 *
 * ## Losing is not built here
 *
 * There is no game-over yet. `Simulation` treats a blocked spawn as "retry next
 * tick", so a full well **stalls honestly** — pieces stop arriving until the
 * stack settles enough to make room — rather than faking an ending. The real
 * losing condition (ADR 0005: [gravitris.game.Phase.Overflow] grace then
 * [gravitris.game.Phase.GameOver]) is the Backend Engineer's separate work; both
 * phases are already declared on `SimState`, and the game-over UI that reads
 * them is a deliberately-deferred follow-up so this build ships no unreachable
 * screen.
 */
class GameSession(
    config: SimConfig,
    /**
     * A **debug-only verification** override for the clear threshold, or `null`
     * to use the [SimConfig] default (0.90).
     *
     * The shipped threshold is a deliberate guess the client tunes by eye
     * (`MechanicTuning`), and 0.90 needs a well packed almost solidly across a
     * band — reachable in real play, but not reliably reachable by crude
     * `adb shell input` on a slow software emulator inside one scripted session.
     * `make playthrough` therefore passes a lower, reachable value so the clear
     * — spawn, lock, ignite, remove, re-settle — can actually be captured as
     * proof. It changes *when* a band clears, never *how*, so a clear filmed at
     * a lower threshold proves the identical mechanism. `MainActivity` only
     * honours it on a debuggable build; a release build ignores it entirely.
     */
    clearThresholdOverride: Float? = null,
) {

    private val simulation = Simulation(config)
    private val driver = FrameDriver(simulation, config)

    init {
        if (clearThresholdOverride != null) {
            simulation.tuning.clearThreshold = clearThresholdOverride
        }
        // The line that turns the solver into a game. Until this call the
        // simulation spawns, locks and clears nothing (Simulation.start).
        simulation.start()
    }

    val state: SimState get() = simulation.state

    /**
     * Advance by one rendered frame's worth of real elapsed time and return the
     * interpolation alpha for `lerp(prev, current, alpha)`.
     *
     * [drainTick] is called by [FrameDriver] with a reused frame immediately
     * before each tick; fill it from the pending player intent. Pass the *real*
     * delta — clamping it here is the defect ADR 0013 removed.
     */
    fun advance(frameDeltaSeconds: Float, drainTick: (InputFrame) -> Unit): Float =
        driver.advance(frameDeltaSeconds, drainTick)

    /** Drop accrued time. Call on resume and after any render-thread stall, so
     *  the first frame back does not spend the whole catch-up budget at once. */
    fun resetAccumulator() = driver.resetAccumulator()
}
