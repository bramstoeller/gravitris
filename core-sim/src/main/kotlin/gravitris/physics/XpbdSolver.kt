package gravitris.physics

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Substepped XPBD solver (ADR 0001, ADR 0003).
 *
 * One [step] advances exactly one fixed 1/60 s tick, subdivided into
 * `config.substeps` substeps. Each substep integrates, then runs **one**
 * iteration of each constraint group — the "small steps" formulation, which is
 * what keeps compliance an honest material parameter rather than something
 * that drifts with iteration count.
 *
 * ### Determinism (ADR 0006)
 *
 * This class uses only `+ - * /` and [sqrt], all of which are strict IEEE-754
 * on JVM 17+ with `Math.sqrt` correctly rounded, so results are bit-identical
 * across runs *and* across devices. Two rules keep it that way and must be
 * held in review:
 *
 * - **No transcendental functions.** No `sin`, `cos`, `exp` or `pow` — they
 *   are permitted 1–2 ulp of platform variance and would break cross-device
 *   determinism silently. Piece rotation uses exact quarter turns instead.
 * - **No concurrency in the constraint solve.** Gauss-Seidel is
 *   order-dependent; parallelising it without graph colouring changes results
 *   run to run. Constraint order and contact order are part of the contract.
 *
 * ### Allocation
 *
 * Nothing here allocates. Every buffer is owned by [world] or by this class
 * and sized at construction. `AllocationTest` asserts it.
 */
internal class XpbdSolver(private val world: SoftBodyWorld) {

    private val config = world.config

    private val grid = UniformGrid(
        cellSize = 2f * world.particleRadius,
        originX = world.wellMinX,
        originY = world.wellFloorY,
        width = world.wellMaxX - world.wellMinX,
        // Headroom above the well: a stack that overflows must still collide.
        height = config.wellHeight * 2f,
        particleCapacity = world.particleCapacity,
    )

    // --- impact reporting ---------------------------------------------------

    /**
     * Impacts are aggregated **per body**, not per particle. A piece landing
     * puts five or six edge particles into contact on the same tick, and
     * reporting each of them separately would hand the haptics layer a burst
     * of near-identical events to de-duplicate itself. One event per body per
     * tick, at the centroid of its newly-contacting particles and carrying the
     * strongest approach speed among them, is what the shell actually wants.
     * That also bounds the list by body count, so there is no eviction policy
     * to get wrong.
     */
    val impactX = FloatArray(world.maxBodies)
    val impactY = FloatArray(world.maxBodies)
    val impactStrength = FloatArray(world.maxBodies)
    var impactCount: Int = 0
        private set

    private val bodyImpactSumX = FloatArray(world.maxBodies)
    private val bodyImpactSumY = FloatArray(world.maxBodies)
    private val bodyImpactHits = IntArray(world.maxBodies)
    private val bodyImpactPeak = FloatArray(world.maxBodies)

    var kineticEnergy: Float = 0f
        private set

    // --- kinematic drag -----------------------------------------------------

    /**
     * A player drag, in well units for the whole tick, spread evenly over the
     * substeps. Set by `Simulation` before [step]; consumed and cleared by it.
     *
     * **Why the solver applies this rather than the caller.** A drag is a
     * kinematic translation: the finger moves the piece regardless of what the
     * physics wants. Applying the whole tick's motion in one teleport before
     * the substep loop puts the piece up to `dragDeltaX` *inside* whatever it
     * was dragged against, and the contact solve then removes that overlap
     * inside a single substep. [deriveVelocities] divides position change by
     * the substep `h`, not the tick, so the overlap comes back out as a speed
     * of `dragDeltaX / h` — `substeps` times the speed the finger was actually
     * moving at. At 8 substeps a leisurely 3 units/s drag manufactured 24
     * units/s of ejection, close to [MAX_SPEED], every tick for as long as the
     * finger was held. That is the "interlocked, vibrating, then apart" the
     * client reported: the vibration lasts exactly as long as the drag.
     *
     * Spreading the same translation over the substeps makes the manufactured
     * speed `(dragDeltaX / substeps) / h` — which is just `dragDeltaX / TICK`,
     * the finger's real speed, independent of the substep count. The piece
     * still squashes against whatever it is pushed into, because the contact
     * solve still resolves the overlap; it simply stops being resolved
     * `substeps` times too hard.
     *
     * Measured on the two-body repro (`InterlockJitterTest`): peak kinetic
     * energy during a held drag falls from 162 to under the quiet threshold,
     * and no longer grows with the substep count.
     */
    var dragBody: Int = -1
    var dragDeltaX: Float = 0f

