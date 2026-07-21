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
    fun `the render topology is the area constraints plus only the O junction fill`() {
        // The invariant that keeps particleCompression honest across the piece,
        // seams included: every solver area constraint (cell + seam) is a render
        // triangle, so no constrained material goes undrawn. TopologyMatchesSolver
        // guards this per cell in :app; this extends it to the seam bridges.
        //
        // The sole render triangles WITHOUT a backing area constraint are the O's
        // two centre-junction fills (ADR 0018): they close the four-cell hole for
        // the eye, and are render-only on purpose — a near-rigid area constraint
        // there rings a heavy pile past its settle line, and their corners are
        // already constrained so per-particle compression interpolates correctly.
        val cell = config().lattice * config().lattice
        for (a in 0 until PieceShapes.COUNT) {
            val world = SoftBodyWorld(config())
            world.addBody(a, 9f, 9f) // first body: base 0, so absolute == body-local

            val constraints = HashSet<List<Int>>()
            for (k in 0 until world.areaCount) {
                val tri = listOf(world.acA[k], world.acB[k], world.acC[k])
                // Inert padding is a == b == c; a real triangle never repeats.
                if (tri[0] == tri[1] && tri[1] == tri[2]) continue
                constraints.add(tri.sorted())
            }
            val renderSet = triangles(a).map { it.sorted() }.toHashSet()

            assertTrue(
                renderSet.containsAll(constraints),
                "archetype $a has area constraints with no render triangle; the mesh omits " +
                    "constrained material",
            )
            // Everything the render mesh adds beyond the constraints must be a
            // junction fill: a triangle spanning three distinct cells.
            val extra = renderSet - constraints
            for (t in extra) {
                assertEquals(
                    3, t.map { it / cell }.toSet().size,
                    "archetype $a render triangle $t is neither an area constraint nor a junction fill",
                )
            }
            assertEquals(
                if (a == 1) 2 else 0, extra.size,
                "archetype $a has ${extra.size} render-only triangles, expected only the O's junction",
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

    @Test
    fun `the O centre junction is triangulated, no hole`() {
        // The O is the only shape where four cells meet at a POINT (ADR 0018).
        // The edge-to-edge seams clip the corners of the central 2r x 2r square
        // and leave its middle open — the hole the Frontend's correct extrusion
        // exposed (handoff 0040). The junction fill closes it with the two
        // triangles on the four inner corners.
        val l = config().lattice
        val cell = l * l
        val p00 = 0 * cell + (l - 1) * l + (l - 1) // cell 0 (bottom-left) top-right
        val p10 = 1 * cell + (l - 1) * l + 0       // cell 1 (bottom-right) top-left
        val p01 = 2 * cell + 0 * l + (l - 1)       // cell 2 (top-left) bottom-right
        val p11 = 3 * cell + 0 * l + 0             // cell 3 (top-right) bottom-left

        val renderSet = triangles(1).map { it.sorted() }.toHashSet()
        assertTrue(
            listOf(p00, p10, p11).sorted() in renderSet,
            "the O junction triangle {$p00,$p10,$p11} is missing; the centre is still a hole",
        )
        assertTrue(
            listOf(p00, p11, p01).sorted() in renderSet,
            "the O junction triangle {$p00,$p11,$p01} is missing; the centre is still a hole",
        )

        // Behavioural proof, index-free: the piece's geometric centre lies inside
        // a render triangle. A per-cell mesh (or the clipped seam junction) would
        // leave it in the gap between triangles.
        val s = Simulation(config()).also { it.addPiece(1, 9f, 9f) }.state
        var cx = 0f
        var cy = 0f
        for (i in 0 until s.particleCount) { cx += s.positionX[i]; cy += s.positionY[i] }
        cx /= s.particleCount
        cy /= s.particleCount
        val covered = triangles(1).any { t ->
            pointInTriangle(cx, cy, s.positionX, s.positionY, t[0], t[1], t[2])
        }
        assertTrue(covered, "the O's geometric centre ($cx, $cy) is in no render triangle — a hole")
    }

    @Test
    fun `only the O has a four-cell junction`() {
        // A junction triangle is the only render triangle that spans THREE
        // distinct cells (a seam bridge spans two, an interior triangle one). So
        // exactly the O should have any, and exactly two of them; the other six
        // must pad the junction inert and stay seamless without a phantom centre.
        val cell = config().lattice * config().lattice
        for (a in 0 until PieceShapes.COUNT) {
            val threeCell = triangles(a).count { t ->
                setOf(t[0] / cell, t[1] / cell, t[2] / cell).size == 3
            }
            val expected = if (a == 1) 2 else 0
            assertEquals(
                expected, threeCell,
                "archetype $a has $threeCell junction (3-cell) triangles, expected $expected",
            )
        }
    }

    // --- helpers ------------------------------------------------------------

    /** The archetype's whole-piece render triangles, as `[i0, i1, i2]` triples. */
    private fun triangles(a: Int): List<IntArray> {
        val idx = Simulation(config()).state.bodyTriangleIndices[a]
        return (idx.indices step 3).map { intArrayOf(idx[it], idx[it + 1], idx[it + 2]) }
    }

    /** Point-in-triangle by consistent sign of the three edge cross products,
     *  with a small tolerance so a point on an edge (e.g. the shared diagonal)
     *  counts as covered. */
    private fun pointInTriangle(
        px: Float, py: Float, x: FloatArray, y: FloatArray, a: Int, b: Int, c: Int,
    ): Boolean {
        val d1 = cross(px, py, x[a], y[a], x[b], y[b])
        val d2 = cross(px, py, x[b], y[b], x[c], y[c])
        val d3 = cross(px, py, x[c], y[c], x[a], y[a])
        val eps = 1e-4f
        val hasNeg = d1 < -eps || d2 < -eps || d3 < -eps
        val hasPos = d1 > eps || d2 > eps || d3 > eps
        return !(hasNeg && hasPos)
    }

    private fun cross(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float =
        (ax - px) * (by - py) - (bx - px) * (ay - py)

    private companion object {
        val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        val EDGES = arrayOf(intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(0, 2))
    }
}
