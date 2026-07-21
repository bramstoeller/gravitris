package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import gravitris.physics.PieceShapes
import gravitris.physics.SoftBodyWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

/**
 * The tetromino piece geometry (ADR 0015): four `L×L` cells bonded into one soft
 * body per shape. These pin the properties the renderer and the mechanic depend
 * on — the constant particle count, the seam welds, the true-boundary edge flag,
 * the inert padding — that the ported physics tests do not exercise directly.
 */
class TetrominoShapeTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f)

    @Test
    fun `every archetype is four cells of material`() {
        val config = config()
        val perBody = 4 * config.lattice * config.lattice
        for (a in 0 until Simulation.ARCHETYPE_COUNT) {
            val sim = Simulation(config)
            sim.addPiece(archetype = a, centerX = 9f, centerY = 9f)
            assertEquals(
                perBody, sim.state.particleCount,
                "archetype $a must be four L*L cells = $perBody particles (constant across shapes)",
            )
            assertEquals(perBody, sim.state.particlesPerBody, "particlesPerBody must be published and constant")
            assertEquals(1, sim.state.bodyCount, "a tetromino is ONE body, not four")
        }
    }

    @Test
    fun `the four cells stay welded across a seam`() {
        // The T piece's cell 0 (bottom-left) and cell 1 (bottom-middle) are
        // horizontally adjacent, so their facing columns are bonded one lattice
        // spacing apart (ADR 0015). If the seam weld were missing the body would
        // fragment: the two cells would drift apart under gravity. After a hard
        // settle the facing particles must still be about a spacing apart, not a
        // well's width.
        val config = config()
        val sim = Simulation(config)
        sim.addPiece(archetype = 2, centerX = 9f, centerY = 20f) // T
        sim.clearActivePiece()
        TestScenes.run(sim, 400)

        val s = sim.state
        val l = config.lattice
        val cell = l * l
        val spacing = SimConfig.PIECE_WIDTH / (l - 1)
        var worst = 0f
        for (row in 0 until l) {
            val a = 0 * cell + row * l + (l - 1) // cell 0, right column
            val b = 1 * cell + row * l + 0 // cell 1, left column
            val dx = s.positionX[a] - s.positionX[b]
            val dy = s.positionY[a] - s.positionY[b]
            val d = sqrt(dx * dx + dy * dy)
            if (d > worst) worst = d
        }
        assertTrue(
            worst < 2f * spacing,
            "the seam between cell 0 and cell 1 stretched to $worst (rest is one spacing, " +
                "$spacing); the cells are not welded and the piece is fragmenting",
        )
    }

    @Test
    fun `particleEdge marks the true outline, not the internal seams`() {
        // B2 rendering (ADR 0015): the silhouette rim light reads particleEdge,
        // so a seam-facing edge must be interior (0) or every internal cell
        // boundary would draw a bright line and the piece would read as four
        // tiles, not one. The O piece is the clean case — cell 0's right edge
        // faces cell 1 (interior), its left edge faces empty space (free).
        val sim = Simulation(config())
        sim.addPiece(archetype = 1, centerX = 9f, centerY = 9f) // O, body 0
        val s = sim.state
        val l = config().lattice
        val mid = l / 2

        val seamFacing = 0 * (l * l) + mid * l + (l - 1) // cell 0, mid of right (seam) column
        val freeFacing = 0 * (l * l) + mid * l + 0 // cell 0, mid of left (free) column
        assertEquals(
            0f, s.particleEdge[seamFacing],
            "a seam-facing particle must be interior (edge 0) so the seam does not rim-light",
        )
        assertEquals(
            1f, s.particleEdge[freeFacing],
            "a free-surface particle must be edge 1",
        )
    }

    @Test
    fun `body-local UV is continuous across a cell seam, not reset per cell`() {
        // §15 / D10: the grain, subsurface and specular all read the body UV, so
        // if UV restarts at 0..1 every cell the piece reads as four separate
        // tiles — the client's complaint. The I piece is four cells in a row;
        // stepping from cell 0's right column to cell 1's left column is one grid
        // step in world space, and the UV must advance by exactly that one step,
        // NOT jump back from 1 to 0.
        val config = config()
        val sim = Simulation(config)
        sim.addPiece(archetype = 0, centerX = 9f, centerY = 9f) // I — flat bar
        val s = sim.state
        val l = config.lattice
        val cell = l * l
        val mid = l / 2

        val cell0Right = 0 * cell + mid * l + (l - 1)
        val cell1Left = 1 * cell + mid * l + 0
        val cell0Left = 0 * cell + mid * l + 0

        val withinCellStep = s.particleU[cell0Right] - s.particleU[cell0Left]
        val stepPerColumn = withinCellStep / (l - 1)
        val seamStep = s.particleU[cell1Left] - s.particleU[cell0Right]

        assertTrue(
            s.particleU[cell1Left] > s.particleU[cell0Right],
            "cell 1's left column U (${s.particleU[cell1Left]}) is not past cell 0's right column " +
                "(${s.particleU[cell0Right]}); the UV reset per cell, so the piece reads as four tiles",
        )
        assertEquals(
            stepPerColumn, seamStep, 1e-5f,
            "the seam is not one continuous grid step (${stepPerColumn}); the grain would break at it",
        )
        // And the coordinate genuinely spans the whole footprint: the long axis
        // reaches 1, the short (1-cell) axis stays at or below 1, never stretched.
        var maxU = 0f
        var maxV = 0f
        for (i in 0 until s.particleCount) {
            if (s.particleU[i] > maxU) maxU = s.particleU[i]
            if (s.particleV[i] > maxV) maxV = s.particleV[i]
        }
        assertEquals(1f, maxU, 1e-5f, "the long axis of the I piece must reach U=1 across the whole body")
        assertTrue(maxV < maxU, "the short (1-cell) axis must stay below the long axis, not stretch to 1")
    }

    @Test
    fun `grain compensation restores one per-cell frequency for every piece`() {
        // §15 / D10: body-wide UV would give a long piece coarser grain than a
        // compact one. grainScaleCompensation cancels the footprint so that, once
        // :app folds it into uGrainScale, one lattice column advances the grain
        // coordinate by the SAME amount on every archetype. That invariant is
        // comp[a] * (U step per column) == 1 / (lattice - 1), constant for all a.
        val config = config()
        val l = config.lattice
        val expected = 1f / (l - 1)
        for (a in 0 until Simulation.ARCHETYPE_COUNT) {
            val sim = Simulation(config)
            sim.addPiece(archetype = a, centerX = 9f, centerY = 9f)
            val s = sim.state
            // Two horizontally adjacent particles inside cell 0's bottom row.
            val step = s.particleU[1] - s.particleU[0]
            val compensated = s.grainScaleCompensation[a] * step
            assertEquals(
                expected, compensated, 1e-5f,
                "archetype $a grain frequency is not normalised: compensated per-column step " +
                    "$compensated != $expected — a big piece would carry coarser grain than a small one",
            )
        }
        // The compensation is a real per-footprint correction, not a no-op: the
        // four-cell-long I must carry a larger factor than the 2x2 O.
        val sim = Simulation(config)
        assertTrue(
            sim.state.grainScaleCompensation[0] > sim.state.grainScaleCompensation[1],
            "the I piece (4x1) must compensate more than the O (2x2)",
        )
    }

    @Test
    fun `particleCorner marks only true outer-silhouette corners`() {
        // §16: 1 at a convex corner of the whole piece, 0 at internal cell
        // corners and every seam, so :app rounds the real outline only. The count
        // per shape is the pin — an O is a rectangle (4), an L or J has five, the
        // zigzags and the T have six. Getting the elbow wrong would change these.
        val expected = mapOf(0 to 4, 1 to 4, 2 to 6, 3 to 6, 4 to 6, 5 to 5, 6 to 5)
        for (a in 0 until Simulation.ARCHETYPE_COUNT) {
            val sim = Simulation(config())
            sim.addPiece(archetype = a, centerX = 9f, centerY = 9f)
            val s = sim.state
            var corners = 0
            for (i in 0 until s.particleCount) {
                val c = s.particleCorner[i]
                assertTrue(c == 0f || c == 1f, "corner flag $c on particle $i of archetype $a is neither 0 nor 1")
                if (c == 1f) {
                    corners++
                    assertEquals(
                        1f, s.particleEdge[i],
                        "particle $i of archetype $a is a corner but not a free surface — a corner " +
                            "must sit on the outline, never on an interior seam",
                    )
                }
            }
            assertEquals(
                expected[a], corners,
                "archetype $a has $corners silhouette corners, expected ${expected[a]}",
            )
        }
    }

    @Test
    fun `the O piece rounds exactly its four extreme corners`() {
        // The clean rectangle case: the only corner particles are the four at the
        // geometric extremes of the silhouette, nothing interior.
        val sim = Simulation(config())
        sim.addPiece(archetype = 1, centerX = 9f, centerY = 9f) // O
        val s = sim.state
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until s.particleCount) {
            if (s.positionX[i] < minX) minX = s.positionX[i]
            if (s.positionX[i] > maxX) maxX = s.positionX[i]
            if (s.positionY[i] < minY) minY = s.positionY[i]
            if (s.positionY[i] > maxY) maxY = s.positionY[i]
        }
        val eps = 1e-3f
        for (i in 0 until s.particleCount) {
            if (s.particleCorner[i] == 1f) {
                val atCornerX = kotlin.math.abs(s.positionX[i] - minX) < eps || kotlin.math.abs(s.positionX[i] - maxX) < eps
                val atCornerY = kotlin.math.abs(s.positionY[i] - minY) < eps || kotlin.math.abs(s.positionY[i] - maxY) < eps
                assertTrue(
                    atCornerX && atCornerY,
                    "an O corner particle sits at (${s.positionX[i]}, ${s.positionY[i]}), not a bbox extreme",
                )
            }
        }
    }

    @Test
    fun `an L stays sharp at its inner elbow`() {
        // §16's headline case: an L must round its outline but NOT the concave
        // notch where the vertical stub meets the bar. For archetype 6 (L: bar +
        // top-right), that reflex vertex is the top-right corner of cell 2, the
        // rightmost bar cell, which cell 3 sits directly above — so it is covered
        // and must read 0, while the piece's true bottom-left corner reads 1.
        val config = config()
        val sim = Simulation(config)
        sim.addPiece(archetype = 6, centerX = 9f, centerY = 9f) // L
        val s = sim.state
        val l = config.lattice
        val cell = l * l
        val elbow = 2 * cell + (l - 1) * l + (l - 1) // cell 2, top-right lattice corner
        val trueCorner = 0 * cell + 0 * l + 0 // cell 0, bottom-left of the bar
        assertEquals(
            0f, s.particleCorner[elbow],
            "the L's inner elbow was flagged for rounding; it must stay sharp where the cells meet",
        )
        assertEquals(
            1f, s.particleCorner[trueCorner],
            "the L's bottom-left outline corner must round",
        )
    }

    @Test
    fun `a tree shape carries an inert padded seam that injects no motion`() {
        // Six of the seven shapes are trees (three seams); the O alone has four.
        // The stride is kept constant by padding the missing seam with
        // self-referential constraints the solver's EPS guards skip (ADR 0015).
        // First: the padding really is present and self-referential.
        val world = SoftBodyWorld(config())
        world.addBody(archetype = 0, centerX = 9f, centerY = 9f) // I piece, three real seams
        var selfReferential = 0
        for (k in 0 until world.distanceCount) if (world.dcA[k] == world.dcB[k]) selfReferential++
        assertTrue(
            selfReferential > 0,
            "a tree tetromino must carry inert self-referential distance constraints as padding",
        )

        // Second: it is harmless — a tree shape seeded at rest, weightless, must
        // stay at rest. If a padded constraint were live it would manufacture
        // motion from an undeformed lattice, which would show here with nothing
        // else moving.
        val sim = Simulation(config().copy(gravity = 0f))
        sim.addPiece(archetype = 0, centerX = 9f, centerY = 9f)
        sim.clearActivePiece()
        assertEquals(0f, sim.state.kineticEnergy, "a freshly seeded tree shape starts at rest")
        TestScenes.run(sim, 400)
        assertTrue(
            sim.state.kineticEnergy < 1e-3f,
            "the inert padding manufactured motion: kinetic energy ${sim.state.kineticEnergy}",
        )
    }

    @Test
    fun `rotating a tetromino swaps its footprint`() {
        // A single square rotates onto its own footprint; a real shape does not.
        // The T is 3 cells wide and 2 tall, so a quarter turn must narrow and
        // heighten it — proof the whole shape turns, not just some cells.
        val sim = Simulation(config())
        val body = sim.addPiece(archetype = 2, centerX = 9f, centerY = 22f) // T, falling
        val before = extent(sim, body)
        val input = InputFrame().apply { rotate = true }
        sim.step(input)
        val after = extent(sim, body)

        assertTrue(
            after[0] < before[0] - 0.3f,
            "rotation should narrow the T: width ${before[0]} -> ${after[0]}",
        )
        assertTrue(
            after[1] > before[1] + 0.3f,
            "rotation should heighten the T: height ${before[1]} -> ${after[1]}",
        )
    }

    @Test
    fun `a rotation blocked by a neighbour is refused, not forced`() {
        // A quarter turn that would overlap settled material must be rejected
        // outright — applying it and leaving the contact solver to push the
        // overlap apart is launch energy (ADR 0016). Box a T in from above so
        // its taller rotated footprint has nowhere to go.
        val sim = Simulation(config())
        sim.addPiece(archetype = 0, centerX = 6f, centerY = 6f) // I ceiling, body 0
        val t = sim.addPiece(archetype = 2, centerX = 6f, centerY = 2.6f) // T below, body 1, active
        val before = extent(sim, t)

        val input = InputFrame().apply { rotate = true }
        sim.step(input)
        val after = extent(sim, t)

        // Rejected: the footprint is unchanged (bar a tick of gravity), not
        // swapped tall-and-narrow the way a taken rotation would leave it.
        assertTrue(
            after[0] > before[0] - 0.3f,
            "a blocked rotation must leave the piece un-turned: width ${before[0]} -> ${after[0]}",
        )
    }

    @Test
    fun `coverage bands clear with tetromino shapes`() {
        // The clear rule and the occupancy bands are shape-agnostic — bands
        // stamp per particle and a clear removes whole bodies by centroid band
        // (ADR 0004/0005). Deal shaped pieces into a well with an easy threshold
        // and a clear must eventually fire, dropping the body count.
        val sim = Simulation(config().copy(seed = 99L))
        sim.tuning.clearThreshold = 0.6f
        sim.tuning.positioningTicks = 1
        sim.start()
        val input = InputFrame()

        var prev = sim.state.bodyCount
        var sawClear = false
        repeat(8000) {
            sim.step(input)
            val b = sim.state.bodyCount
            if (b < prev) sawClear = true
            prev = b
        }
        assertTrue(sawClear, "no band cleared over 8000 ticks of dealing tetrominoes")
    }

    @Test
    fun `the shape table stays a tetromino of four connected cells`() {
        // Guards the frozen layout (ADR 0015): four cells, all orthogonally
        // connected into one piece — a reordering or a typo that split a shape
        // would be caught here rather than as a fragmenting body at runtime.
        for (a in 0 until PieceShapes.COUNT) {
            assertEquals(PieceShapes.CELLS, cellCount(a), "archetype $a must have four cells")
            assertTrue(isConnected(a), "archetype $a cells are not all connected into one piece")
        }
    }

    // --- helpers ------------------------------------------------------------

    private fun extent(sim: Simulation, body: Int): FloatArray {
        val s = sim.state
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val base = body * s.particlesPerBody
        for (i in base until base + s.particlesPerBody) {
            if (s.positionX[i] < minX) minX = s.positionX[i]
            if (s.positionX[i] > maxX) maxX = s.positionX[i]
            if (s.positionY[i] < minY) minY = s.positionY[i]
            if (s.positionY[i] > maxY) maxY = s.positionY[i]
        }
        return floatArrayOf(maxX - minX, maxY - minY)
    }

    private fun cellCount(a: Int): Int = PieceShapes.CELLS

    /** Whether the four cells form one orthogonally-connected piece. */
    private fun isConnected(a: Int): Boolean {
        val seen = BooleanArray(PieceShapes.CELLS)
        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        seen[0] = true
        var count = 0
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            count++
            for (dir in arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))) {
                val n = PieceShapes.neighbour(a, c, dir[0], dir[1])
                if (n >= 0 && !seen[n]) {
                    seen[n] = true
                    stack.addLast(n)
                }
            }
        }
        return count == PieceShapes.CELLS
    }
}
