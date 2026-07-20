package gravitris

import gravitris.game.SimConfig
import gravitris.physics.SoftBodyWorld
import gravitris.physics.XpbdSolver
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * Energy the solver invents rather than receives, and the ways two bodies end
 * up sharing space they should not.
 *
 * Opened while triaging the client's Fairphone 6 report — *"twee blokjes waren
 * interlocked, trillend voor een tijdje, toen los"*, two bodies interlocked,
 * trembling for a while, then apart — and the screenshot of two airborne bodies
 * with sharp concave spikes and a folded silhouette.
 *
 * ### What was *not* wrong
 *
 * **Compliance.** This was the leading hypothesis: `SimConfig` records that
 * above ~2e-4 bodies interpenetrate deeply and ADR 0003's rigid contacts inject
 * energy resolving it, and the shipped 1e-4 was called close to the edge. It is
 * not the cause. Swept across the range, the vibration is *worse* on stiffer
 * material — peak kinetic energy 266.6 at `distanceCompliance` 1e-6, 162.1 at
 * the shipped 1e-4, 122.8 at 2e-4. Stiffening to chase this would have made it
 * worse while deleting the feel the client approved ("Ja, beter"; "Ziet er goed
 * uit nu, blokjes raken elkaar").
 *
 * **The renderer's boundary extrusion.** Checked by reproducing
 * `VertexFill.extrudeBoundary` and counting concave corners in the drawn
 * silhouette: it never inverted in any scene measured, including the loudest.
 * It mildly amplifies concavity the physics already has — one raw concave
 * corner drew as three at a 40 units/s collision — which is worth the frontend
 * engineer's attention, but it does not create the defect.
 */
internal class InterlockJitterTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 10f, wellHeight = 20f)

    /**
     * The broadphase must find fast pairs.
     *
     * Two bodies converging in mid air, swept across the speed range the well
     * can actually produce — free fall in a 20-unit well reaches the terminal
     * cap of 30 units/s, so two bodies can close at 60.
     *
     * The failure this guards is specifically **non-monotonic**: while the grid
     * was rebuilt once per tick this was clean at 10, 20, 27, 30, 35 and 50 and
     * deeply wrong at 40 and 60, because whether a pair is missed depends on
     * where it happens to sit relative to a cell boundary when the grid is
     * built. A sweep is therefore not optional — a single speed proves nothing,
     * and the speed that happens to be tested proves least of all.
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
     * Built on [SoftBodyWorld] rather than `Simulation` because the public input
     * surface has no horizontal launch — a hard drop is vertical, and a drag is
     * kinematic by design. The collision this reproduces is between two *free*
     * bodies, which is what the client's screenshot showed, so neither would do.
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
        const val COLLISION_FRAMES = 60

        /**
         * Fraction of a particle diameter. Measured 0.000 after the fix across
         * the whole speed sweep, and up to 0.393 before it. Contacts are rigid
         * (ADR 0003 §2), so any sustained overlap is a missed pair rather than
         * a soft contact yielding.
         */
        const val PENETRATION_BUDGET = 0.05f
    }
}