    // --- tick ---------------------------------------------------------------

    fun step() {
        val n = world.particleCount
        if (n == 0) {
            kineticEnergy = 0f
            impactCount = 0
            dragBody = -1
            dragDeltaX = 0f
            return
        }

        beginTick(n)

        val h = TICK / config.substeps
        for (s in 0 until config.substeps) {
            integrate(h, n)
            applyDragSubstep()
            // Rebuilt every substep, not every tick (revising ADR 0003 §1).
            //
            // The narrowphase stencil reaches one cell — `2 * particleRadius`,
            // 0.45 well units at lattice 5 — around the cell a particle was
            // bucketed into. A pair bucketed two cells apart is never tested,
            // and at build time such a pair is at least one cell apart, so the
            // stencil is only sound while a pair cannot close a cell's width
            // before the next rebuild.
            //
            // Per *tick* that bound does not hold: two particles may each move
            // `MAX_SPEED * TICK` = 0.5 units, closing 1.0 against a 0.45 cell.
            // Measured, two bodies converging faster than ~35 units/s
            // interpenetrated up to 0.39 of a particle diameter with no contact
            // ever being detected, and the correction — rigid, resolved inside
            // one substep — came back out as ejection velocity at the terminal
            // cap. It is non-monotonic in closing speed (clean at 50, deep at
            // 40 and 60), which is the signature of a missed pair rather than
            // of a stiffness limit.
            //
            // Per *substep* the bound holds with margin: `MAX_SPEED * h` is
            // 0.0625, closing 0.125 against the same 0.45 cell, a 3.6x margin.
            //
            // Cost measured on the ADR 0001 reference scene (960 particles, 60
            // bodies): a build is 0.6% of a step, and seven extra builds raised
            // a step from 468 to 478 us, +2.1%. That is a relative cost and
            // survives the 12.06x device derating unchanged.
            grid.build(world.posX, world.posY, n)
            // XPBD accumulates a Lagrange multiplier per constraint within a
            // substep; it must be reset at the start of each one or the
            // effective compliance drifts.
            world.dcLambda.fill(0f, 0, world.distanceCount)
            world.acLambda.fill(0f, 0, world.areaCount)
            solveDistance(h)
            solveArea(h)
            solveContacts(lastSubstep = s == config.substeps - 1, n = n)
            deriveVelocities(h, n)
        }

        endTick(n)
        dragBody = -1
        dragDeltaX = 0f
    }

    /**
     * One substep's share of the pending drag.
     *
     * `substepPrev` moves with the position, so a piece dragged through empty
     * space derives no velocity from the drag at all — the property the
     * original per-tick translation was written to have, and which the
     * per-substep split preserves. Only the part of the motion the contact
     * solve *undoes* becomes velocity, which is the part that physically
     * should.
     */
    private fun applyDragSubstep() {
        val body = dragBody
        if (body < 0 || dragDeltaX == 0f) return
        val step = dragDeltaX / config.substeps
        val base = body * world.particlesPerBody
        for (k in 0 until world.particlesPerBody) {
            val i = base + k
            world.posX[i] += step
            world.substepPrevX[i] += step
        }
    }

    private fun beginTick(n: Int) {
        for (i in 0 until n) {
            world.framePrevX[i] = world.posX[i]
            world.framePrevY[i] = world.posY[i]
            world.inContactLastTick[i] = world.inContactThisTick[i]
            world.inContactThisTick[i] = false
            world.impactSpeed[i] = 0f
        }
        world.particleContact.fill(0f, 0, n)
        impactCount = 0
    }

