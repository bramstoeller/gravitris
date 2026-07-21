package gravitris

import gravitris.game.SimConfig
import gravitris.game.Simulation
import gravitris.physics.PieceShapes
import gravitris.physics.SoftBodyWorld
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The seamless per-archetype render topology (ADR 0018) — the fix for the
 * internal cell-seam "+" the client reported (handoff 0038). These pin that a
 * tetromino is ONE continuous mesh, not four abutting cell meshes:
 *
 * - every internal seam is bridged by real triangles,
 * - no render triangle edge spans a UV discontinuity, so grain/specular do not
 *   step by one grid unit at a seam,
 * - the render triangulation is EXACTLY the solver's area constraints, so
 *   `particleCompression` is continuous across a seam too — not only the UV.
 *
 * Companion to the per-cell checks in [TetrominoShapeTest]; these exercise the
 * whole-piece topology those do not.
 */
class SeamlessTopologyTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f)

    @Test
    fun `every internal seam is bridged by triangles`() {
        val l = config().lattice
        val cell = l * l
        for (a in 0 until PieceShapes.COUNT) {
            val tris = triangles(a)
            for (c in 0 until PieceShapes.CELLS) {
                for (dir in DIRS) {
                    val nb = PieceShapes.neighbour(a, c, dir[0], dir[1])
                    // Count each unordered seam once; skip absent neighbours.
                    if (nb <= c) continue
                    val cRange = c * cell until (c + 1) * cell
                    val nRange = nb * cell until (nb + 1) * cell
                    val bridged = tris.any { t ->
                        t.any { it in cRange } && t.any { it in nRange }
                    }
                    assertTrue(
                        bridged,
                        "archetype $a seam between cells $c and $nb has no bridging triangle; " +
                            "the seam would read as a gap/step (the '+')",
                    )
                }
            }
        }
    }

    @Test
    fun `no render triangle edge spans a uv discontinuity`() {
        // The proof the "+" is gone: every triangle edge — interior AND seam
        // bridge — connects lattice-adjacent particles, so it never jumps the
        // body-wide UV by more than one grid step. A per-cell mesh would have NO
        // edge crossing a seam, and the two cells' facing columns would differ by
        // a step the shader interpolates across nothing; here the bridge carries
        // that step as a real, interpolated edge.
        for (a in 0 until PieceShapes.COUNT) {
            val s = Simulation(config()).also { it.addPiece(a, 9f, 9f) }.state
            // One column advances the body-wide UV by exactly this, everywhere.
            val step = s.particleU[1] - s.particleU[0]
            val tol = step * 1e-3f + 1e-6f
            for (t in triangles(a)) {
                for (e in EDGES) {
                    val p = t[e[0]]
                    val q = t[e[1]]
                    val du = abs(s.particleU[p] - s.particleU[q])
                    val dv = abs(s.particleV[p] - s.particleV[q])
                    assertTrue(
                        du <= step + tol,
                        "archetype $a edge $p-$q jumps U by $du > one grid step $step; " +
                            "the grain/specular would step across it",
                    )
                    assertTrue(
                        dv <= step + tol,
                        "archetype $a edge $p-$q jumps V by $dv > one grid step $step",
                    )
                }
            }
        }
    }

    @Test
    fun `the render topology is exactly the solver's area constraints`() {
        // The invariant that keeps particleCompression honest across the whole
        // piece, seams included: every render triangle is one area constraint and
        // vice versa. TopologyMatchesSolverTest guards this per cell in :app; this
        // extends it to the seam bridges, which are the seam area triples verbatim.
        for (a in 0 until PieceShapes.COUNT) {
            val world = SoftBodyWorld(config())
            world.addBody(a, 9f, 9f) // first body: base 0, so absolute == body-local

            val constraints = HashSet<List<Int>>()
            var realConstraints = 0
            for (k in 0 until world.areaCount) {
                val tri = intArrayOf(world.acA[k], world.acB[k], world.acC[k])
                // Inert padding is a == b == c; a real triangle never repeats.
                if (tri[0] == tri[1] && tri[1] == tri[2]) continue
                realConstraints++
                constraints.add(tri.sorted())
            }

            val render = triangles(a)
            val renderSet = render.map { it.sorted() }.toHashSet()

            assertEquals(
                realConstraints, render.size,
                "archetype $a: ${render.size} render triangles vs $realConstraints area " +
                    "constraints — the mesh and the solver disagree on triangle count",
            )
            assertEquals(
                constraints, renderSet,
                "archetype $a render triangles are not the solver's area constraints; " +
                    "compression shading would describe geometry not on screen",
            )
        }
    }

    @Test
    fun `particleFreeEdges is the per-direction detail behind particleEdge`() {
        val mask = SoftBodyWorld.FREE_LEFT or SoftBodyWorld.FREE_RIGHT or
            SoftBodyWorld.FREE_DOWN or SoftBodyWorld.FREE_UP
        for (a in 0 until PieceShapes.COUNT) {
            val s = Simulation(config()).also { it.addPiece(a, 9f, 9f) }.state
            for (i in 0 until s.particleCount) {
                val m = s.particleFreeEdges[i]
                assertEquals(
                    s.particleEdge[i] == 1f, m != 0,
                    "archetype $a particle $i: particleEdge and the free-edge mask disagree",
                )
                assertEquals(
                    0, m and mask.inv(),
                    "archetype $a particle $i has bits outside the 4-bit free-edge mask: $m",
                )
                assertTrue(
                    !(m and SoftBodyWorld.FREE_LEFT != 0 && m and SoftBodyWorld.FREE_RIGHT != 0),
                    "archetype $a particle $i is flagged free on both left and right",
                )
                assertTrue(
                    !(m and SoftBodyWorld.FREE_DOWN != 0 && m and SoftBodyWorld.FREE_UP != 0),
                    "archetype $a particle $i is flagged free on both down and up",
                )
            }
        }
    }

    @Test
    fun `a seam-facing side has its direction bit clear`() {
        // The O piece: cell 0's right column faces cell 1 (a seam), so its RIGHT
        // bit must be clear — extruding it would push it into cell 1 and reopen
        // the seam the bridge just closed. Its left column faces empty space, so
        // its LEFT bit must be set so it extrudes out to the silhouette.
        val l = config().lattice
        val mid = l / 2
        val s = Simulation(config()).also { it.addPiece(1, 9f, 9f) }.state // O
        val seamFacing = 0 * (l * l) + mid * l + (l - 1)
        val freeFacing = 0 * (l * l) + mid * l + 0
        assertEquals(
            0, s.particleFreeEdges[seamFacing] and SoftBodyWorld.FREE_RIGHT,
            "a seam-facing side must not be flagged free, or extrusion would reopen the seam",
        )
        assertTrue(
            s.particleFreeEdges[freeFacing] and SoftBodyWorld.FREE_LEFT != 0,
            "the outer side must be flagged free so extrusion reaches the silhouette",
        )
    }

    @Test
    fun `the per-archetype topology is static, in range and non-degenerate`() {
        val first = Simulation(config()).state.bodyTriangleIndices
        val second = Simulation(config()).state.bodyTriangleIndices
        val ppb = 4 * config().lattice * config().lattice

        assertEquals(first.size, second.size, "the archetype count changed between constructions")
        for (arch in first.indices) {
            assertArrayEquals(
                first[arch], second[arch],
                "archetype $arch topology differs between two constructions; it must be static",
            )
            val t = first[arch]
            assertTrue(t.size % 3 == 0, "archetype $arch index count $t.size is not a multiple of 3")
            for (v in t) {
                assertTrue(v in 0 until ppb, "archetype $arch index $v is outside [0, $ppb)")
            }
            var k = 0
            while (k < t.size) {
                assertTrue(
                    t[k] != t[k + 1] && t[k + 1] != t[k + 2] && t[k] != t[k + 2],
                    "archetype $arch has a degenerate triangle at index $k",
                )
                k += 3
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    /** The archetype's whole-piece render triangles, as `[i0, i1, i2]` triples. */
    private fun triangles(a: Int): List<IntArray> {
        val idx = Simulation(config()).state.bodyTriangleIndices[a]
        return (idx.indices step 3).map { intArrayOf(idx[it], idx[it + 1], idx[it + 2]) }
    }

    private companion object {
        val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        val EDGES = arrayOf(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(0, 2))
    }
}
