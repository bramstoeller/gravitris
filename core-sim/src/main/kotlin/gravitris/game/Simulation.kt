package gravitris.game

import gravitris.physics.SoftBodyWorld
import gravitris.physics.XpbdSolver
import kotlin.math.min

/**
 * The simulation. Construct once with a [SimConfig], then call [step] exactly
 * once per fixed 1/60 s tick.
 *
 * `:app` drives this from an accumulator and interpolates between
 * [SimState.prevPositionX]/[SimState.positionX] for rendering (ADR 0006):
 *
 * ```
 * accumulator += min(frameDelta, MAX_FRAME_DELTA)   // clamp: no spiral of death
 * while (accumulator >= TICK) { sim.step(input); accumulator -= TICK }
 * alpha = accumulator / TICK
 * ```
 *
 * ### What this does not do yet
 *
 * Stage 1 is the physics only (docs/build-order.md Track 1A). There is no
 * piece spawning, no lock detection, no coverage bands, no clear rule, no
 * losing condition and no scoring — those are Stages 3 and 4, deliberately
 * deferred because "the clear rule ... is untunable until the physics is felt.
 * Building it early means tuning it twice." The corresponding [SimState]
 * fields are present and inert; each one documents what it will become.
 *
 * Pieces therefore enter the well through [addPiece], which is a harness
 * affordance for Milestone 1 ("one piece falls into an empty well"), not the
 * spawner. Stage 3 replaces the call site, not this class's shape.
 */
class Simulation(private val config: SimConfig) {

    private val world = SoftBodyWorld(config)
    private val solver = XpbdSolver(world)

    /**
     * Where a new piece's centre sits: as high as it can while still being
     * wholly inside the well.
     *
     * This puts the piece's material in the topmost bands, which is what
     * ADR 0005 requires of it — its overflow test is the fill of the spawn
     * band, and that only means anything if the spawn region *is* one of the
     * coverage bands. [SimState.spawnBandIndex] publishes which one.
     *
     * **Declared before [stateImpl] deliberately.** `State` derives
     * [SimState.spawnBandIndex] from this at construction, and Kotlin
     * initialises properties in declaration order — computing it after the
     * state would read a zero and publish the floor band, not the spawn band.
     */
    private val spawnCenterY: Float =
        config.wellHeight - world.pieceMaxHalfHeight - world.particleRadius

    private val stateImpl = State(config)

    /** Scratch for rotation rollback. Sized once; [applyRotate] allocates nothing. */
    private val rotateScratchX = FloatArray(world.particlesPerBody)
    private val rotateScratchY = FloatArray(world.particlesPerBody)

    private val bands = CoverageBands(
        bandCount = config.bandCount,
        columns = config.bandColumns,
        rowsPerBand = config.bandRows,
        minX = world.wellMinX,
        wellWidth = world.wellMaxX - world.wellMinX,
        bottomY = world.wellFloorY,
        bandHeight = config.wellHeight / config.bandCount,
    )

    private val sequence = PieceSequence(config.seed, ARCHETYPE_COUNT)

    /**
     * The dials the Product Lead turns in front of the client. Mutable and
     * read live — see [MechanicTuning] for what that costs and why it is worth
     * it.
     */
    val tuning = MechanicTuning(config)

    // --- mechanic state -----------------------------------------------------

    val state: SimState get() = stateImpl

    /** Whether the game is dealing pieces. See [start]. */
    private var running: Boolean = false

    /**
     * Whether the active piece is in its positioning window (ADR 0016): parked
     * at the spawn row, gravity suppressed (the body is weightless), sliding
     * horizontally under the player's finger. Cleared the moment it drops.
     */
    private var positioning: Boolean = false

    /** Ticks left in the positioning window; only meaningful while [positioning]. */
    private var positioningRemaining: Int = 0

    /** Consecutive ticks the active piece has been still and touching something. */
    private var stillTicks: Int = 0

    /** Ticks since the active piece first touched anything; -1 before it has. */
    private var touchedTicks: Int = -1

    /** Ticks since the current clear was confirmed; -1 when not clearing. */
    private var clearTicks: Int = -1

    private var clearPhase: Phase.Clearing? = null

    /** Remaining grace ticks while overflowing (ADR 0005); -1 when not in overflow. */
    private var overflowTicks: Int = -1

    /**
     * Reused overflow phase — one instance whose [Phase.Overflow.remainingTicks]
     * is mutated in place, so the grace window allocates nothing per tick, the
     * same bargain [Phase.Clearing] makes.
     */
    private val overflowPhase = Phase.Overflow(0)

    /** Terminal: the grace expired with the spawn band still over the line. */
    private var gameOver: Boolean = false

    /** Centre of the well: where every piece is dealt. */
    private val spawnX: Float = 0.5f * (world.wellMinX + world.wellMaxX)