    private fun integrate(h: Float, n: Int) {
        val gh = config.gravity * h
        for (i in 0 until n) {
            world.substepPrevX[i] = world.posX[i]
            world.substepPrevY[i] = world.posY[i]
            if (world.invMass[i] == 0f) continue
            world.velY[i] += gh
            world.posX[i] += world.velX[i] * h
            world.posY[i] += world.velY[i] * h
        }
    }

    private fun deriveVelocities(h: Float, n: Int) {
        val inv = 1f / h
        // ADR 0003 §5: position-based dynamics conserves energy well enough
        // that an undamped pile rings indefinitely. This is a feel parameter
        // as much as a stability one.
        val retain = 1f - config.linearDamping
        for (i in 0 until n) {
            var vx = (world.posX[i] - world.substepPrevX[i]) * inv * retain
            var vy = (world.posY[i] - world.substepPrevY[i]) * inv * retain
            // Terminal velocity. Heavy material should stop accelerating, and
            // capping speed also bounds how far a particle can travel between
            // broadphase rebuilds (the grid is rebuilt per frame, not per
            // substep — ADR 0003 §1). Deliberately a fixed constant rather
            // than something derived from cell size: a lattice-dependent cap
            // would make the physics differ per ADR 0009 quality tier, and an
            // accessibility or performance setting must never change what
            // happens in the game.
            val speedSq = vx * vx + vy * vy
            if (speedSq > MAX_SPEED * MAX_SPEED) {
                val scale = MAX_SPEED / sqrt(speedSq)
                vx *= scale
                vy *= scale
            }
            world.velX[i] = vx
            world.velY[i] = vy
        }
    }

    // --- constraint kernels -------------------------------------------------

    private fun solveDistance(h: Float) {
        val alpha = config.distanceCompliance / (h * h)
        val px = world.posX
        val py = world.posY
        val invMass = world.invMass
        for (k in 0 until world.distanceCount) {
            val a = world.dcA[k]
            val b = world.dcB[k]
            val wa = invMass[a]
            val wb = invMass[b]
            val wsum = wa + wb
            if (wsum == 0f) continue
            val dx = px[a] - px[b]
            val dy = py[a] - py[b]
            val d = sqrt(dx * dx + dy * dy)
            if (d < EPS) continue
            val c = d - world.dcRest[k]
            val dl = (-c - alpha * world.dcLambda[k]) / (wsum + alpha)
            world.dcLambda[k] += dl
            val nx = dx / d
            val ny = dy / d
            px[a] += wa * dl * nx
            py[a] += wa * dl * ny
            px[b] -= wb * dl * nx
            py[b] -= wb * dl * ny
        }
    }

    /**
     * Two area constraints per lattice cell. These are what stop a body
     * collapsing flat under load and what make it bulge sideways into gaps
     * instead of thinning out — and bulging sideways is the coverage-band
     * mechanic, so this kernel is load-bearing for the product, not just for
     * stability (ADR 0001).
     */
    private fun solveArea(h: Float) {
        val alpha = config.areaCompliance / (h * h)
        val px = world.posX
        val py = world.posY
        val invMass = world.invMass
        for (k in 0 until world.areaCount) {
            val a = world.acA[k]
            val b = world.acB[k]
            val c = world.acC[k]
            val wa = invMass[a]
            val wb = invMass[b]
            val wc = invMass[c]
            if (wa + wb + wc == 0f) continue
            val xa = px[a]; val ya = py[a]
            val xb = px[b]; val yb = py[b]
            val xc = px[c]; val yc = py[c]
            val area = 0.5f * ((xb - xa) * (yc - ya) - (xc - xa) * (yb - ya))
            val err = area - world.acRest[k]
            // Gradient of signed area with respect to each vertex.
            val gxa = 0.5f * (yb - yc); val gya = 0.5f * (xc - xb)
            val gxb = 0.5f * (yc - ya); val gyb = 0.5f * (xa - xc)
            val gxc = 0.5f * (ya - yb); val gyc = 0.5f * (xb - xa)
            val wsum = wa * (gxa * gxa + gya * gya) +
                wb * (gxb * gxb + gyb * gyb) +
                wc * (gxc * gxc + gyc * gyc)
            if (wsum < EPS) continue
            val dl = (-err - alpha * world.acLambda[k]) / (wsum + alpha)
            world.acLambda[k] += dl
            px[a] += wa * dl * gxa; py[a] += wa * dl * gya
            px[b] += wb * dl * gxb; py[b] += wb * dl * gyb
            px[c] += wc * dl * gxc; py[c] += wc * dl * gyc
        }
    }

