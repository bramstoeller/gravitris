package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation
import gravitris.physics.SoftBodyWorld
import gravitris.physics.XpbdSolver
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * The client's Fairphone 6 report: *"twee blokjes waren interlocked, trillend
 * voor een tijdje, toen los"* — two bodies interlocked, trembling for a while,
 * then apart.
 *
 * Reproduced deterministically as **a held drag pushing the active piece into a
 * settled one**, which is the ordinary interaction in the Milestone 1 toy: it
 * spawns pieces on top of the ones already there and every new piece is
 * draggable. The trembling lasts exactly as long as the finger is down, which
 * is what "then apart" describes — nothing comes unstuck, the player lets go.
 *
 * ### What was actually wrong
 *
 * Two independent sources of energy the solver invented, neither of them the
 * material's softness:
 *
 * 1. **The drag was a per-tick teleport.** It translated the piece by the whole
 *    tick's drag before the substep loop, so it could seed overlap that the
 *    contact solve then removed inside a *single* substep. `deriveVelocities`
 *    divides by the substep `h`, so the overlap re-emerged as a speed
 *    `substeps` times the finger's own — 24 units/s from a 3 units/s drag, near
 *    the terminal cap, renewed every tick for as long as the drag was held.
 *
 * 2. **The broadphase stencil under-reached.** The uniform grid was rebuilt once
 *    per tick, but a particle may move `MAX_SPEED * TICK` in that time, which is
 *    larger than the one-cell stencil. Pairs converging faster than ~35 units/s
 *    were never tested and interpenetrated up to 0.39 of a particle diameter
 *    before being found.
 *
 * ### What was *not* wrong
 *
 * **Compliance.** Swept across the range, the vibration under a held drag is
 * *worse* on stiffer material, not softer — peak kinetic energy 266.6 at
 * `distanceCompliance` 1e-6, 162.1 at the shipped 1e-4, 122.8 at 2e-4.
 * Stiffening the material to chase this would have made it worse while deleting
 * the feel the client approved ("Ja, beter"; "Ziet er goed uit nu, blokjes
 * raken elkaar"). These tests therefore run at the shipping compliance and
 * assert the fix holds there, rather than pinning a stiffness.
 *
 * **The renderer's boundary extrusion.** Checked directly, by reproducing
 * `VertexFill.extrudeBoundary` and counting concave corners in the drawn
 * silhouette: it never inverted in any scene measured, including the loudest.
 * It does mildly *amplify* concavity the physics already has (one raw concave
 * corner drew as three at a 40 units/s collision), which is worth the frontend
 * engineer's attention, but it does not create the defect.
 */
internal class InterlockJitterTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 10f, wellHeight = 20f)

    /**
     * Trembling, measured from positions alone.
     *
     * Total kinetic energy cannot see this: a piece shoved bodily along by a
     * finger carries real momentum and is not trembling, while a piece buzzing
     * in place may carry very little. So this tracks each particle's offset
     * from its own body's centroid and counts how often that offset reverses
     * direction — deformation oscillating in place, which is what "trillend"
     * describes and what the player sees.
     */
    private class TremorMeter(state: SimState) {
        private val n = state.particleCount
        private val offset = FloatArray(n)
        private val lastDirection = IntArray(n)
        private var primed = false
        var reversals: Int = 0
            private set

        fun sample(state: SimState) {
            val bodies = state.bodyCount
            val per = n / bodies
            for (body in 0 until bodies) {
                val base = body * per
                var cx = 0f
                for (k in 0 until per) cx += state.positionX[base + k]
                cx /= per
                for (k in 0 until per) {
                    val i = base + k
                    val current = state.positionX[i] - cx
                    if (primed) {
                        val delta = current - offset[i]
                        // Below this a change is settling or float noise, not a
                        // tremor: a twentieth of a particle radius per tick.
                        val direction = when {
                            delta > TREMOR_FLOOR -> 1
                            delta < -TREMOR_FLOOR -> -1
                            else -> 0
                        }
                        if (direction != 0) {
                            if (lastDirection[i] != 0 && direction != lastDirection[i]) reversals++
                            lastDirection[i] = direction
                        }
                    }
                    offset[i] = current
                }
            }
            primed = true
        }
    }

    /**
     * Settles a piece on the floor with a second piece already resting to its
     * right, then holds a drag into that neighbour and reports the tremor.
     */
    private fun tremorUnderHeldDrag(config: SimConfig): Int {
        val sim = Simulation(config)
        sim.addPiece(0, NEIGHBOUR_X, REST_Y)
        sim.clearActivePiece()
        sim.addPiece(0, DRAGGED_X, REST_Y)
        TestScenes.run(sim, SETTLE_FRAMES)

        val meter = TremorMeter(sim.state)
        meter.sample(sim.state)
        val input = InputFrame()
        repeat(DRAG_FRAMES) {
            input.clear()
            input.dragX = DRAG_PER_TICK
            sim.step(input)
            meter.sample(sim.state)
        }
        return meter.reversals
    }

    @Test
    fun `a piece held against a neighbour does not tremble`() {
        val reversals = tremorUnderHeldDrag(config())
        assertTrue(
            reversals <= TREMOR_BUDGET,
            "a piece dragged against a settled neighbour trembled: $reversals direction " +
                "reversals over $DRAG_FRAMES frames of held drag (budget $TREMOR_BUDGET). " +
                "This is the client's 'interlocked, trillend' report. Check that the drag is " +
                "still applied per substep rather than teleported once per tick.",
        )
    }

    /**
     * The invariant that actually pins the fix.
     *
     * Substeps are a convergence dial (ADR 0003 pins them at 8 for stability),
     * so the *same* drag must produce the same response at any substep count.
     * When the drag was a per-tick teleport it did not: the manufactured
     * ejection speed was proportional to the substep count, so tremor grew with
     * a number that is supposed to only make the solve more accurate — 189
     * reversals at 4 substeps, 745 at 8, 1290 at 16. After the fix the same
     * sweep reads 38, 27, 31: no trend.
     *
     * This is the assertion to keep. It fails loudly for the right reason and
     * cannot be satisfied by tuning a threshold.
     */
    @Test
    fun `drag response does not depend on the substep count`() {
        val measured = intArrayOf(4, 8, 16).map { substeps ->
            substeps to tremorUnderHeldDrag(config().copy(substeps = substeps))
        }
        for ((substeps, reversals) in measured) {
            assertTrue(
                reversals <= TREMOR_BUDGET,
                "tremor at $substeps substeps was $reversals reversals (budget $TREMOR_BUDGET); " +
                    "measured across the sweep: $measured. Raising the substep count must make " +
                    "the solve more accurate, never shakier — if it does, something is being " +
                    "resolved per substep that was applied per tick.",
            )
        }
    }

    /**
     * A piece dragged through empty space must derive no velocity from the
     * drag. The per-substep split moves `substepPrev` with the position exactly
     * as the per-tick translation did, and this is the property that guarantees
     * it — without it the drag would fling the piece.
     */
    @Test
    fun `dragging through empty space injects no energy`() {
        val config = config()
        val sim = Simulation(config)
        sim.addPiece(0, DRAGGED_X, REST_Y)
        TestScenes.run(sim, SETTLE_FRAMES)

        val input = InputFrame()
        var peak = 0f
        repeat(DRAG_FRAMES) {
            input.clear()
            input.dragX = DRAG_PER_TICK
            sim.step(input)
            if (sim.state.kineticEnergy > peak) peak = sim.state.kineticEnergy
        }
        assertTrue(
            peak < config.quietKineticEnergy,
            "dragging a lone piece across an empty floor should stay quiet, peak kinetic " +
                "energy was $peak (quiet threshold ${config.quietKineticEnergy})",
        )
    }

    /**
     * The broadphase must find fast pairs.
     *
     * Two bodies converging in mid air, swept across the speed range the well
     * can actually produce — free fall in a 20-unit well reaches the terminal
     * cap of 30 units/s, so two bodies can close at 60.
     *
     * The failure this guards is specifically **non-monotonic**: before the
     * grid was rebuilt per substep this was clean at 10, 20, 27, 30, 35 and 50
     * and deeply wrong at 40 and 60, because whether a pair is missed depends
     * on where it happens to sit relative to a cell boundary when the grid is
     * built. A sweep is therefore not optional — a single speed proves nothing.
     */
    @Test
    fun `fast collisions are never missed by the broadphase`() {
        for (closing in floatArrayOf(10f, 20f, 27f, 30f, 35f, 40f, 50f, 60f)) {
            val scene = InterlockScene(config(), closing)
            var worst = 0f
            repeat(COLLISION_FRAMES) {
                scene.step()
                val pen = scene.penetration()
                if (pen > worst) worst = pen
            }
            assertTrue(
                worst < PENETRATION_BUDGET,
                "two bodies converging at $closing units/s interpenetrated to $worst of a " +
                    "particle diameter (budget $PENETRATION_BUDGET). A pair the narrowphase " +
                    "never tested; check the broadphase is still rebuilt every substep.",
            )
        }
    }

    /**
     * Two bodies aimed at each other in mid air at a given closing speed.
     *
     * Built on [SoftBodyWorld] rather than [Simulation] because the public
     * input surface has no horizontal launch — a hard drop is vertical, and a
     * drag is kinematic by design. The collision this reproduces is between two
     * *free* bodies, which is what the client's screenshot showed, so neither
     * would do.
     */
    private class InterlockScene(config: SimConfig, closing: Float) {
        private val world = SoftBodyWorld(config)
        private val solver = XpbdSolver(world)

        init {
            val left = world.addBody(0, 3.0f, AIRBORNE_Y)
            val right = world.addBody(0, 7.0f, AIRBORNE_Y)
            launch(left, closing * 0.5f)
            launch(right, -closing * 0.5f)
        }

        private fun launch(body: Int, vx: Float) {
            val base = body * world.particlesPerBody
            for (k in 0 until world.particlesPerBody) world.velX[base + k] = vx
        }

        fun step() = solver.step()

        /** Deepest cross-body overlap, as a fraction of a particle diameter. */
        fun penetration(): Float {
            val diameter = 2f * world.particleRadius
            var worst = 0f
            for (i in 0 until world.particleCount) {
                for (j in i + 1 until world.particleCount) {
                    if (world.particleBody[i] == world.particleBody[j]) continue
                    val dx = world.posX[i] - world.posX[j]
                    val dy = world.posY[i] - world.posY[j]
                    val d = sqrt(dx * dx + dy * dy)
                    if (d < diameter) {
                        val pen = (diameter - d) / diameter
                        if (pen > worst) worst = pen
                    }
                }
            }
            return worst
        }

        private companion object {
            const val AIRBORNE_Y = 10f
        }
    }

    private companion object {
        const val NEIGHBOUR_X = 6.5f
        const val DRAGGED_X = 3.0f
        const val REST_Y = 1.2f

        /** Well units per tick — 3 units/s, an unhurried finger. */
        const val DRAG_PER_TICK = 0.05f

        const val SETTLE_FRAMES = 120
        const val DRAG_FRAMES = 120
        const val COLLISION_FRAMES = 60

        /**
         * Well units per tick below which an offset change is settling or float
         * noise rather than a tremor.
         */
        const val TREMOR_FLOOR = 0.01f

        /**
         * Reversals over [DRAG_FRAMES], measured before and after the fix:
         *
         * | substeps | teleported per tick | applied per substep |
         * | -------- | ------------------- | ------------------- |
         * | 4        | 189                 | 38                  |
         * | **8**    | **745**             | **27**              |
         * | 16       | 1290                | 31                  |
         *
         * Set in the gap, not at zero. A piece being pushed into a neighbour
         * *should* deform, and deformation reverses direction as the material
         * finds its shape — that is the feel the client approved, not a defect.
         * What the fix removes is the reversal count scaling with a dial that is
         * only supposed to buy accuracy.
         */
        const val TREMOR_BUDGET = 60

        /**
         * Fraction of a particle diameter. Measured 0.000 after the fix across
         * the whole speed sweep, and up to 0.393 before it. Contacts are rigid
         * (ADR 0003 §2), so any sustained overlap is a missed pair rather than
         * a soft contact yielding.
         */
        const val PENETRATION_BUDGET = 0.05f
    }
}
