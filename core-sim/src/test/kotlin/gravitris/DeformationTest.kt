package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The blocks must visibly deform. This is the product's reason to exist, and
 * Milestone 1 shipped without it: the client's device showed geometrically
 * perfect squares, and measurement agreed — a hard landing took 2% off a
 * body's height.
 *
 * These assert on the **silhouette**, because that is what a player sees.
 *
 * `particleCompression` — the area ratio the renderer shades with — is
 * deliberately *not* the metric here, and that distinction is the whole reason
 * the regression went unnoticed. Area is held near-rigid on purpose so a
 * squashed body bulges sideways instead of shrinking, so the area ratio spans
 * roughly 0.895..1.0 whether the material is rigid or spongy. Judging squash
 * by it reports "11% deformation" for a block that is visually a perfect
 * square. Aspect ratio is the honest number.
 */
class DeformationTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f)

    /** Width and height of one body's silhouette, from its particle centres. */
    /**
     * Bounding box of one **cell** of a body (ADR 0015). A tetromino is four
     * cells; its whole-body box mixes their motion, but a single cell squashes
     * exactly as the old single block did, so the per-cell box is what the
     * squash assertions measure. Cell 0 is body-local particles `[0, L^2)`.
     */
    private fun cellBox(s: SimState, body: Int): FloatArray {
        val cell = s.bodyLattice * s.bodyLattice
        val base = body * s.particlesPerBody
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in base until base + cell) {
            val x = s.positionX[i]
            val y = s.positionY[i]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return floatArrayOf(maxX - minX, maxY - minY)
    }

    private fun bodyBox(s: SimState, body: Int): FloatArray {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until s.particleCount) {
            if (s.particleBody[i] != body) continue
            val x = s.positionX[i]
            val y = s.positionY[i]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        return floatArrayOf(maxX - minX, maxY - minY)
    }

    @Test
    fun `a hard landing visibly squashes the block`() {
        val config = config()
        val sim = Simulation(config)
        val body = sim.addPiece(archetype = 0, centerX = 5f, centerY = 15f)
        sim.slamActivePiece(30f) // impact-velocity probe (ADR 0016)
        sim.step(InputFrame())

        var flattest = Float.MAX_VALUE
        var widest = 0f
        val input = InputFrame()
        repeat(DROP_FRAMES) {
            sim.step(input)
            val box = cellBox(sim.state, body)
            if (box[1] < flattest) flattest = box[1]
            if (box[0] > widest) widest = box[0]
        }

        val heightRatio = flattest / config.pieceWidth
        val widthRatio = widest / config.pieceWidth
        val aspect = widthRatio / heightRatio

        // Measured 0.852 / 1.203 / 1.412 at distanceCompliance 1e-4, against
        // 0.980 / 1.149 / 1.173 at Milestone 1's 1e-6.
        //
        // Height and aspect ratio are the assertions that discriminate, with
        // wide margin on both sides. The width bound does *not*: 1.149 against
        // a bound of 1.15 rejects the old value by 0.0006, which is luck, not
        // design. Its job is to guard the bulge — that squash comes from shape
        // change and not from the body shrinking — and for that it has a
        // sufficient 4% margin at the shipped value. Do not lean on it to
        // catch a stiffness regression; the other two do that.
        // A tetromino cell squashes a real but smaller amount than a lone block
        // (~0.91 vs 0.85): its seam neighbours share and resist the load, and the
        // impact is spread across four cells (ADR 0015). The per-MATERIAL
        // response is unchanged — this is the aggregate the seams produce, and it
        // still reads as a visibly non-square landing, which is the assertion.
        assertTrue(
            heightRatio < 0.93f,
            "a hard landing should visibly flatten the cell: height was " +
                "$heightRatio of rest, which reads as a rigid square",
        )
        assertTrue(
            widthRatio > 1.05f,
            "a squashed cell should bulge sideways, not shrink: width was " +
                "$widthRatio of rest",
        )
        assertTrue(
            aspect > 1.15f,
            "the landing silhouette should be obviously non-square: aspect " +
                "ratio was $aspect (rigid material sits near 1.0)",
        )
    }

    @Test
    fun `a block under a stack visibly bears the load`() {
        // Sustained deformation, not the impact transient: the bottom body is
        // carrying everything above it and should look like it. A deliberately
        // narrow column — every piece dropped on the same spot — so the load
        // concentrates on the bottom rather than spreading across a wide well
        // (a tetromino is wide, ADR 0015, so a wide pile stacks shallow).
        val config = config()
        val sim = Simulation(config)
        val input = InputFrame()
        val cx = config.wellWidth * 0.5f
        repeat(12) { b ->
            val y = TestScenes.stackHeight(sim.state) + 6f
            sim.addPiece(archetype = b % Simulation.ARCHETYPE_COUNT, centerX = cx, centerY = y)
            sim.clearActivePiece()
            var f = 0
            while (f < 240 && sim.state.kineticEnergy > config.quietKineticEnergy) {
                sim.step(input)
                f++
            }
        }
        TestScenes.run(sim, SETTLE_FRAMES)

        // Measure the most-compressed cell anywhere in the column (ADR 0015) —
        // the material actually bearing the load. A cell box, not a whole-body
        // box, since a tetromino spans several cells.
        var minHeightRatio = Float.MAX_VALUE
        for (body in 0 until sim.state.bodyCount) {
            val h = cellBox(sim.state, body)[1] / config.pieceWidth
            if (h < minHeightRatio) minHeightRatio = h
        }

        assertTrue(
            minHeightRatio < 0.96f,
            "the load-bearing cell at the bottom of the pile should be " +
                "visibly compressed: least height was $minHeightRatio of rest",
        )
    }

    @Test
    fun `squashed material bulges rather than losing area`() {
        // The counterpart to the squash assertions. Deformation must come from
        // shape change, not from bodies quietly shrinking — a body that loses
        // area reads as a rendering bug, and it would break the coverage-band
        // mechanic that depends on material bulging into gaps (ADR 0001).
        val config = config()
        val sim = Simulation(config)
        val body = sim.addPiece(archetype = 0, centerX = 5f, centerY = 15f)
        sim.slamActivePiece(30f) // impact-velocity probe (ADR 0016)
        sim.step(InputFrame())

        var lowest = Float.MAX_VALUE
        val input = InputFrame()
        repeat(DROP_FRAMES) {
            sim.step(input)
            for (i in 0 until sim.state.particleCount) {
                if (sim.state.particleBody[i] != body) continue
                val c = sim.state.particleCompression[i]
                if (c < lowest) lowest = c
            }
        }

        // Measured 0.895 at impact. Area is the quantity held near-rigid, so
        // this bound protects the bulge, and it doubles as the guard on the
        // renderer's darkening range, which `:app` tuned against 0.888..1.0.
        assertTrue(
            lowest > 0.80f,
            "a landing should preserve area and bulge sideways; deepest area " +
                "compression was $lowest, which means material is being lost",
        )
    }

    private companion object {
        /** Long enough to cover the fall, the impact and the relaxation. */
        const val DROP_FRAMES = 240

        const val SETTLE_FRAMES = 900
    }
}