    // --- contacts -----------------------------------------------------------

    /**
     * Non-penetration and Coulomb friction, solved together in one pass.
     *
     * **Contacts are rigid, not compliant** (ADR 0003 §2). Bodies are soft
     * because their *interior* constraints are compliant; making the contact
     * soft as well would let bodies visibly sink into each other, which reads
     * as a rendering bug rather than as softness.
     *
     * **Penetration depth is captured before the normal correction is
     * applied**, because the friction clamp is proportional to it. The spike
     * computed ground friction from the depth *after* resolving it, so the
     * depth was always zero and friction was effectively infinite (spike
     * README, bug 2). That is an easy mistake to repeat and the reason the
     * ordering here is explicit.
     */
    private fun solveContacts(lastSubstep: Boolean, n: Int) {
        solveBoundaryContacts(lastSubstep, n)
        solveParticleContacts(lastSubstep, n)
    }

    private fun solveBoundaryContacts(lastSubstep: Boolean, n: Int) {
        val r = world.particleRadius
        val minX = world.wellMinX + r
        val maxX = world.wellMaxX - r
        val floorY = world.wellFloorY + r
        val mu = config.friction
        // Boundary contacts are checked directly for every particle every
        // substep and never go through the broadphase, so landing on the floor
        // or sliding down a wall is exact regardless of speed.
        for (i in 0 until n) {
            if (world.invMass[i] == 0f) continue

            val depthFloor = floorY - world.posY[i]
            if (depthFloor > 0f) {
                // Landing on the floor is the loudest event in the game and
                // must reach the haptics layer. Boundary normals are axis
                // aligned, so the approach speed is just the inward velocity
                // component.
                recordApproach(i, -world.velY[i])
                world.posY[i] += depthFloor
                applyBoundaryFriction(i, mu * depthFloor, tangentIsX = true)
                noteContact(i, depthFloor, lastSubstep)
            }

            val depthLeft = minX - world.posX[i]
            if (depthLeft > 0f) {
                recordApproach(i, -world.velX[i])
                world.posX[i] += depthLeft
                applyBoundaryFriction(i, mu * depthLeft, tangentIsX = false)
                noteContact(i, depthLeft, lastSubstep)
            }

            val depthRight = world.posX[i] - maxX
            if (depthRight > 0f) {
                recordApproach(i, world.velX[i])
                world.posX[i] -= depthRight
                applyBoundaryFriction(i, mu * depthRight, tangentIsX = false)
                noteContact(i, depthRight, lastSubstep)
            }
        }
    }

    /**
     * Cancels tangential motion against a static boundary, up to [maxSlide].
     *
     * When the accumulated tangential motion is smaller than the clamp the
     * cancellation is total, which is static friction; beyond it the surface
     * slides. ADR 0003 records the honest caveat: in a settled pile both the
     * motion and the clamp are tiny and the ratio usually exceeds one, so a
     * settled pile is somewhat *stickier* than reality. That helps stability
     * and may hurt the feel of material flowing into gaps — a tuning risk to
     * watch at Milestone 1, not a known defect.
     */
    private fun applyBoundaryFriction(i: Int, maxSlide: Float, tangentIsX: Boolean) {
        if (tangentIsX) {
            val t = world.posX[i] - world.substepPrevX[i]
            val len = abs(t)
            if (len < EPS) return
            world.posX[i] -= t * min(1f, maxSlide / len)
        } else {
            val t = world.posY[i] - world.substepPrevY[i]
            val len = abs(t)
            if (len < EPS) return
            world.posY[i] -= t * min(1f, maxSlide / len)
        }
    }

