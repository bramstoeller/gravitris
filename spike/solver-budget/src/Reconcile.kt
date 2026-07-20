// SPIKE CODE — NOT PRODUCTION.
//
// Reconciles ADR 0003's "substep floor is 8" against the backend engineer's
// independent measurement, which puts the floor between 2 and 4 with no trend
// above 4 (handoff 0006).
//
// Hypothesis under test: the original spike's floor is an artefact of its SCENE,
// not a property of the solver. After the seeding fix, bodies ended up placed 3
// per row and ~20 rows high — a column starting ~57 units in the air, so bodies
// arrived at ~58 units/s. The real well is 20 tall. If the substep requirement
// tracks peak IMPACT VELOCITY rather than stack depth, then lowering the drop
// height should move the apparent floor down to ~4 and reproduce their result.
//
// Prediction if the hypothesis holds:
//   - peak speed during settling scales with drop height
//   - at low drop height, 4 substeps settles as well as 8 (their finding)
//   - at high drop height, low substep counts leave a pile still ringing
//   - the original 1200-frame window was measuring settling TIME, not stability

package spike

import kotlin.math.sqrt

private fun buildWell(
    bodies: Int,
    lattice: Int,
    bodyWidth: Float,
    wellWidth: Float,
    dropPitchMultiplier: Float,
    massPerParticle: Float,
    compliance: Float,
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
        wellMinX = 0f,
        wellMaxX = wellWidth,
        floorY = 0f,
        particleRadius = radius,
    )

    val pitchX = bodyWidth + spacing * 1.2f
    val pitchY = bodyWidth * dropPitchMultiplier
    val firstX = radius
    val lastX = wellWidth - radius - bodyWidth
    val stagger = pitchX * 0.5f
    val perRow = maxOf(1, 1 + ((lastX - firstX - stagger) / pitchX).toInt())
    for (b in 0 until bodies) {
        val col = b % perRow
        val row = b / perRow
        val x = firstX + col * pitchX + (if (row % 2 == 1) stagger else 0f)
        val y = radius + row * pitchY
        w.addLatticeBody(x, y, lattice, spacing, massPerParticle, b, compliance, compliance)
    }
    return w
}

/** Settles while tracking the peak speed any particle reached — the impact energy. */
private fun settleTracked(w: World, substeps: Int, frames: Int): Float {
    var peak = 0f
    for (f in 0 until frames) {
        w.step(DT, substeps)
        val s = w.maxSpeed()
        if (s > peak) peak = s
        // A tunnelled body leaves the well and drives the grid extents to
        // absurd values, which hangs the broadphase rather than failing. Bail.
        if (!s.isFinite() || s > 1e5f) return Float.POSITIVE_INFINITY
    }
    return peak
}

private fun escaped(w: World): Boolean {
    for (i in 0 until w.particleCount) {
        if (!w.px[i].isFinite() || !w.py[i].isFinite()) return true
        if (w.py[i] < -5f) return true          // below the floor == missed contact
    }
    return false
}

private fun initialTopY(w: World): Float = w.stackHeight()

