package gravitris.app.gl

import gravitris.app.Tunables
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The shell's triangle topology must be the core's, index for index.
 *
 * `:app` derives the pattern from the lattice size because the index buffer is
 * built in `onSurfaceCreated`, before a `Simulation` exists to ask. That leaves
 * two definitions of one thing. This closes it.
 *
 * Merely covering the same cells is not enough. The solver defines two area
 * constraints per lattice cell on two *specific* triangles, and
 * `particleCompression` — the one shading input this build carries — is the
 * ratio of their current to rest areas. Split a cell along the other diagonal
 * and the renderer draws a quadrilateral whose halves are not the halves the
 * solver measured, so the darkening would describe geometry that is not on
 * screen. That is precisely the class of error the architect warned reads as a
 * physics bug.
 */
class TopologyMatchesSolverTest {

    @ParameterizedTest
    @ValueSource(ints = [4, 5, 6])
    fun `the shell reproduces the core's triangle indices exactly`(lattice: Int) {
        val simulation = Simulation(
            SimConfig(lattice = lattice, wellWidth = 12f, wellHeight = 24f)
        )
        val fromCore = simulation.state.triangleIndices

        // Body 0's pattern, which is what the core publishes.
        val fromShell = LatticeTopology.buildIndices(maxBodies = 1, lattice = lattice)

        assertEquals(
            fromCore.size, fromShell.size,
            "the two modules disagree on how many indices a body has",
        )
        assertArrayEquals(
            fromCore,
            IntArray(fromShell.size) { fromShell[it].toInt() },
            "the shell's triangle topology has diverged from the solver's",
        )
    }

    @Test
    fun `the shipped lattice offsets each body by a whole body of particles`() {
        val lattice = Tunables.TOY_LATTICE
        val particlesPerBody = lattice * lattice
        val perBody = LatticeTopology.indicesPerBody(lattice)
        val indices = LatticeTopology.buildIndices(maxBodies = 3, lattice = lattice)

        for (body in 0 until 3) {
            for (k in 0 until perBody) {
                assertEquals(
                    indices[k].toInt() + body * particlesPerBody,
                    indices[body * perBody + k].toInt(),
                    "body $body index $k is not body 0's pattern at the right offset",
                )
            }
        }
    }
}
