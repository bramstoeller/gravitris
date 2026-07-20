// SPIKE CODE — NOT PRODUCTION.
//
// Array-of-structures mirror of the constraint kernels, to measure what the SoA
// layout is actually worth. This is the shape a naive implementation reaches for
// first: an array of particle objects, an array of constraint objects.
//
// It mirrors integrate + distance + area + velocity update. Contacts are excluded
// because the broadphase is a separate data-structure question; the constraint
// kernels are where the layout choice bites.

package spike

import kotlin.math.sqrt

class PParticle(var x: Float, var y: Float, var invMass: Float) {
    var prevX = x; var prevY = y
    var vx = 0f; var vy = 0f
}

class PDistance(val a: PParticle, val b: PParticle, val rest: Float, val compliance: Float) {
    var lambda = 0f
}

class PArea(
    val a: PParticle, val b: PParticle, val c: PParticle,
    val rest: Float, val compliance: Float,
) {
    var lambda = 0f
}

class AoSWorld(val particles: Array<PParticle>, val dist: Array<PDistance>, val area: Array<PArea>) {

    fun step(dt: Float, substeps: Int) {
        val h = dt / substeps
        for (s in 0 until substeps) {
            integrate(h)
            for (d in dist) d.lambda = 0f
            for (a in area) a.lambda = 0f
            solveDistance(h)
            solveArea(h)
            updateVelocities(h)
        }
    }

    private fun integrate(h: Float) {
        val g = GRAVITY * h
        for (p in particles) {
            p.prevX = p.x; p.prevY = p.y
            if (p.invMass == 0f) continue
            p.vy += g
            p.x += p.vx * h
            p.y += p.vy * h
        }
    }

    private fun solveDistance(h: Float) {
        val h2 = h * h
        for (k in dist) {
            val a = k.a; val b = k.b
            val wsum = a.invMass + b.invMass
            if (wsum == 0f) continue
            val dx = a.x - b.x; val dy = a.y - b.y
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1e-8f) continue
            val c = d - k.rest
            val alpha = k.compliance / h2
            val dl = (-c - alpha * k.lambda) / (wsum + alpha)
            k.lambda += dl
            val nx = dx / d; val ny = dy / d
            a.x += a.invMass * dl * nx; a.y += a.invMass * dl * ny
            b.x -= b.invMass * dl * nx; b.y -= b.invMass * dl * ny
        }
    }

    private fun solveArea(h: Float) {
        val h2 = h * h
        for (k in area) {
            val a = k.a; val b = k.b; val c = k.c
            if (a.invMass + b.invMass + c.invMass == 0f) continue
            val ar = 0.5f * ((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y))
            val cc = ar - k.rest
            val gxa = 0.5f * (b.y - c.y); val gya = 0.5f * (c.x - b.x)
            val gxb = 0.5f * (c.y - a.y); val gyb = 0.5f * (a.x - c.x)
            val gxc = 0.5f * (a.y - b.y); val gyc = 0.5f * (b.x - a.x)
            val wsum = a.invMass * (gxa * gxa + gya * gya) +
                       b.invMass * (gxb * gxb + gyb * gyb) +
                       c.invMass * (gxc * gxc + gyc * gyc)
            if (wsum < 1e-12f) continue
            val alpha = k.compliance / h2
            val dl = (-cc - alpha * k.lambda) / (wsum + alpha)
            k.lambda += dl
            a.x += a.invMass * dl * gxa; a.y += a.invMass * dl * gya
            b.x += b.invMass * dl * gxb; b.y += b.invMass * dl * gyb
            c.x += c.invMass * dl * gxc; c.y += c.invMass * dl * gyc
        }
    }

    private fun updateVelocities(h: Float) {
        val inv = 1f / h
        for (p in particles) {
            p.vx = (p.x - p.prevX) * inv
            p.vy = (p.y - p.prevY) * inv
        }
    }
}

/**
 * Builds an AoS world with exactly the same particles and constraints as [w],
 * so the two are timed on identical work. Object allocation order matches
 * creation order, which is the *best* case for AoS locality — a real game that
 * spawns and destroys pieces would fragment worse than this.
 */
fun mirrorToAoS(w: World): AoSWorld {
    val ps = Array(w.particleCount) { i ->
        PParticle(w.px[i], w.py[i], w.invMass[i])
    }
    val ds = Array(w.distanceCount) { k ->
        PDistance(ps[w.dcA[k]], ps[w.dcB[k]], w.dcRest[k], w.dcCompliance[k])
    }
    val ars = Array(w.areaCount) { k ->
        PArea(ps[w.acA[k]], ps[w.acB[k]], ps[w.acC[k]], w.acRest[k], w.acCompliance[k])
    }
    return AoSWorld(ps, ds, ars)
}
