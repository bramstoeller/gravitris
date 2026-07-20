// SPIKE CODE — NOT PRODUCTION.
//
// Measures, on this host:
//   1. solver cost vs particle / constraint / substep count
//   2. the stack-stability floor (residual jitter) vs substep count
//   3. SoA vs AoS on identical work
//   4. coverage-band cost
//   5. determinism (bit-identical replay)
//   6. a fixed reference configuration, so the same number can be taken on a
//      real device later and turned into a true derating ratio
//
// Everything printed here is a HOST number on x86-64 HotSpot. It is NOT an
// on-device number. See docs/adr/0001 for how it is derated and what that costs
// in confidence.

package spike

import kotlin.math.sqrt

const val DT = 1f / 60f

// Compliance (XPBD, m^2/N-ish in our unit system). Lower = stiffer.
const val FIRM = 1e-8f
const val MEDIUM = 1e-6f
const val SPONGY = 1e-5f

private fun percentile(sorted: LongArray, p: Double): Double =
    sorted[((sorted.size - 1) * p).toInt()] / 1_000_000.0

private class Timing(val p50: Double, val p95: Double, val p99: Double, val mean: Double)

private fun timeFrames(frames: Int, block: () -> Unit): Timing {
    val samples = LongArray(frames)
    for (f in 0 until frames) {
        val t0 = System.nanoTime()
        block()
        samples[f] = System.nanoTime() - t0
    }
    val sorted = samples.clone(); sorted.sort()
    var sum = 0.0
    for (s in samples) sum += s
    return Timing(percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99), sum / frames / 1_000_000.0)
}

private fun settle(w: World, substeps: Int, frames: Int) {
    for (f in 0 until frames) w.step(DT, substeps)
}

private fun row(vararg cols: Any) = println(cols.joinToString(" | "))

// ---------------------------------------------------------------------------

fun main() {
    println("# Solver budget spike — HOST measurements (x86-64, HotSpot ${System.getProperty("java.version")})")
    println("# NOT on-device numbers. Derating is documented in docs/adr/0001.")
    println("# cpu: ${Runtime.getRuntime().availableProcessors()} logical cores, single-threaded solver")
    println()

    experimentScaling()
    experimentStability()
    experimentLayout()
    experimentAllocation()
    experimentCoverage()
    experimentDeterminism()
    experimentReference()
}

/** A piece is about 2 well-units across, like a classic tetromino. */
const val PIECE_WIDTH = 1.8f

// --- 1. cost scaling -------------------------------------------------------

