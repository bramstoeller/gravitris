// SPIKE CODE — NOT PRODUCTION.
//
// Scene construction and the coverage-band candidate algorithm.

package spike

import kotlin.math.max
import kotlin.math.min

/** Well geometry, in simulation units. 1 unit ~= 1 "cell" of a classic well. */
object Well {
    const val MIN_X = 0.0f
    const val MAX_X = 10.0f
    const val FLOOR_Y = 0.0f
    const val TOP_Y = 20.0f
}

/**
 * Builds a lattice soft body: [n] x [n] particles, spacing [s].
 * Structural + shear distance constraints, plus two area constraints per cell
 * so the body resists collapsing flat rather than only resisting stretch.
 */
fun World.addLatticeBody(
    originX: Float,
    originY: Float,
    n: Int,
    s: Float,
    massPerParticle: Float,
    body: Int,
    distanceCompliance: Float,
    areaCompliance: Float,
) {
    val base = particleCount
    for (row in 0 until n) {
        for (col in 0 until n) {
            addParticle(originX + col * s, originY + row * s, massPerParticle, body)
        }
    }
    fun idx(r: Int, c: Int) = base + r * n + c

    for (r in 0 until n) for (c in 0 until n) {
        if (c + 1 < n) addDistance(idx(r, c), idx(r, c + 1), distanceCompliance)   // structural H
        if (r + 1 < n) addDistance(idx(r, c), idx(r + 1, c), distanceCompliance)   // structural V
        if (r + 1 < n && c + 1 < n) {
            addDistance(idx(r, c), idx(r + 1, c + 1), distanceCompliance)          // shear
            addDistance(idx(r, c + 1), idx(r + 1, c), distanceCompliance)          // shear
            addArea(idx(r, c), idx(r, c + 1), idx(r + 1, c), areaCompliance)
            addArea(idx(r, c + 1), idx(r + 1, c + 1), idx(r + 1, c), areaCompliance)
        }
    }
}

/**
 * A full well: [bodies] lattice bodies pre-placed in a stack, so the benchmark
 * measures the steady state that actually matters — a tall pile compressing
 * under its own weight — rather than a single body in free fall.
 *
 * [bodyWidth] is the piece size in well units (a classic tetromino occupies
 * about 2). Lattice resolution then sets particle spacing, and particle radius
 * is half the spacing so neighbours within a body just touch.
 *
 * Placement must leave at least one particle *diameter* of clear air between
 * adjacent bodies. Anything tighter starts the scene interpenetrating, and the
 * contact solver converts that overlap into launch energy — which reads as a
 * stability problem in the results when it is really a setup bug.
 */
fun buildStack(
    bodies: Int,
    lattice: Int,
    bodyWidth: Float,
    massPerParticle: Float,
    distanceCompliance: Float,
    areaCompliance: Float,
): World {
    val perBody = lattice * lattice
    val distPerBody = 2 * lattice * (lattice - 1) + 2 * (lattice - 1) * (lattice - 1)
    val areaPerBody = 2 * (lattice - 1) * (lattice - 1)
    val spacing = bodyWidth / (lattice - 1)
    val radius = spacing * 0.5f

    val w = World(
        maxParticles = bodies * perBody + 16,
        maxDistance = bodies * distPerBody + 16,
        maxArea = bodies * areaPerBody + 16,
        wellMinX = Well.MIN_X,
        wellMaxX = Well.MAX_X,
        floorY = Well.FLOOR_Y,
        particleRadius = radius,
    )

    // Horizontal pitch needs one particle diameter of clear air. Vertical pitch
    // is deliberately generous: bodies are dropped from a loose lattice and
    // gravity packs them, which produces a realistically interlocked pile rather
    // than neat columns. Alternate rows are staggered so they key into each other
    // instead of forming independent towers.
    val pitchX = bodyWidth + spacing * 1.2f
    val pitchY = bodyWidth * 1.6f
    val firstX = Well.MIN_X + radius
    val lastX = Well.MAX_X - radius - bodyWidth
    val stagger = pitchX * 0.5f
    // Columns must fit *including* the stagger. Clamping an overflowing column
    // back inside the well is the tempting fix and it is wrong: it silently
    // overlaps two bodies at t=0, and the contact solver turns that overlap into
    // launch energy that reads as solver instability for the whole run.
    val perRow = max(1, 1 + ((lastX - firstX - stagger) / pitchX).toInt())
    for (b in 0 until bodies) {
        val col = b % perRow
        val row = b / perRow
        val x = firstX + col * pitchX + (if (row % 2 == 1) stagger else 0f)
        val y = Well.FLOOR_Y + radius + row * pitchY
        w.addLatticeBody(x, y, lattice, spacing, massPerParticle, b, distanceCompliance, areaCompliance)
    }
    return w
}

/** Height of the highest particle — how tall the settled pile actually is. */
fun World.stackHeight(): Float {
    var top = 0f
    for (i in 0 until particleCount) if (py[i] > top) top = py[i]
    return top
}

/**
 * Coverage bands via a coarse occupancy bitmap.
 *
 * The band question is "is this horizontal slice mostly material, with no
 * meaningful hole in it". Summing particle disk areas answers a different
 * question badly: overlapping particles double-count and a ring of particles
 * around a void reads as full. Stamping each particle into a coarse grid and
 * counting set cells measures *span*, which is what the player sees, and costs
 * O(particles) with a tiny constant.
 *
 * Resolution is deliberately coarse: cells are about one particle radius, so
 * each particle stamps a handful of cells.
 */
class CoverageBands(
    val bandCount: Int,
    val colsPerBand: Int,
    val rowsPerBand: Int,
    val minX: Float,
    val maxX: Float,
    val floorY: Float,
    val bandHeight: Float,
) {
    private val cellsPerBand = colsPerBand * rowsPerBand
    private val occupancy = ByteArray(bandCount * cellsPerBand)
    val fill = FloatArray(bandCount)

    private val cellW = (maxX - minX) / colsPerBand
    private val cellH = bandHeight / rowsPerBand

    fun compute(w: World, radius: Float) {
        java.util.Arrays.fill(occupancy, 0)
        val n = w.particleCount
        val invCellW = 1f / cellW
        val invCellH = 1f / cellH
        for (i in 0 until n) {
            val x = w.px[i]; val y = w.py[i]
            val c0 = ((x - radius - minX) * invCellW).toInt()
            val c1 = ((x + radius - minX) * invCellW).toInt()
            val r0 = ((y - radius - floorY) * invCellH).toInt()
            val r1 = ((y + radius - floorY) * invCellH).toInt()
            var r = max(0, r0)
            val rEnd = min(bandCount * rowsPerBand - 1, r1)
            val cStart = max(0, c0)
            val cEnd = min(colsPerBand - 1, c1)
            while (r <= rEnd) {
                val band = r / rowsPerBand
                val rowInBand = r - band * rowsPerBand
                val base = band * cellsPerBand + rowInBand * colsPerBand
                var c = cStart
                while (c <= cEnd) { occupancy[base + c] = 1; c++ }
                r++
            }
        }
        for (b in 0 until bandCount) {
            var set = 0
            val base = b * cellsPerBand
            for (k in 0 until cellsPerBand) set += occupancy[base + k].toInt()
            fill[b] = set.toFloat() / cellsPerBand
        }
    }

    /** Bands at or above [threshold], for the clear rule. */
    fun fullBands(threshold: Float, out: IntArray): Int {
        var n = 0
        for (b in 0 until bandCount) if (fill[b] >= threshold) out[n++] = b
        return n
    }
}
