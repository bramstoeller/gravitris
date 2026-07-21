package gravitris.app.gl

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shell's index-buffer assembly (ADR 0018).
 *
 * A malformed index buffer is a black screen or a missing body on the client's
 * phone, not a wrong picture this container can notice — so these stand in for
 * the visual check that cannot run here. The topology itself (the seam bridges,
 * the match to the solver's area constraints, winding) is the core's and is
 * pinned by `:core-sim`'s `SeamlessTopologyTest`; what `:app` owns and what these
 * pin is the concatenation: each live body's whole-piece indices, in order,
 * offset by a whole body of particles so no body references another's vertices.
 */
class BodyIndexAssemblyTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 20f, wellHeight = 40f)

    /**
     * Drop each archetype in turn down the middle and let it settle, exactly as
     * `RenderFootprintTest` does — a fresh piece cannot be seeded overlapping the
     * one still at the spawn row (the solver rejects it), so the stack has to be
     * grown, not stamped. Body order is the add order, so `bodyArchetype[b]` is
     * the b-th archetype passed here.
     */
    private fun simWith(vararg archetypes: Int): Simulation {
        val sim = Simulation(config())
        for (a in archetypes) {
            sim.addPiece(a, 10f, 38f)
            sim.clearActivePiece()
            repeat(90) { sim.step(InputFrame()) }
        }
        repeat(120) { sim.step(InputFrame()) }
        return sim
    }

    @Test
    fun `assembly is each body's per-archetype indices offset by a whole body`() {
        // Deliberately a mix of archetypes, including two of the same, so the
        // per-archetype lookup and the per-body offset are both exercised.
        val state = simWith(0, 1, 0, 4, 6).state
        val ppb = state.particlesPerBody

        val out = ShortArray(BodyIndexAssembly.capacityShorts(state, state.bodyCount))
        val count = BodyIndexAssembly.assemble(state, out)

        var cursor = 0
        for (b in 0 until state.bodyCount) {
            val expected = state.bodyTriangleIndices[state.bodyArchetype[b]]
            val offset = b * ppb
            for (v in expected) {
                assertEquals(
                    (v + offset).toShort(),
                    out[cursor],
                    "body $b index $cursor is not its archetype's pattern at offset $offset",
                )
                cursor++
            }
        }
        assertEquals(cursor, count, "assemble() reported a different length than it wrote")
    }

    @Test
    fun `every assembled index stays inside its own body`() {
        val state = simWith(2, 3, 5, 6).state
        val ppb = state.particlesPerBody
        val out = ShortArray(BodyIndexAssembly.capacityShorts(state, state.bodyCount))
        val count = BodyIndexAssembly.assemble(state, out)

        // Walk the jagged per-archetype lengths to know which body each run of
        // indices belongs to — the whole point is that a body never references
        // another body's vertices, which would smear two pieces into one mesh.
        var cursor = 0
        for (b in 0 until state.bodyCount) {
            val len = state.bodyTriangleIndices[state.bodyArchetype[b]].size
            val low = b * ppb
            val high = low + ppb
            for (k in 0 until len) {
                val index = out[cursor + k].toInt() and 0xFFFF
                assertTrue(
                    index in low until high,
                    "body $b index $index escaped its vertex range [$low, $high)",
                )
            }
            cursor += len
        }
        assertEquals(cursor, count)
    }

    @Test
    fun `capacity is never exceeded by a full well of the worst-case shape`() {
        // capacityShorts sizes the GL buffer once, up front, for the worst case;
        // if a real full well ever wrote more than that, the IBO would overflow
        // on a device this container cannot debug. The O has the most seams (four)
        // and so the longest per-archetype run, so an O-only well is that worst
        // case — pack one until the solver refuses another piece, then assemble.
        val sim = Simulation(config())
        val capacity = sim.state.let { s ->
            BodyIndexAssembly.capacityShorts(s, s.particleCapacity / s.particlesPerBody)
        }

        var added = 0
        while (added < 200) {
            try {
                sim.addPiece(1, 10f, 38f) // archetype 1 = O
            } catch (_: IllegalStateException) {
                break // well is full: capacity exhausted or the spawn row is blocked
            }
            sim.clearActivePiece()
            repeat(90) { sim.step(InputFrame()) }
            added++
        }
        // Settle, then a couple more attempts to make sure we truly saturated.
        repeat(240) { sim.step(InputFrame()) }
        assertTrue(added >= 3, "the well never accepted a meaningful stack ($added pieces)")

        val state = sim.state
        val out = ShortArray(capacity)
        val count = BodyIndexAssembly.assemble(state, out)
        assertTrue(count > 0, "no indices assembled; the test proves nothing")
        assertTrue(
            count <= capacity,
            "assembled $count indices ($added O pieces) into a buffer sized for $capacity — " +
                "the IBO would overflow on the device",
        )
    }
}
