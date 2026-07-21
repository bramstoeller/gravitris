package gravitris.physics

import gravitris.game.SimConfig
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Particle, constraint and body storage for the soft-body solver, in a
 * structure-of-arrays layout.
 *
 * **SoA is adopted for allocation, not for cache.** ADR 0001 measured the
 * layout at 1–3% at the scale this game runs (100–255 KB working set, which
 * fits in L2); the crossover to a real cache win only appears an order of
 * magnitude past our budget. The payoff that does hold is that primitive
 * arrays let the steady-state loop allocate nothing, and on ART a per-frame
 * allocation stream buys GC pauses, and a pause on a 16.67 ms budget is a
 * dropped frame. Every array here is sized to capacity once, at construction.
 *
 * A body is a `lattice` x `lattice` grid of particles held together by
 * structural and shear distance constraints plus two area constraints per
 * lattice cell, so it resists collapsing flat rather than only resisting
 * stretch (ADR 0001). For `lattice` 4/5/6 that is 60/104/160 constraints per
 * body, which reproduces ADR 0001's measured constraint counts exactly.
 */
internal class SoftBodyWorld(val config: SimConfig) {

    // --- geometry -----------------------------------------------------------

    /** Particles along one piece edge. */
    val lattice: Int = config.lattice

    val particlesPerBody: Int = lattice * lattice

    /**
     * Full material width of a body, outer edge to outer edge. **The gameplay
     * constant** — everything below is derived from it, not the other way
     * round (ADR 0011), so changing [lattice] leaves piece size untouched.
     */
    val pieceExtent: Float = config.pieceExtent

    /** Distance between adjacent lattice particles at rest. */
    val spacing: Float = config.spacing

    /**
     * Contact radius. Half the lattice spacing, so adjacent particles within a
     * body exactly touch at rest and the body reads as solid material rather
     * than as a bag of separated dots.
     */
    val particleRadius: Float = config.particleRadius

    /** Centre-to-centre span of the lattice: [pieceExtent] less a radius a side. */
    val pieceWidth: Float = config.pieceWidth

    // Well bounds. The floor is y = 0 and the left wall is x = 0, so band
    // geometry (ADR 0004) starts at the origin and `:app` can map insets onto
    // it directly.
    val wellMinX: Float = 0f
    val wellMaxX: Float = config.wellWidth
    val wellFloorY: Float = 0f

    // --- capacity -----------------------------------------------------------

    /**
     * Derived rather than configured: `SimConfig` carries no body-capacity
     * field and adding one would cross a module boundary (docs/contracts.md).
     * Enough bodies to pack the well twice over, with a floor of 64 so a
     * narrow benchmark well still holds the ADR 0001 reference scene of 60.
     */
    val maxBodies: Int = max(
        64,
        2 * ceil((config.wellWidth * config.wellHeight) / (pieceExtent * pieceExtent).toDouble()).toInt(),
    )

    val particleCapacity: Int = maxBodies * particlesPerBody

    private val distancePerBody: Int = 2 * lattice * (lattice - 1) + 2 * (lattice - 1) * (lattice - 1)
    private val areaPerBody: Int = 2 * (lattice - 1) * (lattice - 1)

    // --- particle state -----------------------------------------------------

    val posX = FloatArray(particleCapacity)
    val posY = FloatArray(particleCapacity)

    /** Position at the start of the current tick, for render interpolation. */
    val framePrevX = FloatArray(particleCapacity)
    val framePrevY = FloatArray(particleCapacity)

    /** Position at the start of the current substep, for velocity derivation. */
    val substepPrevX = FloatArray(particleCapacity)
    val substepPrevY = FloatArray(particleCapacity)

    val velX = FloatArray(particleCapacity)
    val velY = FloatArray(particleCapacity)

    val mass = FloatArray(particleCapacity)
    val invMass = FloatArray(particleCapacity)

    /**
     * Per-particle gravity multiplier, 1 normally and 0 while a piece is being
     * positioned (ADR 0016). Suppressing gravity this way keeps the piece a
     * normal *dynamic* soft body — it still deforms and its drag still resolves
     * per substep — rather than pinning it rigid (`invMass = 0`), which would
     * turn a piece slid into the stack into an immovable shover. The solver
     * multiplies rather than branches, so a positioned piece costs no extra
     * control flow in the integrate loop.
     */
    val gravityScale = FloatArray(particleCapacity) { 1f }

    val particleBody = IntArray(particleCapacity)
    val particleU = FloatArray(particleCapacity)
    val particleV = FloatArray(particleCapacity)
    val particleEdge = FloatArray(particleCapacity)
    val particleCompression = FloatArray(particleCapacity) { 1f }
    val particleContact = FloatArray(particleCapacity)

