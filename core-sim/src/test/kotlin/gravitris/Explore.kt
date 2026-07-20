package gravitris

import gravitris.game.InputFrame
import gravitris.game.Phase
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Test

/** Scratch measurement harness. Deleted before commit. */
class Explore {

    @Test
    fun measure() {
        val config = SimConfig(lattice = 5, wellWidth = 10f, wellHeight = 20f)

        // 1. Band fill of a settled pile.
        val pile = TestScenes.pile(config, bodies = 12)
        TestScenes.run(pile, 400)
        println("=== settled 12-body pile ===")
        println("KE=${pile.state.kineticEnergy}")
        println(pile.state.bandFill.take(12).joinToString { "%.3f".format(it) })

        // 2. A well packed as full as seeding allows.
        val packed = TestScenes.pile(config, bodies = 24)
        TestScenes.run(packed, 600)
        println("=== settled 24-body pile ===")
        println("KE=${packed.state.kineticEnergy}")
        println(packed.state.bandFill.take(16).joinToString { "%.3f".format(it) })
        println("max fill=${packed.state.bandFill.max()}")

        // 3. Does a running game spawn, lock and clear?
        val game = Simulation(config)
        game.start()
        val input = InputFrame()
        var locks = 0
        var lastActive = game.state.activePieceBody
        var clears = 0
        var wasClearing = false
        repeat(6000) {
            game.step(input)
            val a = game.state.activePieceBody
            if (a != lastActive && a >= 0) locks++
            lastActive = a
            val clearing = game.state.phase is Phase.Clearing
            if (clearing && !wasClearing) {
                clears++
                println("clear at tick ${game.state.tick} bands=" +
                    (game.state.phase as Phase.Clearing).bands.joinToString())
                println("  fills=" + game.state.bandFill.take(10)
                    .joinToString { "%.2f".format(it) })
            }
            wasClearing = clearing
        }
        println("=== 6000 ticks of play ===")
        println("spawns=$locks bodies=${game.state.bodyCount} clears=$clears")
        println("KE=${game.state.kineticEnergy}")
        println("fills=" + game.state.bandFill.take(14).joinToString { "%.2f".format(it) })
    }
}
