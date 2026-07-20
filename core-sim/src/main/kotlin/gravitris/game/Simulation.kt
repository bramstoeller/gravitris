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
class Simulation(config: SimConfig) {

    private val world = SoftBodyWorld(config)
    private val solver = XpbdSolver(world)

    private val stateImpl = State(config)

    /** Scratch for rotation rollback. Sized once; [applyRotate] allocates nothing. */
    private val rotateScratchX = FloatArray(world.particlesPerBody)
    private val rotateScratchY = FloatArray(world.particlesPerBody)

    val state: SimState get() = stateImpl

    /** Advances exactly one fixed 1/60 s tick. Pure given (state, input). */
    fun step(input: InputFrame) {
        applyInput(input)
        solver.step()
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
     * Translates the active piece horizontally, clamped to the well.
     *
     * Position and both previous-position buffers move together, so the drag
     * is kinematic and injects no velocity. Moving position alone would make
     * the solver derive a velocity spike of `drag / h` on the next substep and
     * fling the piece — the same class of mistake as seeding bodies
     * overlapping.
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
        for (k in 0 until n) {
            val i = base + k
            world.posX[i] += delta
            world.framePrevX[i] += delta
            world.substepPrevX[i] += delta
        }
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

        // Stage 3. Zero fill and -1 fill are the documented "nothing is
        // happening" values, so `:app` can wire uBandFill/uBandClear now and
        // see no band glow rather than a special case.
        override val bandFill = FloatArray(config.bandCount)
        override val bandClearProgress = FloatArray(config.bandCount) { -1f }
        override val bandBottomY: Float = world.wellFloorY
        override val bandHeight: Float = config.wellHeight / config.bandCount

        override val phase: Phase get() = Phase.Playing
        override val score: Int get() = 0
        override val level: Int get() = 1

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