    /** Bodies scheduled for removal. Sized to capacity; the clear allocates nothing. */
    private val removalScratch = IntArray(world.maxBodies)

    /**
     * Advances exactly one fixed 1/60 s tick. Pure given (state, input, and
     * the current [tuning]).
     *
     * Order matters and is worth stating: input, then physics, then coverage,
     * then the rules. The rules read the world *after* it has moved this tick,
     * so a lock and the clear it triggers are decided against the same
     * positions the renderer is about to draw, not against last tick's.
     */
    fun step(input: InputFrame) {
        applyInput(input)
        solver.step()
        bands.update(world.posX, world.posY, world.particleCount, world.particleRadius)
        advanceMechanic()
        stateImpl.tick++
    }

    // --- the mechanic -------------------------------------------------------

    /**
     * Starts dealing pieces. Until this is called the simulation is physics
     * only — it spawns nothing, locks nothing and clears nothing.
     *
     * A method rather than a constructor flag because it is what separates the
     * *game* from the *solver*, and several callers legitimately want only the
     * solver: the reference benchmark, and every physics test that seeds a
     * pile and watches it settle. Those would be silently corrupted by pieces
     * arriving in the middle of the measurement.
     */
    fun start() {
        running = true
        spawnNext()
    }

    private fun advanceMechanic() {
        if (!running || gameOver) return
        // A clear takes precedence over an overflow grace (ADR 0005): a clear
        // that drops the stack resolves the would-be overflow, so it is run
        // first and, because a clear holds the spawn, the two never overlap.
        //
        // The invariant this order rests on: `clearTicks` and `overflowTicks`
        // are never both >= 0. A clear is only ever begun from a *lock* (an
        // active piece settling), and overflow only from a *due spawn* (no
        // active piece), so the states are mutually exclusive by construction.
        // This ordering is documentation of intent, not a live dependency —
        // reordering the branches is harmless. What is NOT harmless is a future
        // change that lets the two coexist (e.g. beginning overflow while a
        // piece is still active); the check below fails loudly the moment that
        // becomes possible, which is the executable form of the invariant a
        // reordering test could never express. `check` is inline with a lazy
        // message, so it costs a boolean compare and nothing on the happy path.
        check(clearTicks < 0 || overflowTicks < 0) {
            "clear ($clearTicks) and overflow ($overflowTicks) are live at once; " +
                "the two states must be mutually exclusive (ADR 0005)"
        }
        if (clearTicks >= 0) {
            advanceClear()
            return
        }
        if (overflowTicks >= 0) {
            advanceOverflow()
            return
        }
        if (stateImpl.activePieceBody < 0) {
            spawnNext()
            return
        }
        if (positioning) {
            advancePositioning()
            return
        }
        if (hasSettled(stateImpl.activePieceBody)) lockActivePiece()
    }

    /**
     * Runs one tick of the positioning window (ADR 0016). The piece is frozen at
     * the spawn row and does not settle or lock — it only counts down. When the
     * window expires it drops on its own, the same transition a player [drop]
     * triggers early. Counted in ticks, so the window is the same duration on a
     * device that drops frames (ADR 0013).
     */
    private fun advancePositioning() {
        positioningRemaining--
        if (positioningRemaining <= 0) releaseToFall()
    }

    /**
     * Commits the positioning piece to its fall: thaw it so gravity takes over,
     * and reset the lock counters so the debounce measures the fall, not the
     * hover. Idempotent-safe against being reached from both the input path
     * (an early [drop]) and the timer path.
     */
    private fun releaseToFall() {
        if (!positioning) return
        positioning = false
        positioningRemaining = 0
        world.setBodyWeightless(stateImpl.activePieceBody, weightless = false)
        stillTicks = 0
        touchedTicks = -1
    }

    /**
     * Whether the active piece has come to rest.
     *
     * Two conditions, and the second one is the one that took thought.
     *
     * **It must be touching something.** A piece spawns at zero velocity, so a
     * pure energy test locks it the instant it appears, in mid-air. Requiring
     * contact also rules out the top of a bounce, where a piece is genuinely
     * motionless and genuinely not settled.
     *
     * **Its energy must stay low for [SimConfig.lockDebounceTicks] running.**
     * A single-tick test would fire on the instantaneous stillness at the
     * bottom of a squash.
     *
     * And a ceiling, which is the part the naive version gets wrong. Piles in
     * this solver do not fully stop — measured, a settled 16-body pile goes
     * from 142 to 149 contacting particles over 300 idle frames, creeping the
     * whole time. A piece resting in a live pile can therefore be nudged above
     * the energy threshold indefinitely by its neighbours, and the game would
     * simply stop dealing pieces. So a piece that has been in contact for
     * [SimConfig.lockTimeoutTicks] locks regardless. That is a deliberate
     * bound on a known-unbounded wait, not a tuned fudge: tuning the threshold
     * until it looked right would have hidden the creep rather than handled
     * it.
     */
    private fun hasSettled(body: Int): Boolean {
        val base = body * world.particlesPerBody
        val n = world.particlesPerBody

        var touching = false
        var energy = 0f
        for (k in 0 until n) {
            val i = base + k
            if (world.inContactThisTick[i]) touching = true
            energy += world.mass[i] * (world.velX[i] * world.velX[i] + world.velY[i] * world.velY[i])
        }
        energy = 0.5f * energy / n

        if (!touching) {
            stillTicks = 0
            return false
        }

        if (touchedTicks < 0) touchedTicks = 0 else touchedTicks++
        if (touchedTicks >= config.lockTimeoutTicks) return true

        stillTicks = if (energy <= config.lockKineticEnergy) stillTicks + 1 else 0
        return stillTicks >= config.lockDebounceTicks
    }

