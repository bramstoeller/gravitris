package gravitris

import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `:app`-facing contract points that were forbidding a `.size` read or a
 * value-range misread without publishing the number the consumer needs
 * (`docs/contracts.md`, `SimState`). These lock the published values to what
 * the contract documents, so the two cannot drift.
 */
class ContractTest {

    @Test
    fun `bandCount is published and equals both band array lengths`() {
        // A non-default count so the assertion cannot pass by coincidence with
        // the shader's baked-in default.
        val sim = Simulation(SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f, bandCount = 17))
        val s = sim.state
        assertEquals(17, s.bandCount, "bandCount must equal the configured count")
        assertEquals(s.bandCount, s.bandFill.size, "bandFill must be bandCount long")
        assertEquals(s.bandCount, s.bandClearProgress.size, "bandClearProgress must be bandCount long")
    }

    @Test
    fun `triangleIndices length is six per cell and values are particle indices`() {
        for (lattice in intArrayOf(4, 5, 6)) {
            val sim = Simulation(SimConfig(lattice = lattice, wellWidth = 12f, wellHeight = 24f))
            val tri = sim.state.triangleIndices
            val cells = (lattice - 1) * (lattice - 1)
            assertEquals(
                6 * cells, tri.size,
                "lattice $lattice: length must be 6*(lattice-1)^2, not the value range",
            )
            val particlesPerBody = lattice * lattice
            for (v in tri) {
                assertTrue(
                    v in 0 until particlesPerBody,
                    "lattice $lattice: index $v is not a body-local particle index (0 until $particlesPerBody)",
                )
            }
        }
    }

    @Test
    fun `gravity must be finite and non-positive, zero allowed`() {
        assertThrows(IllegalArgumentException::class.java) {
            SimConfig(gravity = 1f) // positive is a sign error
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimConfig(gravity = Float.NaN) // poisons every position with no other symptom
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimConfig(gravity = Float.NEGATIVE_INFINITY)
        }
        // Zero is legal — a weightless scene isolates constraint behaviour.
        SimConfig(gravity = 0f)
    }
}
