package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation
import gravitris.physics.SoftBodyWorld

/**
 * Scene construction shared by the physics tests.
 *
 * Everything here seeds bodies with a deliberate gap. The spike lost real time
 * to a pile seeded less than a particle diameter apart, where the contact
 * solver turned the overlap into launch energy and the result read as
 * non-monotonic *solver* instability — more substeps looking less stable —
 * when it was a setup bug (spike README, bug 1). Scene validity is a
 * precondition of every assertion in these tests, and `Simulation.addPiece`
 * throws rather than let an invalid one through.
 */
internal object TestScenes {

    /**
     * A centre-to-centre pitch at which two pieces of *any* shape cannot
     * overlap — twice the largest half-extent plus a diameter of slack. Used by
     * the capacity test to seed a dense grid without tripping the overlap guard.
     */
    fun pitchFor(config: SimConfig): Float {
        val geom = SoftBodyWorld(config)
        return 2f * geom.pieceMaxHalfExtent + 2f * geom.particleRadius
    }

    /**
     * A settled pile of [bodies] pieces, with the game **not** dealing — the
     * caller's own `step`s are pure physics.
     *
     * Pieces are dropped one at a time from just above the current pile top via
     * the [Simulation.addPiece] harness (not the dealer, so `running` stays
     * false), each settled before the next. This is robust to piece shape and
     * size: hand-placing a grid at a single-cell pitch would overlap the larger
     * tetrominoes (ADR 0015) and throw. The x is spread deterministically so the
     * pile is not a single tower, and the whole thing is a pure function of
     * (config, bodies), so it is as reproducible as the old grid.
     */
    fun pile(config: SimConfig, bodies: Int): Simulation {
        val geom = SoftBodyWorld(config)
        val halfExtent = geom.pieceMaxHalfExtent
        val margin = geom.pieceExtent * 0.2f
        val sim = Simulation(config)
        val input = InputFrame()

        val edgeGap = geom.pieceExtent * 0.15f
        val leftMost = halfExtent + geom.particleRadius + edgeGap
        val rightMost = config.wellWidth - halfExtent - geom.particleRadius - edgeGap
        val span = (rightMost - leftMost).coerceAtLeast(0f)
        val centre = 0.5f * config.wellWidth

        for (b in 0 until bodies) {
            // Golden-ratio spread across the usable width: even, gap-free, and a
            // pure function of the piece index.
            val frac = (b * 0.61803398875f) % 1f
            val x = if (span <= 0f) centre else leftMost + frac * span
            val y = stackHeight(sim.state) + halfExtent + margin
            sim.addPiece(archetype = b % Simulation.ARCHETYPE_COUNT, centerX = x, centerY = y)
            sim.clearActivePiece()
            var f = 0
            while (f < SETTLE_CAP && sim.state.kineticEnergy > config.quietKineticEnergy) {
                sim.step(input)
                f++
            }
        }
        return sim
    }

    private const val SETTLE_CAP = 240

    fun run(sim: Simulation, frames: Int, input: InputFrame = InputFrame()) {
        repeat(frames) { sim.step(input) }
    }

    /**
     * Bit-exact fingerprint of everything the simulation exposes.
     *
     * Raw bits, not float equality: `0.0 == -0.0` and the determinism contract
     * is bit-identical results, not approximately equal ones. Kinetic energy is
     * included because it is derived from velocities, which `SimState` does not
     * expose directly — so a velocity divergence still shows up here.
     */
    fun fingerprint(s: SimState): IntArray {
        val n = s.particleCount
        val out = IntArray(n * 7 + 1)
        var k = 0
        for (i in 0 until n) {
            out[k++] = s.positionX[i].toRawBits()
            out[k++] = s.positionY[i].toRawBits()
            out[k++] = s.prevPositionX[i].toRawBits()
            out[k++] = s.prevPositionY[i].toRawBits()
            out[k++] = s.particleCompression[i].toRawBits()
            out[k++] = s.particleContact[i].toRawBits()
            out[k++] = s.particleBody[i]
        }
        out[k] = s.kineticEnergy.toRawBits()
        return out
    }

    /** Highest particle in the scene — the top of the pile. */
    fun stackHeight(s: SimState): Float {
        var top = 0f
        for (i in 0 until s.particleCount) if (s.positionY[i] > top) top = s.positionY[i]
        return top
    }
}