    /**
     * The piece is done. Release control, then look for bands to clear.
     *
     * If nothing clears, the next piece spawns on the following tick. If
     * something does, [beginClear] takes over and the spawn waits for the
     * whole payoff window.
     */
    private fun lockActivePiece() {
        stateImpl.activePieceBody = -1
        stillTicks = 0
        touchedTicks = -1
        if (!beginClear()) spawnNext()
    }

    // --- the clear rule -----------------------------------------------------

    /**
     * Confirms and starts a clear if any band qualifies. Returns whether one
     * started.
     *
     * Two gates, both from ADR 0005.
     *
     * **Quiescence, via the damping — not via a stack-wide energy test.**
     * ADR 0005 requires that a clear not fire "on a transient bounce spike,
     * which would be the mirror image of the unfairness [the losing condition]
     * exists to prevent". That requirement is real and it is met here by
     * reading the *damped* fill: rise is 0.25/tick, so a one- or two-frame
     * bounce is attenuated to nothing while genuinely sustained material
     * converges well inside [SimConfig.lockDebounceTicks].
     *
     * This used to be a second gate — `solver.kineticEnergy <=
     * quietKineticEnergy` over the whole stack — and it was wrong twice over.
     * **Measured, it made the clear rule unreachable in a real game:** at the
     * lock of a 22-body pile, stack energy was 0.436 against a 0.05 threshold
     * while the fullest band sat at 0.994 against a 0.90 threshold. The game
     * dealt pieces forever and never cleared once in 6 000 ticks.
     *
     * It failed for the reason already written down one method up, in
     * [hasSettled]: piles in this solver do not fully stop, so any wait on
     * stack-wide quiet is a wait on something that may never happen. Worse,
     * [hasSettled] bounds that wait with a timeout and this did not — the
     * doc comment claimed to be "bounded by the lock timeout above", but
     * `beginClear` is asked exactly once per lock, so a gate that fails at
     * that instant does not retry. The clear was not delayed; it was lost.
     *
     * The lesson is the trap's, not this method's: a global quiet test in this
     * solver is never a bound, and a comment asserting a bound is not one.
     *
     * **Something must actually be removed.** A band can be over threshold
     * while no body's centre of mass sits in it — material belonging to
     * pieces centred above and below can fill a band between them. Firing a
     * clear there would remove nothing, leave the band still over threshold,
     * and fire again on the next lock, forever. So the clear is confirmed
     * against the removal list, not against the fill alone.
     */
    private fun beginClear(): Boolean {
        val threshold = tuning.clearThreshold
        var clearing = 0
        for (band in 0 until config.bandCount) {
            if (bands.fill[band] >= threshold) clearing++
        }
        if (clearing == 0) return false

        val doomed = collectBodiesIn(threshold)
        if (doomed == 0) return false

        val cleared = IntArray(clearing)
        var k = 0
        for (band in 0 until config.bandCount) {
            if (bands.fill[band] >= threshold) cleared[k++] = band
        }

        clearPhase = Phase.Clearing(cleared, envelopeTicks() + 1)
        clearTicks = 0
        for (band in cleared) stateImpl.bandClearProgress[band] = 0f
        return true
    }

    /**
     * Fills [removalScratch] with the bodies a clear would remove, and returns
     * how many.
     *
     * **A body is removed when its centroid lies in a clearing band.** The
     * alternative — dissolving the individual particles inside the band and
     * leaving the rest of the body behind — was rejected. It would break the
     * rendering contract outright: `triangleIndices` is body-local and
     * constant for the whole run precisely so the renderer can reuse one index
     * buffer for every body (ADR 0007), and a body with holes in it no longer
     * has the lattice that contract assumes. Removing whole bodies keeps the
     * contract exactly as the Frontend Engineer is building against it.
     *
     * The visible consequence, stated so nobody is surprised by it: a band is
     * 1.0 world units tall and a piece is 2.40, so a clear removes rather more
     * than the band itself — the pieces *sitting in* the band, not a horizontal
     * slice through them. That reads as the material in the glowing zone being
     * released, which is what the brief asks for, and it is the only version
     * of the rule that does not require cutting soft bodies in half.
     */
    private fun collectBodiesIn(threshold: Float): Int {
        var count = 0
        val n = world.particlesPerBody
        for (body in 0 until world.bodyCount) {
            val base = body * n
            var cy = 0f
            for (k in 0 until n) cy += world.posY[base + k]
            val band = bands.bandAt(cy / n)
            if (band >= 0 && bands.fill[band] >= threshold) removalScratch[count++] = body
        }
        return count
    }

