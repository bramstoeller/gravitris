package gravitris.game

/**
 * The dials that get turned **while the game is running**, in front of the
 * client, without a rebuild.
 *
 * Everything else in the product is configured through [SimConfig], which is
 * immutable by design: a changed value means a new [Simulation], and that is
 * what keeps determinism a property you can state simply (ADR 0006). This
 * class is the deliberate exception, and it exists because of one sentence in
 * the brief that ADR 0004 promotes to a requirement:
 *
 * > the ~90% figure is explicitly a guess ... **runtime tunability is a
 * > requirement, not a nicety**.
 *
 * Nobody has played this game. The clear threshold cannot be chosen by
 * argument, only by watching someone's face while it changes. So the clear
 * rule reads [clearThreshold] from here every time it evaluates, rather than
 * from [SimConfig], and the dev panel (ADR 0008, Stage 4C) writes to it.
 *
 * ### What this costs, stated plainly
 *
 * **Mutating this mid-run makes that run unrepeatable.** The determinism
 * contract is "same construction + same tick-indexed input sequence = the same
 * result"; a value that changed at an unrecorded moment is not part of either.
 * That is an acceptable price for a tuning dial and an unacceptable one for a
 * replay fixture, so:
 *
 * - **Replay fixtures must not touch this.** Build the [Simulation], leave the
 *   tuning at its [SimConfig]-derived defaults, drive ticks. `DeterminismTest`
 *   holds that line.
 * - Fields are plain `var`s and not `@Volatile`. `:app` drives the simulation
 *   from one thread and the dev panel posts to that same thread; if that ever
 *   stops being true, this is the class that needs a memory barrier, and it is
 *   called out here so the question is asked rather than discovered.
 *
 * ### Why the clear timings live here too
 *
 * They are the same kind of unknown. `docs/ux/feel-feedback.md` specifies the
 * band-clear sequence to the millisecond and then says, of every number in it,
 * that they are "numbers to build with, numbers to retune once there's a
 * playable build on the client's actual device". The payoff moment is the one
 * thing in this product most worth tuning by eye.
 *
 * **They are tick counts, not milliseconds** — ADR 0013. A duration derived
 * from wall-clock would stretch on a device that drops frames, and the client
 * ruled that out in as many words: *"frames skippen is prima, of een lagere
 * frame rate, maar niet vertragen."* At the fixed 60Hz tick, 60 ticks is one
 * second on every device, including one that only renders 20 of them.
 */
class MechanicTuning(config: SimConfig) {

    /**
     * Fraction of a band's occupancy cells that must be filled for it to
     * clear. Live; read every time the clear rule evaluates.
     *
     * **Tied to [SimConfig.bandColumns]/[SimConfig.bandRows] and to
     * [SimConfig.lattice].** A coarser bitmap or a coarser lattice both
     * over-report fill, so a threshold tuned at one resolution is wrong at
     * another (ADR 0004). Retune after changing either.
     */
    var clearThreshold: Float = config.clearThreshold

    /**
     * Ticks from clear-confirmed to material removed: the ignition flash, the
     * hold, and the dissolve.
     *
     * 24 ticks = 400 ms, which is `feel-feedback.md`'s envelope exactly —
     * 120 ms flash, 80 ms hold, 200 ms dissolve. The material is deliberately
     * still *there* for all of it; the flash has to happen on something.
     */
    var clearEnvelopeTicks: Int = 24

    /**
     * Minimum total ticks the clear holds play, measured from confirmation.
     *
     * 48 ticks = 800 ms: the 400 ms envelope above plus 400 ms of watching the
     * stack drop. This is the floor the brief's payoff moment rests on — *"the
     * re-settle is the payoff moment of the whole game and is given time to be
     * watched rather than rushed"* — so the sequence does not end early even
     * if the stack happens to settle instantly.
     *
     * Per `feel-feedback.md`, **this does not shrink as difficulty rises.**
     */
    var clearMinTicks: Int = 48

    /**
     * Hard ceiling on the same window. 84 ticks = 1400 ms.
     *
     * Without it the game would be held hostage to the long tail of tiny
     * residual motion that this solver's piles never quite lose. With it, the
     * re-settle is watched and then play resumes whether or not the last
     * particle has stopped twitching.
     */
    var clearMaxTicks: Int = 84

    /**
     * Fill of the spawn band above which a due piece triggers overflow rather
     * than spawning (ADR 0005). Live; read each time a piece is due and each
     * tick of the grace window.
     *
     * ~50% is a starting guess like [clearThreshold] — the spawn band is the
     * topmost coverage band, so this is "the top of the well is half-full when
     * the next piece needs to appear". Tuned by watching how it feels to be
     * warned, exactly the reason it is a live dial and not a [SimConfig]
     * constant.
     */
    var overflowThreshold: Float = config.overflowThreshold

    /**
     * Ticks the stack is given to settle back below [overflowThreshold] before
     * the game ends (ADR 0005). 90 ticks = 1.5 s.
     *
     * A tick count, not a wall-clock duration (ADR 0013): the grace freezes and
     * resumes correctly across a backgrounding, so a run cannot top out while
     * the player is not looking. Long enough that a hard landing's transient
     * bulge falls back within it; short enough that the game does not stall at
     * its tensest moment.
     */
    var graceTicks: Int = config.graceTicks

    /**
     * Ticks a new piece may be slid left/right before it drops on its own
     * (ADR 0016). The positioning window — the client's "much less long able to
     * move" — and the time pressure that is half the difficulty fix.
     *
     * A **tick** count, not wall-clock (ADR 0013), so the window is the same
     * duration on a device that drops frames. Live because "short" and "urgent"
     * are a feel the client sets by playing, not by argument — the Product Lead
     * turns this in front of them. 50 ticks ≈ 0.83 s is a starting guess.
     */
    var positioningTicks: Int = 50

    init {
        require(clearEnvelopeTicks >= 1) { "clearEnvelopeTicks must be >= 1" }
        require(positioningTicks >= 1) { "positioningTicks must be >= 1, was $positioningTicks" }
        require(graceTicks >= 1) { "graceTicks must be >= 1, was $graceTicks" }
        require(clearMinTicks >= clearEnvelopeTicks) {
            "clearMinTicks ($clearMinTicks) must be >= clearEnvelopeTicks ($clearEnvelopeTicks); " +
                "play cannot resume before the material has been removed"
        }
        require(clearMaxTicks >= clearMinTicks) {
            "clearMaxTicks ($clearMaxTicks) must be >= clearMinTicks ($clearMinTicks)"
        }
    }
}
