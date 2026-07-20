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

    /** Distance between adjacent lattice particles at rest. */
    val spacing: Float = SimConfig.PIECE_WIDTH / (lattice - 1)

    /**
     * Contact radius. Half the lattice spacing, so adjacent particles within a
     * body exactly touch at rest and the body reads as solid material rather
     * than as a bag of separated dots.
     */
    val particleRadius: Float = 0.5f * spacing

    /** Full width of a body including the contact radius on both sides. */
    val pieceExtent: Float = SimConfig.PIECE_WIDTH + 2f * particleRadius

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

        val half = SimConfig.PIECE_WIDTH * 0.5f
        val minX = centerX - half - particleRadius
        val maxX = centerX + half + particleRadius
        val minY = centerY - half - particleRadius
        check(minX >= wellMinX && maxX <= wellMaxX && minY >= wellFloorY) {
            "body at ($centerX, $centerY) does not fit inside the well " +
                "x=[$wellMinX, $wellMaxX] y>=$wellFloorY: piece spans " +
                "x=[$minX, $maxX] y>=$minY"
        }

        // Overlap against already-placed material. O(n*m) and deliberately so:
        // this is scene setup, not the per-frame path.
        val minGapSq = (2f * particleRadius) * (2f * particleRadius)
        for (i in 0 until particleCount) {
            val dx = posX[i] - centerX
            val dy = posY[i] - centerY
            // Cheap reject: outside the new body's bounding circle plus a
            // particle diameter cannot overlap any of its particles.
            val reach = SimConfig.PIECE_WIDTH * 0.7072f + 2f * particleRadius
            if (dx * dx + dy * dy > reach * reach) continue
            for (row in 0 until lattice) {
                for (col in 0 until lattice) {
                    val nx = centerX - half + col * spacing
                    val ny = centerY - half + row * spacing
                    val ex = posX[i] - nx
                    val ey = posY[i] - ny
                    check(ex * ex + ey * ey >= minGapSq) {
                        "body at ($centerX, $centerY) would be seeded overlapping existing " +
                            "material at (${posX[i]}, ${posY[i]}); the contact solver would " +
                            "convert the overlap into launch energy (see spike README bug 1)"
                    }
                }
            }
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
                inContactThisTick[i] = false
                inContactLastTick[i] = false
            }
        }
        particleCount += particlesPerBody

        addConstraints(base)
        return body
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
