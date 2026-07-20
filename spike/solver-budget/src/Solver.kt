// SPIKE CODE — NOT PRODUCTION.
//
// Purpose: measure the cost and the stability of an XPBD soft-body solver core
// so the architecture can be designed against a real number instead of a guess.
//
// This file is deliberately a single flat unit with public mutable arrays. It is
// a measuring instrument, not a design. Do not lift it into the product; lift the
// *numbers* and the kernel shapes.
//
// Layout: structure-of-arrays (SoA), primitive FloatArray/IntArray only, no
// allocation in the stepping loop. AoS.kt mirrors it for the layout comparison.

package spike

import kotlin.math.abs
import kotlin.math.sqrt

const val GRAVITY = -30.0f

/**
 * 2D XPBD soft-body world.
 *
 * Integration follows Macklin et al. "Small Steps in Physics Simulation" (2019):
 * many substeps with a single constraint iteration each, rather than one step
 * with many iterations. Substep count is the dominant quality/cost dial.
 */
class World(
    maxParticles: Int,
    maxDistance: Int,
    maxArea: Int,
    val wellMinX: Float,
    val wellMaxX: Float,
    val floorY: Float,
    val particleRadius: Float,
) {
    // ---- particles (SoA) ----
    val px = FloatArray(maxParticles)
    val py = FloatArray(maxParticles)
    val prevX = FloatArray(maxParticles)
    val prevY = FloatArray(maxParticles)
    val vx = FloatArray(maxParticles)
    val vy = FloatArray(maxParticles)
    val invMass = FloatArray(maxParticles)
    val bodyId = IntArray(maxParticles)
    var particleCount = 0

    // ---- distance constraints (SoA) ----
    val dcA = IntArray(maxDistance)
    val dcB = IntArray(maxDistance)
    val dcRest = FloatArray(maxDistance)
    val dcCompliance = FloatArray(maxDistance)
    val dcLambda = FloatArray(maxDistance)
    var distanceCount = 0

    // ---- area constraints (SoA) ----
    val acA = IntArray(maxArea)
    val acB = IntArray(maxArea)
    val acC = IntArray(maxArea)
    val acRest = FloatArray(maxArea)
    val acCompliance = FloatArray(maxArea)
    val acLambda = FloatArray(maxArea)
    var areaCount = 0

    // ---- uniform-grid broadphase (counting sort, rebuilt per frame) ----
    private val cellSize = particleRadius * 2.0f
    private val invCell = 1.0f / cellSize
    private var gridW = 0
    private var gridH = 0
    private var originX = 0.0f
    private var originY = 0.0f
    private var cellStart = IntArray(0)
    private var cellCursor = IntArray(0)
    private var cellItems = IntArray(maxParticles)

    var frictionCoefficient = 0.55f

    /**
     * Per-substep velocity damping. Position-based dynamics conserves energy well
     * enough that an undamped pile rings indefinitely; a small drag term is what
     * every soft-body game uses to make a stack actually come to rest. It is a
     * feel parameter as much as a stability one.
     */
    var linearDamping = 0.005f

    // Instrumentation. Not part of any hot path decision, just reporting.
    var lastContactPairs = 0
    var selfCollision = false

    fun addParticle(x: Float, y: Float, mass: Float, body: Int): Int {
        val i = particleCount++
        px[i] = x; py[i] = y
        prevX[i] = x; prevY[i] = y
        vx[i] = 0f; vy[i] = 0f
        invMass[i] = if (mass <= 0f) 0f else 1f / mass
        bodyId[i] = body
        return i
    }

    fun addDistance(a: Int, b: Int, compliance: Float) {
        val i = distanceCount++
        dcA[i] = a; dcB[i] = b
        dcRest[i] = dist(a, b)
        dcCompliance[i] = compliance
        dcLambda[i] = 0f
    }

    fun addArea(a: Int, b: Int, c: Int, compliance: Float) {
        val i = areaCount++
        acA[i] = a; acB[i] = b; acC[i] = c
        acRest[i] = signedArea(a, b, c)
        acCompliance[i] = compliance
        acLambda[i] = 0f
    }

    private fun dist(a: Int, b: Int): Float {
        val dx = px[a] - px[b]; val dy = py[a] - py[b]
        return sqrt(dx * dx + dy * dy)
    }

    private fun signedArea(a: Int, b: Int, c: Int): Float =
        0.5f * ((px[b] - px[a]) * (py[c] - py[a]) - (px[c] - px[a]) * (py[b] - py[a]))

    // ------------------------------------------------------------------
    // Stepping
    // ------------------------------------------------------------------

    /** One rendered frame: broadphase once, then [substeps] XPBD substeps. */
    fun step(dt: Float, substeps: Int) {
        buildGrid()
        val h = dt / substeps
        for (s in 0 until substeps) {
            integrate(h)
            resetLambdas()
            solveDistance(h)
            solveArea(h)
            solveContacts()
            solveBounds()
            updateVelocities(h)
        }
    }

    private fun integrate(h: Float) {
        val n = particleCount
        val g = GRAVITY * h
        for (i in 0 until n) {
            if (invMass[i] == 0f) { prevX[i] = px[i]; prevY[i] = py[i]; continue }
            prevX[i] = px[i]; prevY[i] = py[i]
            vy[i] += g
            px[i] += vx[i] * h
            py[i] += vy[i] * h
        }
    }

    private fun resetLambdas() {
        java.util.Arrays.fill(dcLambda, 0, distanceCount, 0f)
        java.util.Arrays.fill(acLambda, 0, areaCount, 0f)
    }

    private fun solveDistance(h: Float) {
        val n = distanceCount
        val h2 = h * h
        for (k in 0 until n) {
            val a = dcA[k]; val b = dcB[k]
            val wa = invMass[a]; val wb = invMass[b]
            val wsum = wa + wb
            if (wsum == 0f) continue
            val dx = px[a] - px[b]; val dy = py[a] - py[b]
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1e-8f) continue
            val c = d - dcRest[k]
            val alpha = dcCompliance[k] / h2
            val dl = (-c - alpha * dcLambda[k]) / (wsum + alpha)
            dcLambda[k] += dl
            val nx = dx / d; val ny = dy / d
            px[a] += wa * dl * nx; py[a] += wa * dl * ny
            px[b] -= wb * dl * nx; py[b] -= wb * dl * ny
        }
    }

    private fun solveArea(h: Float) {
        val n = areaCount
        val h2 = h * h
        for (k in 0 until n) {
            val a = acA[k]; val b = acB[k]; val c = acC[k]
            val wa = invMass[a]; val wb = invMass[b]; val wc = invMass[c]
            if (wa + wb + wc == 0f) continue
            val xa = px[a]; val ya = py[a]
            val xb = px[b]; val yb = py[b]
            val xc = px[c]; val yc = py[c]
            val area = 0.5f * ((xb - xa) * (yc - ya) - (xc - xa) * (yb - ya))
            val cc = area - acRest[k]
            // gradients of signed area wrt each vertex
            val gxa = 0.5f * (yb - yc); val gya = 0.5f * (xc - xb)
            val gxb = 0.5f * (yc - ya); val gyb = 0.5f * (xa - xc)
            val gxc = 0.5f * (ya - yb); val gyc = 0.5f * (xb - xa)
            val wsum = wa * (gxa * gxa + gya * gya) +
                       wb * (gxb * gxb + gyb * gyb) +
                       wc * (gxc * gxc + gyc * gyc)
            if (wsum < 1e-12f) continue
            val alpha = acCompliance[k] / h2
            val dl = (-cc - alpha * acLambda[k]) / (wsum + alpha)
            acLambda[k] += dl
            px[a] += wa * dl * gxa; py[a] += wa * dl * gya
            px[b] += wb * dl * gxb; py[b] += wb * dl * gyb
            px[c] += wc * dl * gxc; py[c] += wc * dl * gyc
        }
    }

    // ---- broadphase ----

    private fun buildGrid() {
        val n = particleCount
        if (n == 0) return
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = px[i]; val y = py[i]
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        originX = minX - cellSize; originY = minY - cellSize
        gridW = (((maxX - originX) * invCell).toInt() + 2).coerceAtLeast(1)
        gridH = (((maxY - originY) * invCell).toInt() + 2).coerceAtLeast(1)
        val cells = gridW * gridH
        if (cellStart.size < cells + 1) {
            cellStart = IntArray(cells + 1)
            cellCursor = IntArray(cells + 1)
        }
        java.util.Arrays.fill(cellStart, 0, cells + 1, 0)
        java.util.Arrays.fill(cellCursor, 0, cells, 0)
        // counting sort
        for (i in 0 until n) cellStart[cellIndex(px[i], py[i])]++
        var acc = 0
        for (c in 0 until cells) { val v = cellStart[c]; cellStart[c] = acc; acc += v }
        cellStart[cells] = acc
        for (i in 0 until n) {
            val c = cellIndex(px[i], py[i])
            cellItems[cellStart[c] + cellCursor[c]] = i
            cellCursor[c]++
        }
    }

    private fun cellIndex(x: Float, y: Float): Int {
        var cx = ((x - originX) * invCell).toInt()
        var cy = ((y - originY) * invCell).toInt()
        if (cx < 0) cx = 0; if (cx >= gridW) cx = gridW - 1
        if (cy < 0) cy = 0; if (cy >= gridH) cy = gridH - 1
        return cy * gridW + cx
    }

    /**
     * Non-penetration + Coulomb friction between particles, solved as rigid
     * (zero-compliance) position corrections. Friction is what makes a tall
     * stack stand still instead of creeping; it is not optional polish.
     */
    private fun solveContacts() {
        val n = particleCount
        if (n == 0 || gridW == 0) return
        val diameter = particleRadius * 2.0f
        var pairs = 0
        for (i in 0 until n) {
            val wi = invMass[i]
            val xi = px[i]; val yi = py[i]
            var cx = ((xi - originX) * invCell).toInt()
            var cy = ((yi - originY) * invCell).toInt()
            if (cx < 0) cx = 0; if (cx >= gridW) cx = gridW - 1
            if (cy < 0) cy = 0; if (cy >= gridH) cy = gridH - 1
            val bi = bodyId[i]
            var oy = -1
            while (oy <= 1) {
                val ny2 = cy + oy
                if (ny2 < 0 || ny2 >= gridH) { oy++; continue }
                var ox = -1
                while (ox <= 1) {
                    val nx2 = cx + ox
                    if (nx2 < 0 || nx2 >= gridW) { ox++; continue }
                    val c = ny2 * gridW + nx2
                    var s = cellStart[c]
                    val e = cellStart[c + 1]
                    while (s < e) {
                        val j = cellItems[s]; s++
                        if (j <= i) continue
                        if (!selfCollision && bodyId[j] == bi) continue
                        val wj = invMass[j]
                        val wsum = wi + wj
                        if (wsum == 0f) continue
                        val dx = px[i] - px[j]; val dy = py[i] - py[j]
                        val d2 = dx * dx + dy * dy
                        if (d2 >= diameter * diameter || d2 < 1e-12f) continue
                        pairs++
                        val d = sqrt(d2)
                        val cvio = d - diameter          // negative == overlap
                        val nx3 = dx / d; val ny3 = dy / d
                        val corr = -cvio / wsum
                        px[i] += wi * corr * nx3; py[i] += wi * corr * ny3
                        px[j] -= wj * corr * nx3; py[j] -= wj * corr * ny3

                        // friction: damp relative tangential motion this substep
                        val rdx = (px[i] - prevX[i]) - (px[j] - prevX[j])
                        val rdy = (py[i] - prevY[i]) - (py[j] - prevY[j])
                        val tdot = rdx * nx3 + rdy * ny3
                        var tx = rdx - tdot * nx3
                        var ty = rdy - tdot * ny3
                        val tlen = sqrt(tx * tx + ty * ty)
                        if (tlen > 1e-8f) {
                            val maxSlide = frictionCoefficient * abs(cvio)
                            val scale = if (tlen > maxSlide) maxSlide / tlen else 1.0f
                            tx *= scale; ty *= scale
                            px[i] -= wi / wsum * tx; py[i] -= wi / wsum * ty
                            px[j] += wj / wsum * tx; py[j] += wj / wsum * ty
                        }
                    }
                    ox++
                }
                oy++
            }
        }
        lastContactPairs = pairs
    }

    private fun solveBounds() {
        val n = particleCount
        val r = particleRadius
        val lo = wellMinX + r; val hi = wellMaxX - r; val fl = floorY + r
        for (i in 0 until n) {
            if (invMass[i] == 0f) continue
            if (px[i] < lo) px[i] = lo
            if (px[i] > hi) px[i] = hi
            if (py[i] < fl) {
                val pen = fl - py[i]          // depth BEFORE the correction
                py[i] = fl
                // ground friction, same static-slide clamp as particle contacts
                val tx = px[i] - prevX[i]
                val maxSlide = frictionCoefficient * pen
                if (abs(tx) > maxSlide) px[i] -= tx - (if (tx > 0) maxSlide else -maxSlide)
                else px[i] -= tx
            }
        }
    }

    private fun updateVelocities(h: Float) {
        val n = particleCount
        val inv = 1f / h
        val damp = 1f - linearDamping
        for (i in 0 until n) {
            vx[i] = (px[i] - prevX[i]) * inv * damp
            vy[i] = (py[i] - prevY[i]) * inv * damp
        }
    }

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    /** Total kinetic energy. Near zero for a genuinely settled stack; a
     *  non-zero floor is solver jitter and will read on screen as a bug. */
    fun kineticEnergy(): Double {
        var e = 0.0
        for (i in 0 until particleCount) {
            if (invMass[i] == 0f) continue
            val m = 1f / invMass[i]
            e += 0.5 * m * (vx[i].toDouble() * vx[i] + vy[i].toDouble() * vy[i])
        }
        return e
    }

    fun maxSpeed(): Float {
        var m = 0f
        for (i in 0 until particleCount) {
            val s = sqrt(vx[i] * vx[i] + vy[i] * vy[i])
            if (s > m) m = s
        }
        return m
    }

    /** Cheap order-insensitive state hash, for the determinism check. */
    fun stateHash(): Long {
        var h = 1125899906842597L
        for (i in 0 until particleCount) {
            h = h * 31 + px[i].toRawBits()
            h = h * 31 + py[i].toRawBits()
        }
        return h
    }
}