private fun experimentScaling() {
    println("## 1. Cost vs scene size and substep count")
    println("Scene: full well, lattice soft bodies pre-stacked, settled before timing.")
    println("compliance=MEDIUM, self-collision off (bodies are held together by their own constraints)")
    println()
    println("Well is 10 wide x 20 tall; 'height' is the settled pile top, so a value")
    println("near 20 means the well is genuinely full and the game would be ending.")
    println()
    row("bodies", "lattice", "particles", "dist", "area", "contacts", "height", "substeps", "p50 ms", "p95 ms", "p99 ms")
    row("---", "---", "---", "---", "---", "---", "---", "---", "---", "---", "---")

    val configs = listOf(
        Triple(20, 4, 8), Triple(40, 4, 8), Triple(60, 4, 8), Triple(80, 4, 8),
        Triple(60, 3, 8), Triple(60, 5, 8), Triple(60, 6, 8), Triple(60, 8, 8),
        Triple(60, 4, 2), Triple(60, 4, 4), Triple(60, 4, 12), Triple(60, 4, 16),
        Triple(60, 5, 4), Triple(60, 5, 12), Triple(60, 6, 12),
    )
    for ((bodies, lattice, substeps) in configs) {
        val w = buildStack(bodies, lattice, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
        settle(w, substeps, 900)                       // settle + JIT warmup
        val t = timeFrames(1200) { w.step(DT, substeps) }
        row(bodies, lattice, w.particleCount, w.distanceCount, w.areaCount,
            w.lastContactPairs, "%.1f".format(w.stackHeight()), substeps,
            "%.3f".format(t.p50), "%.3f".format(t.p95), "%.3f".format(t.p99))
    }
    println()
}

// --- 2. stack stability ----------------------------------------------------

private fun experimentStability() {
    println("## 2. Stack stability: residual jitter after settling")
    println("A settled stack should be still. A non-zero floor is solver jitter and")
    println("will read on screen as a bug. 60 bodies, lattice 4, mass 1/particle.")
    println()
    row("substeps", "compliance", "KE after 600f", "KE after 1200f", "max speed", "stack height", "verdict")
    row("---", "---", "---", "---", "---", "---", "---")

    for (compliance in listOf(FIRM to "FIRM", MEDIUM to "MEDIUM", SPONGY to "SPONGY")) {
        for (substeps in listOf(2, 4, 6, 8, 12, 16)) {
            val w = buildStack(60, 4, PIECE_WIDTH, 1.0f, compliance.first, compliance.first)
            settle(w, substeps, 600)
            val ke1 = w.kineticEnergy()
            settle(w, substeps, 600)
            val ke2 = w.kineticEnergy()
            val ms = w.maxSpeed()
            val top = w.stackHeight()
            val exploded = !top.isFinite() || top > 100f
            val verdict = when {
                exploded -> "DIVERGED"
                ms > 1.0f -> "jittering"
                ms > 0.2f -> "creeping"
                else -> "settled"
            }
            row(substeps, compliance.second, "%.4f".format(ke1), "%.4f".format(ke2),
                "%.4f".format(ms), "%.2f".format(top), verdict)
        }
    }
    println()

    // The brief makes pieces heavier as the game progresses. Heavier mass against
    // the same compliance means deeper compression and a harder contact problem,
    // so the late game is the stability case that matters, not the early game.
    println("### 2b. Stability vs piece mass (compliance MEDIUM, the late-game case)")
    println()
    row("mass/particle", "substeps", "KE settled", "max speed", "stack height", "verdict")
    row("---", "---", "---", "---", "---", "---")
    for (mass in listOf(1.0f, 2.0f, 4.0f, 8.0f)) {
        for (substeps in listOf(4, 8, 12, 16)) {
            val w = buildStack(60, 4, PIECE_WIDTH, mass, MEDIUM, MEDIUM)
            settle(w, substeps, 1200)
            val ke = w.kineticEnergy()
            val ms = w.maxSpeed()
            val top = w.stackHeight()
            val verdict = when {
                !top.isFinite() || top > 100f -> "DIVERGED"
                ms > 1.0f -> "jittering"
                ms > 0.2f -> "creeping"
                else -> "settled"
            }
            row(mass, substeps, "%.4f".format(ke), "%.4f".format(ms), "%.2f".format(top), verdict)
        }
    }
    println()
}

// --- 3. layout -------------------------------------------------------------

private fun experimentLayout() {
    println("## 3. SoA vs AoS, identical constraint work (no contacts, no broadphase)")
    println("Scaled well past the game's own budget to find where layout starts to")
    println("matter. Working set is ~32 bytes/particle + ~20 bytes/constraint.")
    println()
    row("particles", "constraints", "~working set", "SoA p50 ms", "AoS p50 ms", "AoS/SoA")
    row("---", "---", "---", "---", "---", "---")

    for ((bodies, lattice) in listOf(
        20 to 4, 60 to 4, 60 to 6, 240 to 6, 960 to 6, 3840 to 6,
    )) {
        val w = buildStack(bodies, lattice, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
        // Settling is irrelevant here — this measures kernel throughput on a
        // valid scene, and the huge scenes are far too slow to settle.
        if (w.particleCount < 5000) settle(w, 8, 200)
        val aos = mirrorToAoS(w)

        // SoA: constraint kernels only, matched to the AoS step
        val soaOnly = SoAConstraintsOnly(w)
        repeat(400) { soaOnly.step(DT, 8) }
        repeat(400) { aos.step(DT, 8) }

        val frames = if (w.particleCount > 20000) 300 else 1500
        val ts = timeFrames(frames) { soaOnly.step(DT, 8) }
        val ta = timeFrames(frames) { aos.step(DT, 8) }
        val constraints = w.distanceCount + w.areaCount
        val bytes = w.particleCount * 32L + constraints * 20L
        row(w.particleCount, constraints, "%d KB".format(bytes / 1024),
            "%.3f".format(ts.p50), "%.3f".format(ta.p50),
            "%.2fx".format(ta.p50 / ts.p50))
    }
    println()
}

// --- 3b. allocation --------------------------------------------------------

/**
 * On Android the layout argument that actually pays is allocation, not cache:
 * a solver that allocates per frame hands the GC a steady stream of garbage and
 * buys periodic pauses, which on a 16.67ms budget are dropped frames. Steady
 * state must allocate zero bytes.
 */
private fun experimentAllocation() {
    println("## 3b. Steady-state allocation per frame (must be zero)")
    println()
    val tmb = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
    row("scene", "bytes/frame over 1000 frames")
    row("---", "---")

    for ((bodies, lattice) in listOf(60 to 4, 60 to 6)) {
        val w = buildStack(bodies, lattice, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
        settle(w, 8, 600)
        val cb = CoverageBands(20, 40, 4, Well.MIN_X, Well.MAX_X, Well.FLOOR_Y, 1.0f)
        repeat(600) { cb.compute(w, w.particleRadius) }

        val before = tmb.currentThreadAllocatedBytes
        for (f in 0 until 1000) { w.step(DT, 8); cb.compute(w, w.particleRadius) }
        val after = tmb.currentThreadAllocatedBytes
        row("${w.particleCount}p solver+coverage", "${(after - before) / 1000} B/frame")
    }
    println()
}

/** SoA stepping restricted to the kernels the AoS mirror implements. */
private class SoAConstraintsOnly(val w: World) {
    fun step(dt: Float, substeps: Int) {
        val h = dt / substeps
        for (s in 0 until substeps) {
            val g = GRAVITY * h
            for (i in 0 until w.particleCount) {
                w.prevX[i] = w.px[i]; w.prevY[i] = w.py[i]
                if (w.invMass[i] == 0f) continue
                w.vy[i] += g
                w.px[i] += w.vx[i] * h
                w.py[i] += w.vy[i] * h
            }
            java.util.Arrays.fill(w.dcLambda, 0, w.distanceCount, 0f)
            java.util.Arrays.fill(w.acLambda, 0, w.areaCount, 0f)
            solveDistance(h)
            solveArea(h)
            val inv = 1f / h
            for (i in 0 until w.particleCount) {
                w.vx[i] = (w.px[i] - w.prevX[i]) * inv
                w.vy[i] = (w.py[i] - w.prevY[i]) * inv
            }
        }
    }

    private fun solveDistance(h: Float) {
        val h2 = h * h
        for (k in 0 until w.distanceCount) {
            val a = w.dcA[k]; val b = w.dcB[k]
            val wa = w.invMass[a]; val wb = w.invMass[b]
            val wsum = wa + wb
            if (wsum == 0f) continue
            val dx = w.px[a] - w.px[b]; val dy = w.py[a] - w.py[b]
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1e-8f) continue
            val c = d - w.dcRest[k]
            val alpha = w.dcCompliance[k] / h2
            val dl = (-c - alpha * w.dcLambda[k]) / (wsum + alpha)
            w.dcLambda[k] += dl
            val nx = dx / d; val ny = dy / d
            w.px[a] += wa * dl * nx; w.py[a] += wa * dl * ny
            w.px[b] -= wb * dl * nx; w.py[b] -= wb * dl * ny
        }
    }

    private fun solveArea(h: Float) {
        val h2 = h * h
        for (k in 0 until w.areaCount) {
            val a = w.acA[k]; val b = w.acB[k]; val c = w.acC[k]
            val wa = w.invMass[a]; val wb = w.invMass[b]; val wc = w.invMass[c]
            if (wa + wb + wc == 0f) continue
            val xa = w.px[a]; val ya = w.py[a]
            val xb = w.px[b]; val yb = w.py[b]
            val xc = w.px[c]; val yc = w.py[c]
            val area = 0.5f * ((xb - xa) * (yc - ya) - (xc - xa) * (yb - ya))
            val cc = area - w.acRest[k]
            val gxa = 0.5f * (yb - yc); val gya = 0.5f * (xc - xb)
            val gxb = 0.5f * (yc - ya); val gyb = 0.5f * (xa - xc)
            val gxc = 0.5f * (ya - yb); val gyc = 0.5f * (xb - xa)
            val wsum = wa * (gxa * gxa + gya * gya) + wb * (gxb * gxb + gyb * gyb) + wc * (gxc * gxc + gyc * gyc)
            if (wsum < 1e-12f) continue
            val alpha = w.acCompliance[k] / h2
            val dl = (-cc - alpha * w.acLambda[k]) / (wsum + alpha)
            w.acLambda[k] += dl
            w.px[a] += wa * dl * gxa; w.py[a] += wa * dl * gya
            w.px[b] += wb * dl * gxb; w.py[b] += wb * dl * gyb
            w.px[c] += wc * dl * gxc; w.py[c] += wc * dl * gyc
        }
    }
}

// --- 4. coverage bands -----------------------------------------------------

private fun experimentCoverage() {
    println("## 4. Coverage-band cost (occupancy bitmap), per frame")
    println()
    row("particles", "bands", "cells/band", "p50 ms", "p95 ms", "% of 16.67ms")
    row("---", "---", "---", "---", "---", "---")

    for ((bodies, lattice) in listOf(60 to 4, 60 to 5, 60 to 6)) {
        val w = buildStack(bodies, lattice, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
        settle(w, 8, 300)
        for ((cols, rows) in listOf(40 to 4, 60 to 6)) {
            val cb = CoverageBands(20, cols, rows, Well.MIN_X, Well.MAX_X, Well.FLOOR_Y, 1.0f)
            repeat(2000) { cb.compute(w, w.particleRadius) }
            val t = timeFrames(4000) { cb.compute(w, w.particleRadius) }
            row(w.particleCount, 20, cols * rows,
                "%.4f".format(t.p50), "%.4f".format(t.p95),
                "%.2f%%".format(100.0 * t.p50 / 16.67))
        }
    }

    // sanity: what does a settled stack actually read as?
    val w = buildStack(60, 4, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
    settle(w, 8, 800)
    val cb = CoverageBands(20, 40, 4, Well.MIN_X, Well.MAX_X, Well.FLOOR_Y, 1.0f)
    cb.compute(w, w.particleRadius)
    println()
    println("Settled-stack band fill (band 0 = floor):")
    for (b in 0 until 12) println("  band %2d: %5.1f%%".format(b, cb.fill[b] * 100))
    println()
}

// --- 5. determinism --------------------------------------------------------

private fun experimentDeterminism() {
    println("## 5. Determinism")
    val a = buildStack(40, 4, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
    val b = buildStack(40, 4, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
    settle(a, 8, 900)
    settle(b, 8, 900)
    println("  same binary, same seed, 900 frames, 8 substeps: hashes ${if (a.stateHash() == b.stateHash()) "MATCH (bit-identical)" else "DIFFER"}")

    val c = buildStack(40, 4, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
    settle(c, 12, 900)
    println("  same scene at 12 substeps instead of 8: hashes ${if (a.stateHash() == c.stateHash()) "MATCH" else "DIFFER (expected — substep count is part of the simulation contract)"}")

    // frame-splitting: does 900 frames == 450 frames run twice?
    val d = buildStack(40, 4, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
    settle(d, 8, 450); settle(d, 8, 450)
    println("  450+450 frames vs 900 frames: hashes ${if (a.stateHash() == d.stateHash()) "MATCH (stepping is stateless across calls)" else "DIFFER"}")
    println()
}

// --- 6. reference configuration -------------------------------------------

private fun experimentReference() {
    println("## 6. REFERENCE CONFIGURATION")
    println("Run this exact configuration on a target device to obtain the true")
    println("host->device derating ratio. Everything else scales from it.")
    println()
    val w = buildStack(60, 4, PIECE_WIDTH, 1.0f, MEDIUM, MEDIUM)
    settle(w, 8, 600)
    val t = timeFrames(3000) { w.step(DT, 8) }
    println("  config:     60 bodies, lattice 4x4, 8 substeps, compliance 1e-6, mass 1.0")
    println("  particles:  ${w.particleCount}")
    println("  distance:   ${w.distanceCount}")
    println("  area:       ${w.areaCount}")
    println("  contacts:   ${w.lastContactPairs} pairs/frame")
    println("  HOST p50:   %.3f ms/frame".format(t.p50))
    println("  HOST p95:   %.3f ms/frame".format(t.p95))
    println("  HOST mean:  %.3f ms/frame".format(t.mean))
    println()
    println("  DEVICE p50: (unmeasured — see blockers.md)")
}