    /** Per-particle accumulators for [particleCompression], refilled each tick. */
    val compressionCurrent = FloatArray(particleCapacity)
    val compressionRest = FloatArray(particleCapacity)

    /** Contact bookkeeping for impact detection, refilled each tick. */
    val inContactThisTick = BooleanArray(particleCapacity)
    val inContactLastTick = BooleanArray(particleCapacity)

    /** Peak approach speed seen by a particle this tick, for impact strength. */
    val impactSpeed = FloatArray(particleCapacity)

    var particleCount: Int = 0
        private set

    // --- constraints --------------------------------------------------------

    val dcA = IntArray(maxBodies * distancePerBody)
    val dcB = IntArray(maxBodies * distancePerBody)
    val dcRest = FloatArray(maxBodies * distancePerBody)
    val dcLambda = FloatArray(maxBodies * distancePerBody)
    var distanceCount: Int = 0
        private set

    val acA = IntArray(maxBodies * areaPerBody)
    val acB = IntArray(maxBodies * areaPerBody)
    val acC = IntArray(maxBodies * areaPerBody)
    val acRest = FloatArray(maxBodies * areaPerBody)
    val acLambda = FloatArray(maxBodies * areaPerBody)
    var areaCount: Int = 0
        private set

    // --- bodies -------------------------------------------------------------

    val bodyArchetype = IntArray(maxBodies)
    var bodyCount: Int = 0
        private set

    /**
     * Body-local triangle indices, constant for the whole run (ADR 0007). Two
     * triangles per lattice cell, wound counter-clockwise so signed areas are
     * positive and the area constraint does not fight its own rest value.
     */
    val triangleIndices: IntArray = buildTriangleIndices()

    private fun buildTriangleIndices(): IntArray {
        val cells = (lattice - 1) * (lattice - 1)
        val out = IntArray(cells * 6)
        var n = 0
        for (row in 0 until lattice - 1) {
            for (col in 0 until lattice - 1) {
                val p00 = row * lattice + col
                val p10 = row * lattice + col + 1
                val p01 = (row + 1) * lattice + col
                val p11 = (row + 1) * lattice + col + 1
                out[n++] = p00; out[n++] = p10; out[n++] = p11
                out[n++] = p00; out[n++] = p11; out[n++] = p01
            }
        }
        return out
    }

    // --- construction -------------------------------------------------------

    /**
     * Places one body with its lattice centred on ([centerX], [centerY]) and
     * returns its index.
     *
     * **Placement is validated, not trusted.** The spike
     * (`/work/spike/solver-budget/README.md`) records that seeding bodies less
     * than a particle diameter apart let the contact solver convert the
     * overlap into launch energy, which read as a solver problem for a while
     * before it was found to be a setup problem — and that clamping an
     * overflowing body back inside the well reproduced it a second time. So
     * this throws rather than silently seeding a scene whose physics cannot be
     * trusted.
     *
     * @throws IllegalStateException if capacity is exhausted, or the body
     *   would start outside the well or overlapping existing material.
     */
    fun addBody(archetype: Int, centerX: Float, centerY: Float): Int {
        check(bodyCount < maxBodies) {
            "soft-body capacity exhausted: $maxBodies bodies for a " +
                "${config.wellWidth}x${config.wellHeight} well at lattice $lattice"
        }

        val half = pieceWidth * 0.5f
        check(fitsInWell(centerX, centerY)) {
            "body at ($centerX, $centerY) does not fit inside the well " +
                "x=[$wellMinX, $wellMaxX] y>=$wellFloorY: piece spans " +
                "x=[${centerX - half - particleRadius}, ${centerX + half + particleRadius}] " +
                "y>=${centerY - half - particleRadius}"
        }
        check(!overlapsExistingMaterial(centerX, centerY)) {
            "body at ($centerX, $centerY) would be seeded overlapping existing material; " +
                "the contact solver would convert the overlap into launch energy " +
                "(see spike README bug 1). Callers that can legitimately be blocked " +
                "must ask canPlace() first rather than catching this"
        }

        val body = bodyCount++
        bodyArchetype[body] = archetype
        val base = body * particlesPerBody
        val particleMass = config.initialPieceMass
        val inv = 1f / particleMass
        val edgeSpan = (lattice - 1).toFloat()

        for (row in 0 until lattice) {
            for (col in 0 until lattice) {
                val i = base + row * lattice + col
                posX[i] = centerX - half + col * spacing
                posY[i] = centerY - half + row * spacing
                framePrevX[i] = posX[i]
                framePrevY[i] = posY[i]
                substepPrevX[i] = posX[i]
                substepPrevY[i] = posY[i]
                velX[i] = 0f
                velY[i] = 0f
                mass[i] = particleMass
                invMass[i] = inv
                particleBody[i] = body
                particleU[i] = col / edgeSpan
                particleV[i] = row / edgeSpan
                particleEdge[i] =
                    if (row == 0 || row == lattice - 1 || col == 0 || col == lattice - 1) 1f else 0f
                particleCompression[i] = 1f
                particleContact[i] = 0f
                gravityScale[i] = 1f
                inContactThisTick[i] = false
                inContactLastTick[i] = false
            }
        }
        particleCount += particlesPerBody

        addConstraints(base)
        return body
    }