    /**
     * Runs the payoff. `docs/ux/feel-feedback.md` is the specification and the
     * brief is the reason:
     *
     * > the clear reads as a release of pressure, with the stack above
     * > dropping and re-settling. That re-settle is the payoff moment of the
     * > whole game and is given time to be watched rather than rushed.
     *
     * So nothing here is instant and nothing teleports. The material stays
     * present for the whole ignition-hold-dissolve envelope, because the flash
     * has to happen on something; then it is removed and the stack falls under
     * the same solver as everything else, at its own pace.
     *
     * **Every duration is a tick count** (ADR 0013). The UX spec writes them
     * in milliseconds and the fixed 60 Hz tick converts them exactly, but they
     * are counted in ticks so that a device dropping frames sees the same
     * sequence take the same wall-clock time — *"frames skippen is prima ...
     * maar niet vertragen."* Nothing here reads a clock.
     */
    private fun advanceClear() {
        clearTicks++
        val envelope = envelopeTicks()
        val phase = clearPhase ?: return

        if (clearTicks < envelope) {
            val progress = clearTicks.toFloat() / envelope
            for (band in phase.bands) stateImpl.bandClearProgress[band] = progress
            phase.remainingTicks = maxTicks() - clearTicks
            return
        }

        if (clearTicks == envelope) {
            removeClearedMaterial()
            for (band in phase.bands) stateImpl.bandClearProgress[band] = -1f
        }

        phase.remainingTicks = maxTicks() - clearTicks

        // The watch window. Resume once the stack has been given its minimum
        // time *and* has gone quiet — or once the ceiling is reached, because
        // this pile's residual twitching has no end and the game must not be
        // held hostage to it (feel-feedback.md says exactly this: "resume
        // control once the stack is visually mostly settled rather than
        // holding the game hostage to a long tail of tiny residual motion").
        val settled = solver.kineticEnergy <= config.quietKineticEnergy
        if ((clearTicks >= minTicks() && settled) || clearTicks >= maxTicks()) {
            clearTicks = -1
            clearPhase = null
            spawnNext()
        }
    }

    /**
     * Removes the doomed bodies, highest index first.
     *
     * Descending order is required, not tidiness: [SoftBodyWorld.removeBody]
     * fills the hole with the last body, so removing low-to-high would move a
     * body that is itself still on the list and the second removal would take
     * the wrong one.
     */
    private fun removeClearedMaterial() {
        val count = collectBodiesIn(tuning.clearThreshold)
        for (k in count - 1 downTo 0) world.removeBody(removalScratch[k])

        // The active piece is always -1 here — a clear can only start from a
        // lock, and no piece spawns until the window closes — but the whole
        // point of swap-remove is that indices move, so this is asserted
        // rather than assumed.
        check(stateImpl.activePieceBody < 0) {
            "a clear removed material while piece ${stateImpl.activePieceBody} was active; " +
                "body indices have moved and the active index is now meaningless"
        }

        // Recompute rather than wait a tick: the bands the renderer reads this
        // frame must match the material that now exists, or the cleared band
        // draws one frame of glow over a gap.
        bands.update(world.posX, world.posY, world.particleCount, world.particleRadius)

        // ...and snap, because the recompute alone does not achieve that. The
        // damping exists to swallow the *bounce* of a heavy landing, which is a
        // transient the player should not see rewarded with a flash. Material
        // vanishing in a clear is the opposite: a real, intended, instantaneous
        // change. Left damped, fill only falls by FALL_PER_TICK — it halves —
        // so the cleared band keeps glowing over empty space for several frames
        // and the recompute above buys nothing.
        //
        // This snaps every band, not only the cleared ones. That is deliberate:
        // a clear removes whole bodies, which span about three bands each, so
        // several bands change discontinuously at once. A band elsewhere that
        // happens to be mid-bounce loses one frame of damping, on the single
        // frame where the largest visual event in the game is happening.
        bands.snap()
    }

    // Tuning is mutable and could be left inconsistent by a dev panel mid-run,
    // so the ordering the sequence depends on is enforced here on read rather
    // than only at construction.
    private fun envelopeTicks(): Int = tuning.clearEnvelopeTicks.coerceAtLeast(1)
    private fun minTicks(): Int = tuning.clearMinTicks.coerceAtLeast(envelopeTicks())
    private fun maxTicks(): Int = tuning.clearMaxTicks.coerceAtLeast(minTicks())

