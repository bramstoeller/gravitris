package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Test

/** Scratch measurement harness. Deleted before commit. */
class BandSnapProbe {

    @Test
    fun measure() {
        val config = SimConfig(lattice = 5, wellWidth = 10f, wellHeight = 20f)
        println("quietKineticEnergy = ${config.quietKineticEnergy}")
        println("clearThreshold     = ${config.clearThreshold}")

        val game = Simulation(config)
        game.start()
        val input = InputFrame()

        var lastActive = game.state.activePieceBody
        var maxFillSeen = 0f
        var locks = 0
        repeat(6000) {
            game.step(input)
            val a = game.state.activePieceBody
            if (a != lastActive && a < 0) {
                // The tick the piece locked: this is when beginClear() is asked.
                locks++
                val ke = game.state.kineticEnergy
                val maxFill = game.state.bandFill.max()
                if (maxFill > maxFillSeen) maxFillSeen = maxFill
                if (locks <= 25) {
                    println(
                        "lock #$locks  worldKE=%.4f  quiet=%.4f  gatePasses=%s  maxDampedFill=%.3f"
                            .format(ke, config.quietKineticEnergy, ke <= config.quietKineticEnergy, maxFill),
                    )
                }
            }
            lastActive = a
        }
        println("locks=$locks maxDampedFillEverSeen=$maxFillSeen bodies=${game.state.bodyCount}")
    }
}