    /**
     * Whether a body could be seeded at ([centerX], [centerY]) right now.
     *
     * The spawner needs to *ask* rather than to try and recover, because a
     * blocked spawn is not an error: it is the state ADR 0005 turns into the
     * losing condition. [addBody] still throws on a bad placement — a scene
     * builder that seeds an overlap has a bug — but the game loop has a
     * legitimate reason to find the well full, and it must find out without
     * relying on an exception for control flow.
     */
    fun canPlace(centerX: Float, centerY: Float): Boolean =
        bodyCount < maxBodies &&
            fitsInWell(centerX, centerY) &&
            !overlapsExistingMaterial(centerX, centerY)

    private fun fitsInWell(centerX: Float, centerY: Float): Boolean {
        val half = pieceWidth * 0.5f
        return centerX - half - particleRadius >= wellMinX &&
            centerX + half + particleRadius <= wellMaxX &&
            centerY - half - particleRadius >= wellFloorY
    }

    /**
     * O(n*m) and deliberately so: this runs on spawn and on scene setup, not
     * on the per-frame path.
     */
    private fun overlapsExistingMaterial(centerX: Float, centerY: Float): Boolean {
        val half = pieceWidth * 0.5f
        val minGapSq = (2f * particleRadius) * (2f * particleRadius)
        // Cheap reject: outside the new body's bounding circle plus a particle
        // diameter cannot overlap any of its particles.
        val reach = pieceWidth * 0.7072f + 2f * particleRadius
        val reachSq = reach * reach
        for (i in 0 until particleCount) {
            val dx = posX[i] - centerX
            val dy = posY[i] - centerY
            if (dx * dx + dy * dy > reachSq) continue
            for (row in 0 until lattice) {
                for (col in 0 until lattice) {
                    val ex = posX[i] - (centerX - half + col * spacing)
                    val ey = posY[i] - (centerY - half + row * spacing)
                    if (ex * ex + ey * ey < minGapSq) return true
                }
            }
        }
        return false
    }

    /**
     * Removes a body and everything belonging to it, by moving the last body
     * into the vacated slot.
     *
     * **Body indices are not stable across this call.** The body that was last
     * takes [body]'s index. Callers holding a body index over a removal — the
     * active piece, above all — must remap it, and the removal list itself
     * must be walked in descending order so that the swap source is never a
     * body still waiting to be removed.
     *
     * Swap-remove rather than compaction because every array here is indexed
     * by a body-major stride: a body owns particles
     * `[b*particlesPerBody, (b+1)*particlesPerBody)` and, because
     * [addConstraints] adds exactly the same number of constraints for every
     * body in body order, it owns constraints `[b*distancePerBody, ...)` and
     * `[b*areaPerBody, ...)` too. Moving one body is therefore three
     * contiguous copies and an index rebase, with nothing to search for.
     *
     * Constraint multipliers are not carried across. They are reset at the top
     * of every substep, so a stale value cannot outlive the copy.
     */
    fun removeBody(body: Int) {
        require(body in 0 until bodyCount) { "no such body: $body of $bodyCount" }

        val last = bodyCount - 1
        if (body != last) {
            val dst = body * particlesPerBody
            val src = last * particlesPerBody
            val n = particlesPerBody

            posX.copyInto(posX, dst, src, src + n)
            posY.copyInto(posY, dst, src, src + n)
            framePrevX.copyInto(framePrevX, dst, src, src + n)
            framePrevY.copyInto(framePrevY, dst, src, src + n)
            substepPrevX.copyInto(substepPrevX, dst, src, src + n)
            substepPrevY.copyInto(substepPrevY, dst, src, src + n)
            velX.copyInto(velX, dst, src, src + n)
            velY.copyInto(velY, dst, src, src + n)
            mass.copyInto(mass, dst, src, src + n)
            invMass.copyInto(invMass, dst, src, src + n)
            gravityScale.copyInto(gravityScale, dst, src, src + n)
            particleU.copyInto(particleU, dst, src, src + n)
            particleV.copyInto(particleV, dst, src, src + n)
            particleEdge.copyInto(particleEdge, dst, src, src + n)
            particleCompression.copyInto(particleCompression, dst, src, src + n)
            particleContact.copyInto(particleContact, dst, src, src + n)
            inContactThisTick.copyInto(inContactThisTick, dst, src, src + n)
            inContactLastTick.copyInto(inContactLastTick, dst, src, src + n)
            impactSpeed.copyInto(impactSpeed, dst, src, src + n)
            for (k in 0 until n) particleBody[dst + k] = body

            bodyArchetype[body] = bodyArchetype[last]

            // Constraints reference absolute particle indices, so rebase them
            // by the same distance the particles moved.
            val shift = dst - src
            rebase(dcA, dcB, null, body, last, distancePerBody, shift, dcRest)
            rebase(acA, acB, acC, body, last, areaPerBody, shift, acRest)
        }

        bodyCount--
        particleCount -= particlesPerBody
        distanceCount -= distancePerBody
        areaCount -= areaPerBody
    }

