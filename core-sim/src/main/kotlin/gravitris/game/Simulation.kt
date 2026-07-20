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

    /** Consecutive ticks the active piece has been still and touching something. */
    private var stillTicks: Int = 0

    /** Ticks since the active piece first touched anything; -1 before it has. */
    private var touchedTicks: Int = -1

    /** Ticks since the current clear was confirmed; -1 when not clearing. */
    private var clearTicks: Int = -1

    private var clearPhase: Phase.Clearing? = null

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
        if (!running) return
        if (clearTicks >= 0) {
            advanceClear()
            return
        }
        if (stateImpl.activePieceBody < 0) {
            spawnNext()
            return
        }
        if (hasSettled(stateImpl.activePieceBody)) lockActivePiece()
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
     * **Quiescence.** "A clear also requires quiescence ... this stops a clear
     * firing on a transient bounce spike, which would be the mirror image of
     * the unfairness [the losing condition] exists to prevent." The lock
     * debounce has already established that the *piece* is still; this asks
     * the same of the stack. It is bounded by the lock timeout above rather
     * than waited on forever, for the creep reason given there.
     *
     * **Something must actually be removed.** A band can be over threshold
     * while no body's centre of mass sits in it — material belonging to
     * pieces centred above and below can fill a band between them. Firing a
     * clear there would remove nothing, leave the band still over threshold,
     * and fire again on the next lock, forever. So the clear is confirmed
     * against the removal list, not against the fill alone.
     */
    private fun beginClear(): Boolean {
        if (solver.kineticEnergy > config.quietKineticEnergy) return false

        val threshold = tuning.clearThreshold
        var clearing = 0
        for (band in 0 until config.bandCount) {
            if (bands.fillRaw[band] >= threshold) clearing++
        }
        if (clearing == 0) return false

        val doomed = collectBodiesIn(threshold)
        if (doomed == 0) return false

        val cleared = IntArray(clearing)
        var k = 0
        for (band in 0 until config.bandCount) {
            if (bands.fillRaw[band] >= threshold) cleared[k++] = band
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
            if (band >= 0 && bands.fillRaw[band] >= threshold) removalScratch[count++] = body
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
    }

    // Tuning is mutable and could be left inconsistent by a dev panel mid-run,
    // so the ordering the sequence depends on is enforced here on read rather
    // than only at construction.
    private fun envelopeTicks(): Int = tuning.clearEnvelopeTicks.coerceAtLeast(1)
    private fun minTicks(): Int = tuning.clearMinTicks.coerceAtLeast(envelopeTicks())
    private fun maxTicks(): Int = tuning.clearMaxTicks.coerceAtLeast(minTicks())

    // --- spawning -----------------------------------------------------------

    /**
     * Brings in the next piece, if there is room for it.
     *
     * **A blocked spawn is not an error and not a loss.** It is the state
     * ADR 0005 builds the losing condition out of: the well is full, and the
     * right response is to give the stack time to settle and prove it. That
     * grace window is Stage 4 and deliberately not built here. What is built
     * here is the shape it needs — the spawn simply does not happen, the
     * simulation stays in [Phase.Playing] with no active piece, and the next
     * tick tries again. A stack that settles back down resumes play on its
     * own; one that does not will sit there until Stage 4 gives it a verdict.
     *
     * The retry is what Stage 4 replaces with [Phase.Overflow], and it is the
     * only thing that needs replacing.
     */
    private fun spawnNext() {
        val x = 0.5f * (world.wellMinX + world.wellMaxX)
        if (!world.canPlace(x, spawnCenterY)) return
        stateImpl.activePieceBody = world.addBody(sequence.next(), x, spawnCenterY)
        stillTicks = 0
        touchedTicks = -1
    }

    /**
     * Where a new piece's centre sits: as high as it can while still being
     * wholly inside the well.
     *
     * This puts the piece's material in the topmost bands, which is what
     * ADR 0005 requires of it — its overflow test is the fill of the spawn
     * band, and that only means anything if the spawn region *is* one of the
     * coverage bands. [SimState.spawnBandIndex] publishes which one.
     */
    private val spawnCenterY: Float =
        config.wellHeight - 0.5f * world.pieceWidth - world.particleRadius

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

    /** Releases player control without removing the piece. */
    fun clearActivePiece() {
        stateImpl.activePieceBody = -1
    }

    // --- input --------------------------------------------------------------

    private fun applyInput(input: InputFrame) {
        val body = stateImpl.activePieceBody
        if (body < 0 || body >= world.bodyCount) return
        if (input.rotate) applyRotate(body)
        if (input.dragX != 0f) applyDrag(body, input.dragX)
        if (input.hardDrop) applyHardDrop(body, input.hardDropVelocity)
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
     * Adds downward velocity to the active piece. Additive rather than
     * absolute, so a hard drop is a shove on top of whatever the piece was
     * already doing. The flick speed from `:app` is clamped into a usable
     * band: a slow flick still commits the piece, and a fast one cannot exceed
     * the solver's terminal velocity.
     */
    private fun applyHardDrop(body: Int, flickVelocity: Float) {
        val speed = flickVelocity.coerceIn(HARD_DROP_MIN_SPEED, HARD_DROP_MAX_SPEED)
        val base = body * world.particlesPerBody
        for (k in 0 until world.particlesPerBody) {
            world.velY[base + k] -= speed
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
        override val particleRadius: Float = world.particleRadius
        override val particleCapacity: Int = world.particleCapacity
        override val triangleIndices: IntArray get() = world.triangleIndices

        override val bandFill: FloatArray get() = bands.fill
        override val bandClearProgress = FloatArray(config.bandCount) { -1f }
        override val bandBottomY: Float = world.wellFloorY
        override val bandHeight: Float = config.wellHeight / config.bandCount

        override val spawnBandIndex: Int =
            ((spawnCenterY - world.wellFloorY) / (config.wellHeight / config.bandCount))
                .toInt()
                .coerceIn(0, config.bandCount - 1)

        override val phase: Phase get() = clearPhase ?: Phase.Playing

        /** Stage 4. A clear scores nothing yet; the scoring formula is undecided. */
        override val score: Int get() = 0
        override val level: Int get() = 1

        override var tick: Int = 0

        override var activePieceBody: Int = -1

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

        private const val HARD_DROP_MIN_SPEED: Float = 6f
        private const val HARD_DROP_MAX_SPEED: Float = 30f

        /** Bodies in the reference scene, matching ADR 0001's measured row. */
        const val BENCHMARK_BODIES: Int = 60

        /**
         * The configuration to re-run on a real device to close the
         * host-to-device derating blocker (ADR 0009, `.team/blockers.md`).
         *
         * Lattice 4 with [BENCHMARK_BODIES] bodies reproduces ADR 0001's
         * measured workload exactly: 960 particles and 3 600 constraints, at 8
         * substeps and compliance 1e-6. Host p50 was 0.497 ms/frame.
         *
         * The well is deliberately **wide** rather than the tall narrow tower
         * the spike happened to seed. ADR 0003 flags that its stability and
         * contact numbers came from a pile ~4 units wide and ~46 tall, that "a
         * wide well produces more simultaneous contacts per body than a narrow
         * tower, and that specific configuration has not been measured", and
         * that it should be checked at Milestone 1. Particle and constraint
         * counts — which is what the cost model is built on — are identical
         * either way; contact count is the part that differs, and this is the
         * shape the real game has.
         */
        fun benchmarkReferenceConfig(): SimConfig = SimConfig(
            lattice = 4,
            substeps = 8,
            wellWidth = 12f,
            wellHeight = 44f,
        )

        /**
         * Seeding pitch as a multiple of a body's full extent. See
         * [buildBenchmarkScene].
         */
        const val PLACEMENT_GAP: Float = 1.05f

        /**
         * Builds the reference scene: [BENCHMARK_BODIES] bodies packed from
         * the floor up, settled by the caller.
         *
         * Shared by the JVM benchmark test and `:app`'s hidden one-tap device
         * benchmark so the two cannot drift apart and produce a derating ratio
         * that compares different scenes.
         */
        fun buildBenchmarkScene(config: SimConfig = benchmarkReferenceConfig()): Simulation {
            val sim = Simulation(config)
            // Pitch, not extent: bodies are seeded with a deliberate gap.
            // Placing them exactly touching would put neighbouring particles
            // at exactly one diameter, where float rounding decides whether
            // the seeding guard fires and whether the contact solver sees an
            // overlap on tick one. A gap costs nothing and settles out in a
            // few frames.
            val pitch = sim.world.pieceExtent * PLACEMENT_GAP
            val perRow = ((config.wellWidth - pitch) / pitch).toInt() + 1
            check(perRow >= 1) { "benchmark well is narrower than one piece" }
            for (b in 0 until BENCHMARK_BODIES) {
                val row = b / perRow
                val col = b % perRow
                sim.addPiece(
                    archetype = b % ARCHETYPE_COUNT,
                    centerX = pitch * 0.5f + col * pitch,
                    centerY = pitch * 0.5f + row * pitch,
                )
            }
            sim.clearActivePiece()
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
