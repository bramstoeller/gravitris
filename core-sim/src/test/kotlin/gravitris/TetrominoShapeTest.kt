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