    private fun solveParticleContacts(lastSubstep: Boolean, n: Int) {
        val r2 = 2f * world.particleRadius
        val restSq = r2 * r2
        val px = world.posX
        val py = world.posY
        val mu = config.friction

        for (i in 0 until n) {
            val wi = world.invMass[i]
            if (wi == 0f) continue
            // Stencil is centred on the cell the particle was bucketed into at
            // the frame's rebuild, not on where it is now. Using the stored
            // cell is what makes pair visitation independent of intra-frame
            // motion, and therefore reproducible.
            val cell = grid.cellOf(i)
            val cx = cell % grid.columns
            val cy = cell / grid.columns
            val bodyI = world.particleBody[i]

            var ny = cy - 1
            while (ny <= cy + 1) {
                var nx = cx - 1
                while (nx <= cx + 1) {
                    if (nx < 0 || nx >= grid.columns || ny < 0 || ny >= grid.rowCount) {
                        nx++
                        continue
                    }
                    val c = grid.cellIndex(nx, ny)
                    var k = grid.startOf(c)
                    val end = grid.endOf(c)
                    while (k < end) {
                        val j = grid.entryAt(k)
                        k++
                        // `j > i` visits each unordered pair exactly once
                        // regardless of stencil shape, which keeps the
                        // uniqueness argument independent of the grid
                        // geometry.
                        if (j <= i) continue
                        // Self-collision within a body is off (ADR 0003 §6):
                        // bodies are held in shape by their own distance and
                        // area constraints, and skipping same-body pairs cuts
                        // narrowphase work substantially.
                        if (world.particleBody[j] == bodyI) continue

                        val dx = px[i] - px[j]
                        val dy = py[i] - py[j]
                        val dSq = dx * dx + dy * dy
                        if (dSq >= restSq || dSq < EPS) continue

                        val wj = world.invMass[j]
                        val wsum = wi + wj
                        if (wsum == 0f) continue

                        val d = sqrt(dSq)
                        val depth = r2 - d
                        val nxr = dx / d
                        val nyr = dy / d

                        recordImpact(i, j, nxr, nyr)

                        val si = wi / wsum
                        val sj = wj / wsum
                        px[i] += si * depth * nxr
                        py[i] += si * depth * nyr
                        px[j] -= sj * depth * nxr
                        py[j] -= sj * depth * nyr

                        // Friction, using the depth captured above.
                        val relX = (px[i] - world.substepPrevX[i]) - (px[j] - world.substepPrevX[j])
                        val relY = (py[i] - world.substepPrevY[i]) - (py[j] - world.substepPrevY[j])
                        val rn = relX * nxr + relY * nyr
                        val tx = relX - rn * nxr
                        val ty = relY - rn * nyr
                        val tLen = sqrt(tx * tx + ty * ty)
                        if (tLen > EPS) {
                            val scale = min(1f, mu * depth / tLen)
                            px[i] -= si * tx * scale
                            py[i] -= si * ty * scale
                            px[j] += sj * tx * scale
                            py[j] += sj * ty * scale
                        }

                        noteContact(i, depth, lastSubstep)
                        noteContact(j, depth, lastSubstep)
                    }
                    nx++
                }
                ny++
            }
        }
    }

    /**
     * Records that particle [i] is touching something, at [depth].
     *
     * Occlusion is only accumulated on the final substep so it describes the
     * configuration actually being rendered, rather than a sum over eight
     * intermediate ones.
     */
    private fun noteContact(i: Int, depth: Float, lastSubstep: Boolean) {
        world.inContactThisTick[i] = true
        if (!lastSubstep) return
        val occlusion = depth / (2f * world.particleRadius)
        val acc = world.particleContact[i] + occlusion
        world.particleContact[i] = if (acc > 1f) 1f else acc
    }