    // --- spawning -----------------------------------------------------------

    /**
     * Brings in the next piece if the spawn region is clear, or opens the
     * overflow grace if it is not (ADR 0005).
     *
     * A blocked spawn is not an error and not a loss: it is the topped-out
     * state, and the fair response is to give the stack time to settle and prove
     * it rather than end the game on a transient bulge. So instead of silently
     * retrying, a due piece with the spawn band over [MechanicTuning.overflowThreshold]
     * — or physically no room — starts the grace window in [beginOverflow].
     */
    private fun spawnNext() {
        if (canSpawn()) doSpawn() else beginOverflow()
    }

    /**
     * Whether the next piece may appear: the spawn band's fill is at or below
     * the overflow line **and** there is physically room to place a piece.
     *
     * The fill test is ADR 0005's primary, legible trigger — it warns before the
     * well is literally blocked. The [SoftBodyWorld.canPlace] test is the safety
     * net that also keeps [doSpawn] from ever calling [SoftBodyWorld.addBody]
     * into a spot it would throw on. Reads the damped [CoverageBands.fill], the
     * same value the clear rule and the renderer read, so a transient bulge is
     * attenuated and cannot trip overflow on its own.
     */
    private fun canSpawn(): Boolean =
        bands.fill[stateImpl.spawnBandIndex] <= tuning.overflowThreshold &&
            world.canPlace(sequence.peek(), spawnX, spawnCenterY)

    private fun doSpawn() {
        val body = world.addBody(sequence.next(), spawnX, spawnCenterY)
        stateImpl.activePieceBody = body
        overflowTicks = -1
        enterPositioning(body)
    }

    /**
     * Puts [body] into its positioning window (ADR 0016): parked where it is,
     * gravity suppressed (weightless), sliding under the finger until the player
     * drops it or the window expires. Shared by [doSpawn] and the
     * [addPositioningPiece] harness so the two cannot drift.
     */
    private fun enterPositioning(body: Int) {
        stillTicks = 0
        touchedTicks = -1
        positioning = true
        positioningRemaining = tuning.positioningTicks.coerceAtLeast(1)
        world.setBodyWeightless(body, weightless = true)
    }

    /**
     * Opens the settle grace at its full window. Only reached from a due spawn:
     * once overflowing, [advanceMechanic] routes to [advanceOverflow], so this
     * is never called again mid-grace and cannot refresh the countdown.
     */
    private fun beginOverflow() {
        overflowTicks = tuning.graceTicks.coerceAtLeast(1)
        overflowPhase.remainingTicks = overflowTicks
    }

    /**
     * Runs one tick of the overflow grace (ADR 0005).
     *
     * The stack is left alone to settle. The instant it has fallen back below
     * the overflow line **and** gone quiet — the same kinetic-energy predicate
     * the clear rule and stability tests use — play resumes with no penalty.
     * Otherwise the grace counts down, tick by tick so it survives dropped ticks
     * and a backgrounding (ADR 0013); when it reaches zero with the band still
     * over the line, the game is over.
     *
     * "No death by transient" falls straight out of this: a hard landing's bulge
     * pushes the *damped* fill up only briefly, and even at its peak the stack is
     * not quiet, so neither the trigger nor the failure fires on it — the stack
     * settles back, goes quiet under the line, and play resumes.
     */
    private fun advanceOverflow() {
        if (canSpawn() && solver.kineticEnergy <= config.quietKineticEnergy) {
            doSpawn()
            return
        }
        overflowTicks--
        overflowPhase.remainingTicks = overflowTicks
        if (overflowTicks <= 0) {
            overflowTicks = -1
            gameOver = true
        }
    }

    /**
     * Places a piece with its centre at ([centerX], [centerY]) and makes it
     * the active piece. Returns the new body index.
     *
     * @throws IllegalStateException if the well is at body capacity, or the
     *   piece would start outside the well or overlapping existing material.
     *   Seeding an overlap is not tolerated: the contact solver converts it
     *   into launch energy, and the spike lost real time to exactly that
     *   before recognising it as a setup bug rather than a solver one.
     */
    fun addPiece(archetype: Int, centerX: Float, centerY: Float): Int {
        val body = world.addBody(archetype, centerX, centerY)
        stateImpl.activePieceBody = body
        return body
    }

    /**
     * Harness affordance: places a piece with [addPiece] and enters its
     * positioning window (ADR 0016), the exact state a real spawn produces —
     * frozen, sliding under drag, rotate ignored — but at a caller-chosen
     * position and without running the dealer. Sits alongside [addPiece] and
     * [clearActivePiece] so a test can drive slide input against a controlled
     * scene. Returns the new body index.
     */
    fun addPositioningPiece(archetype: Int, centerX: Float, centerY: Float): Int {
        val body = addPiece(archetype, centerX, centerY)
        enterPositioning(body)
        return body
    }