    /**
     * Suppresses or restores gravity for a whole body (ADR 0016) by setting its
     * particles' [gravityScale]. The positioning phase parks a piece weightless
     * this way, then restores its weight on release so it falls under the normal
     * solver.
     *
     * The body stays **dynamic** — it deforms and its drag resolves per substep
     * as always — because only gravity is removed, not its mass. Restoring sets
     * the scale back to exactly 1, so a suppress/restore round trip is
     * bit-identical and leaves determinism intact.
     */
    fun setBodyWeightless(body: Int, weightless: Boolean) {
        require(body in 0 until bodyCount) { "no such body: $body of $bodyCount" }
        val base = body * particlesPerBody
        val scale = if (weightless) 0f else 1f
        for (k in 0 until particlesPerBody) gravityScale[base + k] = scale
    }

    private fun rebase(
        a: IntArray,
        b: IntArray,
        c: IntArray?,
        dstBody: Int,
        srcBody: Int,
        perBody: Int,
        shift: Int,
        rest: FloatArray,
    ) {
        val dst = dstBody * perBody
        val src = srcBody * perBody
        for (k in 0 until perBody) {
            a[dst + k] = a[src + k] + shift
            b[dst + k] = b[src + k] + shift
            if (c != null) c[dst + k] = c[src + k] + shift
            rest[dst + k] = rest[src + k]
        }
    }

    private fun addConstraints(base: Int) {
        // Structural: horizontal and vertical lattice edges.
        for (row in 0 until lattice) {
            for (col in 0 until lattice - 1) {
                addDistance(base + row * lattice + col, base + row * lattice + col + 1)
            }
        }
        for (row in 0 until lattice - 1) {
            for (col in 0 until lattice) {
                addDistance(base + row * lattice + col, base + (row + 1) * lattice + col)
            }
        }
        // Shear: both diagonals of every cell. Without these the lattice is a
        // hinge mechanism and shears flat under load at no constraint cost.
        for (row in 0 until lattice - 1) {
            for (col in 0 until lattice - 1) {
                addDistance(base + row * lattice + col, base + (row + 1) * lattice + col + 1)
                addDistance(base + row * lattice + col + 1, base + (row + 1) * lattice + col)
            }
        }
        // Area: two per cell, matching the render triangles.
        for (row in 0 until lattice - 1) {
            for (col in 0 until lattice - 1) {
                val p00 = base + row * lattice + col
                val p10 = base + row * lattice + col + 1
                val p01 = base + (row + 1) * lattice + col
                val p11 = base + (row + 1) * lattice + col + 1
                addArea(p00, p10, p11)
                addArea(p00, p11, p01)
            }
        }
    }

    private fun addDistance(a: Int, b: Int) {
        val k = distanceCount++
        dcA[k] = a
        dcB[k] = b
        val dx = posX[a] - posX[b]
        val dy = posY[a] - posY[b]
        dcRest[k] = sqrt(dx * dx + dy * dy)
        dcLambda[k] = 0f
    }

    private fun addArea(a: Int, b: Int, c: Int) {
        val k = areaCount++
        acA[k] = a
        acB[k] = b
        acC[k] = c
        acRest[k] = signedArea(a, b, c)
        acLambda[k] = 0f
    }

    fun signedArea(a: Int, b: Int, c: Int): Float =
        0.5f * ((posX[b] - posX[a]) * (posY[c] - posY[a]) - (posX[c] - posX[a]) * (posY[b] - posY[a]))

    /** Total kinetic energy, `0.5 * sum(m * v^2)`. The stability metric. */
    fun kineticEnergy(): Float {
        var sum = 0f
        for (i in 0 until particleCount) {
            sum += mass[i] * (velX[i] * velX[i] + velY[i] * velY[i])
        }
        return 0.5f * sum
    }
}
