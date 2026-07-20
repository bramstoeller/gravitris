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

    /** Centre-to-centre spacing used when seeding a pile. */
    fun pitchFor(config: SimConfig): Float =
        SoftBodyWorld(config).pieceExtent * Simulation.PLACEMENT_GAP

    /** Bodies packed from the floor up, left to right. */
    fun pile(config: SimConfig, bodies: Int): Simulation {
        val sim = Simulation(config)
        val pitch = pitchFor(config)
        val perRow = ((config.wellWidth - pitch) / pitch).toInt() + 1
        check(perRow >= 1) { "well is narrower than one piece" }
        for (b in 0 until bodies) {
            sim.addPiece(
                archetype = b % Simulation.ARCHETYPE_COUNT,
                centerX = pitch * 0.5f + (b % perRow) * pitch,
                centerY = pitch * 0.5f + (b / perRow) * pitch,
            )
        }
        sim.clearActivePiece()
        return sim
    }

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