    /** Releases player control without removing the piece. */
    fun clearActivePiece() {
        stateImpl.activePieceBody = -1
        positioning = false
        positioningRemaining = 0
    }

    // --- input --------------------------------------------------------------

    /**
     * Gates the tick's raw intents by the active piece's phase (ADR 0016). The
     * recognizer in `:app` stays phase-agnostic; the meaning of a tap is decided
     * here — it drops while positioning, rotates while falling.
     */
    private fun applyInput(input: InputFrame) {
        val body = stateImpl.activePieceBody
        if (body < 0 || body >= world.bodyCount) return
        if (positioning) {
            // Slide to aim, then release to drop. Rotate is ignored so a tap
            // (which `:app` delivers as drop+rotate together) drops, not turns.
            if (input.dragX != 0f) applyDrag(body, input.dragX)
            if (input.drop) releaseToFall()
        } else {
            // Falling: rotate only. Drag and drop are ignored — the fall is real
            // gravity and is neither steered nor hastened.
            if (input.rotate) applyRotate(body)
        }
    }

    /**
     * Moves the active piece horizontally, clamped to the well.
     *
     * The clamp is computed here, where the piece's extents are known, but the
     * motion itself is handed to the solver and applied one substep's share at
     * a time — see [XpbdSolver.dragDeltaX]. Translating the whole tick's drag
     * in one go before the substep loop is what made a held drag against
     * another body buzz: the overlap it seeded was undone inside a single
     * substep and came back out as velocity `substeps` times too large.
     *
     * The drag stays kinematic either way. The solver moves the piece's
     * `substepPrev` along with its position, so dragging through empty space
     * derives no velocity from the drag — moving position alone would fling the
     * piece, the same class of mistake as seeding bodies overlapping.
     */
    private fun applyDrag(body: Int, dragX: Float) {
        val base = body * world.particlesPerBody
        val n = world.particlesPerBody
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (k in 0 until n) {
            val x = world.posX[base + k]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        val r = world.particleRadius
        val lowLimit = world.wellMinX + r - minX
        val highLimit = world.wellMaxX - r - maxX
        val delta = dragX.coerceIn(min(lowLimit, 0f), maxOf(highLimit, 0f))
        if (delta == 0f) return
        solver.dragBody = body
        solver.dragDeltaX = delta
    }

    /**
     * Rotates the active piece a quarter turn clockwise about its centroid.
     *
     * **No trigonometry is involved.** ADR 0006 asks for a lookup table where
     * rotation is unavoidable, because `sin`/`cos` carry 1–2 ulp of platform
     * variance and would break cross-device determinism. A quarter turn is
     * `(x, y) -> (y, -x)`, which is not merely deterministic but exact — no
     * table, no rounding, and it cannot drift when applied repeatedly.
     *
     * The rotation is **rejected outright** if it would leave the piece
     * overlapping other material, rather than being applied and left for the
     * contact solver to push apart, because that push is launch energy. The
     * piece simply does not turn, which is the standard and expected
     * behaviour when a rotation is blocked.
     */
    private fun applyRotate(body: Int) {
        val base = body * world.particlesPerBody
        val n = world.particlesPerBody

        var cx = 0f
        var cy = 0f
        for (k in 0 until n) {
            cx += world.posX[base + k]
            cy += world.posY[base + k]
        }
        cx /= n
        cy /= n

        for (k in 0 until n) {
            rotateScratchX[k] = world.posX[base + k]
            rotateScratchY[k] = world.posY[base + k]
        }

        // Rotate, then translate back inside the well as one rigid move.
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        for (k in 0 until n) {
            val dx = rotateScratchX[k] - cx
            val dy = rotateScratchY[k] - cy
            val nx = cx + dy
            val ny = cy - dx
            world.posX[base + k] = nx
            world.posY[base + k] = ny
            if (nx < minX) minX = nx
            if (nx > maxX) maxX = nx
            if (ny < minY) minY = ny
        }
        val r = world.particleRadius
        var shiftX = 0f
        if (minX < world.wellMinX + r) shiftX = world.wellMinX + r - minX
        if (maxX + shiftX > world.wellMaxX - r) shiftX = world.wellMaxX - r - maxX
        val shiftY = if (minY < world.wellFloorY + r) world.wellFloorY + r - minY else 0f
        if (shiftX != 0f || shiftY != 0f) {
            for (k in 0 until n) {
                world.posX[base + k] += shiftX
                world.posY[base + k] += shiftY
            }
        }

        if (overlapsOtherBody(body)) {
            for (k in 0 until n) {
                world.posX[base + k] = rotateScratchX[k]
                world.posY[base + k] = rotateScratchY[k]
            }
            return
        }

        // Keep the previous-position buffers in step so the turn injects no
        // velocity, exactly as for a drag.
        for (k in 0 until n) {
            val i = base + k
            world.framePrevX[i] = world.posX[i]
            world.framePrevY[i] = world.posY[i]
            world.substepPrevX[i] = world.posX[i]
            world.substepPrevY[i] = world.posY[i]
        }
    }

    /**
     * Direct scan rather than a broadphase query: this runs at most once per
     * tick, only on a tap, and only against the active piece.
     */
    private fun overlapsOtherBody(body: Int): Boolean {
        val base = body * world.particlesPerBody
        val n = world.particlesPerBody
        val minGap = 2f * world.particleRadius
        val minGapSq = minGap * minGap
        for (i in 0 until world.particleCount) {
            if (world.particleBody[i] == body) continue
            for (k in 0 until n) {
                val dx = world.posX[i] - world.posX[base + k]
                val dy = world.posY[i] - world.posY[base + k]
                if (dx * dx + dy * dy < minGapSq) return true
            }
        }
        return false
    }

    /**
     * Harness/probe affordance: shoves the active piece downward at [speed],
     * additively, clamped into a usable band up to the solver's terminal
     * velocity.
     *
     * **Test/probe only — MUST NOT be called from game or production code.** The
     * old hard-drop gesture is gone (ADR 0016): release is the drop and the fall
     * is plain gravity, and reintroducing a velocity-injecting control here would
     * quietly bring hard-drop back as a control path the design removed. This
     * exists solely so the solver-probe tests (`:core-sim`) and `:app`'s
     * compression/haptic range tests can put a piece at [XpbdSolver] terminal
     * speed to exercise broadphase margin, rigidity and impact/compression — the
     * impact-velocity path the removed input used to provide. It sits alongside
     * [addPiece]/[clearActivePiece] as a scene affordance, not the game loop.
     * (A `@VisibleForTesting` annotation would say this to the compiler, but that
     * lives in `androidx.annotation`, which `:core-sim` deliberately cannot
     * depend on — ADR 0002/0008 — so it is stated here and held in review.)
     *
     * The [SLAM_MIN_SPEED]/[SLAM_MAX_SPEED] clamp is a fixed contract: `:app`'s
     * range tests pin their measured constants against a 30-unit slam, so the
     * bounds must not move without telling the Frontend.
     */
    fun slamActivePiece(speed: Float) {
        val body = stateImpl.activePieceBody
        if (body < 0 || body >= world.bodyCount) return
        val v = speed.coerceIn(SLAM_MIN_SPEED, SLAM_MAX_SPEED)
        val base = body * world.particlesPerBody
        for (k in 0 until world.particlesPerBody) {
            world.velY[base + k] -= v
        }
    }

    // --- state view ---------------------------------------------------------

    private inner class State(config: SimConfig) : SimState {
        override val particleCount: Int get() = world.particleCount
        override val positionX: FloatArray get() = world.posX
        override val positionY: FloatArray get() = world.posY
        override val prevPositionX: FloatArray get() = world.framePrevX
        override val prevPositionY: FloatArray get() = world.framePrevY
        override val particleBody: IntArray get() = world.particleBody
        override val particleU: FloatArray get() = world.particleU
        override val particleV: FloatArray get() = world.particleV
        override val particleCompression: FloatArray get() = world.particleCompression
        override val particleEdge: FloatArray get() = world.particleEdge
        override val particleContact: FloatArray get() = world.particleContact

        override val bodyCount: Int get() = world.bodyCount
        override val bodyArchetype: IntArray get() = world.bodyArchetype
        override val bodyLattice: Int = config.lattice
        override val particlesPerBody: Int = world.particlesPerBody
        override val particleRadius: Float = world.particleRadius
        override val particleCapacity: Int = world.particleCapacity
        override val triangleIndices: IntArray get() = world.triangleIndices

        override val bandFill: FloatArray get() = bands.fill
        override val bandClearProgress = FloatArray(config.bandCount) { -1f }
        override val bandBottomY: Float = world.wellFloorY
        override val bandCount: Int = config.bandCount
        override val bandHeight: Float = config.wellHeight / config.bandCount

        override val spawnBandIndex: Int =
            ((spawnCenterY - world.wellFloorY) / (config.wellHeight / config.bandCount))
                .toInt()
                .coerceIn(0, config.bandCount - 1)

        override val phase: Phase get() = when {
            gameOver -> Phase.GameOver
            clearPhase != null -> clearPhase!!
            overflowTicks >= 0 -> overflowPhase
            else -> Phase.Playing
        }

        /** Stage 4. A clear scores nothing yet; the scoring formula is undecided. */
        override val score: Int get() = 0
        override val level: Int get() = 1

        override var tick: Int = 0

        override var activePieceBody: Int = -1

        override val activePiecePhase: PiecePhase
            get() = if (positioning && activePieceBody >= 0) PiecePhase.POSITIONING else PiecePhase.FALLING

        override val positioningTicksRemaining: Int
            get() = if (positioning) positioningRemaining else 0

        override val positioningWindowTicks: Int
            get() = tuning.positioningTicks

        override val landing: LandingEstimate = NoLandingEstimate
        override val impacts: ImpactList = Impacts()
        override val kineticEnergy: Float get() = solver.kineticEnergy
    }

    private inner class Impacts : ImpactList {
        override val count: Int get() = solver.impactCount
        override val x: FloatArray get() = solver.impactX
        override val y: FloatArray get() = solver.impactY
        override val strength: FloatArray get() = solver.impactStrength
    }

    private object NoLandingEstimate : LandingEstimate {
        override val yLow: Float = 0f
        override val yHigh: Float = 0f
        override val xMin: Float = 0f
        override val xMax: Float = 0f

        /** Stage 4. `:app` already has to handle an invalid estimate. */
        override val valid: Boolean = false
    }

    companion object {
        /** The fixed simulation tick, in seconds. */
        const val TICK: Float = XpbdSolver.TICK

        private const val SLAM_MIN_SPEED: Float = 6f
        private const val SLAM_MAX_SPEED: Float = 30f

        /**
         * Tetrominoes in the reference scene: enough to nearly fill the
         * reference well (ADR 0015). Measured: a 20x44 well tops out around
         * 26 (lattice 4) to 31 (lattice 5) tetrominoes, so this fills it without
         * hitting the overflow.
         */
        const val BENCHMARK_BODIES: Int = 24

        /**
         * The calibration reference for the pinned lattice (ADR 0014), and the
         * scene to re-run on a real device to close the host-to-device derating
         * blocker (`.team/blockers.md`).
         *
         * **Lattice 4, the pinned shipping tier.** A tetromino is four cells
         * (ADR 0015), so the material is ~4x denser per unit area than the single
         * blocks ADR 0001 measured. The measurement, on the host, near-full
         * 20x44 well: lattice 4 is ~1 700 particles at ~0.78 ms/frame (≈9 ms at
         * the 12x device derating, inside the 16.67 ms budget); lattice 5 is
         * ~3 100 particles at ~1.56 ms (≈19 ms, *over* budget). That measurement
         * is why ADR 0014 pins the lattice at 4 and retires ADR 0009's runtime
         * tier selection — the pin story lives in that ADR, not here; this scene
         * is only the number behind it and the revisit-trigger for a future
         * faster reference device.
         */
        fun benchmarkReferenceConfig(): SimConfig = SimConfig(
            lattice = 4,
            substeps = 8,
            wellWidth = 20f,
            wellHeight = 44f,
        )

        /**
         * Builds the reference scene: [BENCHMARK_BODIES] tetrominoes dropped in
         * from the top and settled into a pile, ready for the caller to measure.
         *
         * Pieces are dropped one at a time above the current pile (via
         * [addPiece], so the dealer stays off) rather than hand-placed on a
         * grid: a grid at a single-cell pitch would overlap the larger
         * tetrominoes and throw (ADR 0015). Shared by the JVM benchmark test and
         * `:app`'s hidden one-tap device benchmark so the two cannot drift apart
         * and compare different scenes.
         */
        fun buildBenchmarkScene(config: SimConfig = benchmarkReferenceConfig()): Simulation {
            val geom = SoftBodyWorld(config)
            val halfExtent = geom.pieceMaxHalfExtent
            val margin = geom.pieceExtent * 0.2f
            val sim = Simulation(config)
            val input = InputFrame()
            val edgeGap = geom.pieceExtent * 0.15f
            val leftMost = halfExtent + geom.particleRadius + edgeGap
            val rightMost = config.wellWidth - halfExtent - geom.particleRadius - edgeGap
            val span = (rightMost - leftMost).coerceAtLeast(0f)
            val centre = 0.5f * config.wellWidth
            for (b in 0 until BENCHMARK_BODIES) {
                val frac = (b * 0.61803398875f) % 1f
                val x = if (span <= 0f) centre else leftMost + frac * span
                val y = sim.state.let { s ->
                    var top = 0f
                    for (i in 0 until s.particleCount) if (s.positionY[i] > top) top = s.positionY[i]
                    top
                } + halfExtent + margin
                sim.addPiece(archetype = b % ARCHETYPE_COUNT, centerX = x, centerY = y)
                sim.clearActivePiece()
                var f = 0
                while (f < 240 && sim.state.kineticEnergy > config.quietKineticEnergy) {
                    sim.step(input)
                    f++
                }
            }
            return sim
        }

        /**
         * Distinct piece archetypes, used by `:app` as a palette index.
         *
         * Stage 1 gives every archetype the same square lattice: the archetype
         * varies only colour. Real piece silhouettes arrive with the piece
         * sequence in Stage 3 — building them now would mean tuning the
         * material against shapes before anyone has felt the material.
         */
        const val ARCHETYPE_COUNT: Int = 7
    }
}