    private fun recordImpact(i: Int, j: Int, nx: Float, ny: Float) {
        // Approach speed along the contact normal, using the velocities the
        // particles carried into this substep.
        val rvx = world.velX[i] - world.velX[j]
        val rvy = world.velY[i] - world.velY[j]
        val approach = -(rvx * nx + rvy * ny)
        if (approach <= 0f) return
        if (approach > world.impactSpeed[i]) world.impactSpeed[i] = approach
        if (approach > world.impactSpeed[j]) world.impactSpeed[j] = approach
    }

    /** Peak closing speed a particle has seen this tick, from any contact. */
    private fun recordApproach(i: Int, approach: Float) {
        if (approach > world.impactSpeed[i]) world.impactSpeed[i] = approach
    }

    private fun endTick(n: Int) {
        updateCompression(n)
        emitImpacts(n)
        kineticEnergy = world.kineticEnergy()
    }

    /**
     * Per-particle current/rest area, averaged over the lattice cells the
     * particle belongs to. 1 is undeformed; the renderer reads it as "how
     * squashed this material is", which is the primary weight cue.
     */
    private fun updateCompression(n: Int) {
        world.compressionCurrent.fill(0f, 0, n)
        world.compressionRest.fill(0f, 0, n)
        for (k in 0 until world.areaCount) {
            val a = world.acA[k]
            val b = world.acB[k]
            val c = world.acC[k]
            val current = abs(world.signedArea(a, b, c))
            val rest = abs(world.acRest[k])
            world.compressionCurrent[a] += current
            world.compressionCurrent[b] += current
            world.compressionCurrent[c] += current
            world.compressionRest[a] += rest
            world.compressionRest[b] += rest
            world.compressionRest[c] += rest
        }
        for (i in 0 until n) {
            val rest = world.compressionRest[i]
            world.particleCompression[i] = if (rest > EPS) world.compressionCurrent[i] / rest else 1f
        }
    }

    private fun emitImpacts(n: Int) {
        val bodies = world.bodyCount
        bodyImpactHits.fill(0, 0, bodies)
        bodyImpactSumX.fill(0f, 0, bodies)
        bodyImpactSumY.fill(0f, 0, bodies)
        bodyImpactPeak.fill(0f, 0, bodies)

        for (i in 0 until n) {
            // Only a contact that is *new* this tick is an impact. A particle
            // resting in a settled pile is in contact every tick and must not
            // fire a haptic every tick.
            if (!world.inContactThisTick[i] || world.inContactLastTick[i]) continue
            val speed = world.impactSpeed[i]
            if (speed < IMPACT_SPEED_THRESHOLD) continue
            val body = world.particleBody[i]
            bodyImpactHits[body]++
            bodyImpactSumX[body] += world.posX[i]
            bodyImpactSumY[body] += world.posY[i]
            val strength = min(1f, world.mass[i] * speed / IMPACT_FULL_SCALE_MOMENTUM)
            if (strength > bodyImpactPeak[body]) bodyImpactPeak[body] = strength
        }

        var out = 0
        for (b in 0 until bodies) {
            val hits = bodyImpactHits[b]
            if (hits == 0) continue
            impactX[out] = bodyImpactSumX[b] / hits
            impactY[out] = bodyImpactSumY[b] / hits
            impactStrength[out] = bodyImpactPeak[b]
            out++
        }
        impactCount = out
    }

    companion object {
        /** The fixed simulation tick. Never variable, never wall-clock scaled (ADR 0006). */
        const val TICK: Float = 1f / 60f

        private const val EPS: Float = 1e-8f

        /**
         * Terminal velocity, well units/second. A tuned feel constant: heavy
         * material should stop accelerating. Chosen above free-fall speed
         * across a default 20-unit well so it shapes long drops without
         * capping ordinary falling.
         */
        private const val MAX_SPEED: Float = 30f

        /**
         * Approach speed below which a new contact is a settle, not an impact,
         * and fires no haptic. Tuned.
         */
        private const val IMPACT_SPEED_THRESHOLD: Float = 1.5f

        /** Momentum (mass x speed) that maps to impact strength 1.0. Tuned. */
        private const val IMPACT_FULL_SCALE_MOMENTUM: Float = 20f
    }
}