fun main() {
    println("# Reconciling ADR 0003's substep floor with the backend engineer's measurement")
    println("# HOST numbers (x86-64, HotSpot ${System.getProperty("java.version")}), single-threaded.")
    println()
    println("Hypothesis: the spike's floor of 8 is a property of its SCENE (a column")
    println("seeded ~57 units in the air), not of the solver. Lowering the drop height")
    println("should move the floor to ~4 and reproduce the backend engineer's result.")
    println()

    // ---------------------------------------------------------------
    println("## 1. Substep floor vs drop height")
    println()
    println("Same solver, same body count, same compliance. Only the initial vertical")
    println("pitch changes, which changes how far bodies fall before landing.")
    println("'peak speed' is the fastest any particle moved during settling.")
    println()
    println("| drop pitch | initial top | peak speed | substeps | KE @900 | KE @1800 | KE @3600 | verdict |")
    println("| --- | --- | --- | --- | --- | --- | --- | --- |")

    for ((mult, label) in listOf(
        1.05f to "1.05 (nearly touching)",
        1.6f to "1.60 (the spike's original)",
        2.5f to "2.50 (extreme)",
    )) {
        for (substeps in listOf(2, 4, 6, 8, 12, 16)) {
            val w = buildWell(
                bodies = 60, lattice = 4, bodyWidth = 1.8f, wellWidth = 10f,
                dropPitchMultiplier = mult, massPerParticle = 1f, compliance = MEDIUM,
            )
            val top0 = initialTopY(w)
            val peak = settleTracked(w, substeps, 900)
            val ke900 = w.kineticEnergy()
            settleTracked(w, substeps, 900)
            val ke1800 = w.kineticEnergy()
            settleTracked(w, substeps, 1800)
            val ke3600 = w.kineticEnergy()
            val verdict = when {
                !ke3600.isFinite() || ke3600 > 1e4 -> "EXPLODED"
                ke900 > 1.0 && ke3600 < ke900 * 0.1 -> "still settling @900"
                ke3600 > 1.0 -> "ringing"
                else -> "settled"
            }
            println("| %s | %.1f | %.1f | %d | %.4f | %.4f | %.4f | %s |".format(
                if (substeps == 2) label else "", if (substeps == 2) top0 else Float.NaN,
                peak, substeps, ke900, ke1800, ke3600, verdict))
        }
    }
    println()

    // ---------------------------------------------------------------
    println("## 2. The backend engineer's two scenes, reproduced in the spike solver")
    println()
    println("If the solvers agree, these should match handoff 0006's table shape:")
    println("catastrophic at 2 in the tall tower, flat noise from 4 upward in both.")
    println()
    println("| scene | substeps | peak speed | KE @900 | verdict |")
    println("| --- | --- | --- | --- | --- |")

    val scenes = listOf(
        Triple("wide well 10 wide, 24 bodies", 24, 10f),
        Triple("tall tower 5 wide, 40 bodies", 40, 5f),
    )
    for ((name, bodies, width) in scenes) {
        for (substeps in listOf(2, 4, 6, 8, 12, 16)) {
            val w = buildWell(
                bodies = bodies, lattice = 4, bodyWidth = 1.8f, wellWidth = width,
                dropPitchMultiplier = 1.05f, massPerParticle = 1f, compliance = MEDIUM,
            )
            val peak = settleTracked(w, substeps, 900)
            val ke = w.kineticEnergy()
            val verdict = when {
                !ke.isFinite() || ke > 1e4 -> "EXPLODED"
                ke > 1.0 -> "ringing"
                else -> "settled"
            }
            println("| %s | %d | %.1f | %.4f | %s |".format(
                if (substeps == 2) name else "", substeps, peak, ke, verdict))
        }
    }
    println()

    // ---------------------------------------------------------------
    println("## 3. Is the requirement a velocity condition?")
    println()
    println("A hard drop is the fastest thing in the real game, so if the floor tracks")
    println("velocity it is the hard drop that sets it — not the depth of the pile.")
    println("Below: one body driven downward at a chosen speed into a settled pile.")
    println("particle diameter = 0.6; 'travel/substep' = speed / (60 x substeps).")
    println()
    println("| impact speed | substeps | travel/substep | penetrated? | KE after |")
    println("| --- | --- | --- | --- | --- |")

    for (speed in listOf(20f, 60f, 120f, 240f)) {
        for (substeps in listOf(2, 4, 8, 16)) {
            val w = buildWell(
                bodies = 12, lattice = 4, bodyWidth = 1.8f, wellWidth = 10f,
                dropPitchMultiplier = 1.05f, massPerParticle = 1f, compliance = MEDIUM,
            )
            settleTracked(w, 8, 600)                    // settle the target pile first
            val floorBefore = w.stackHeight()
            // launch every particle of the topmost body downward
            var topBody = -1
            var topY = -1f
            for (i in 0 until w.particleCount) if (w.py[i] > topY) { topY = w.py[i]; topBody = w.bodyId[i] }
            for (i in 0 until w.particleCount) if (w.bodyId[i] == topBody) w.vy[i] = -speed
            val peak = settleTracked(w, substeps, 600)
            val ke = w.kineticEnergy()
            val travel = speed / (60f * substeps)
            val penetrated = !peak.isFinite() || escaped(w) ||
                !ke.isFinite() || ke > 1e4 || w.stackHeight() < floorBefore - 1.5f
            println("| %.0f | %d | %.3f | %s | %s |".format(
                speed, substeps, travel, if (penetrated) "**YES**" else "no",
                if (ke.isFinite() && ke < 1e9) "%.4f".format(ke) else "diverged"))
        }
    }
    println()
    println("Contact is missed when travel/substep approaches the particle diameter (0.6).")
}
