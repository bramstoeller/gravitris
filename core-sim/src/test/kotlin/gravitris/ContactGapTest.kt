package gravitris

import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Bodies in a settled pile touch. The gap the client sees is drawn, not
 * simulated.**
 *
 * The client reported a persistent visible margin around every block, and the
 * concern was serious: the coverage-band mechanic (ADR 0004) rewards packing
 * material tightly, so a permanent physical separation would cap achievable
 * coverage below 100% by construction and undermine the mechanic before it is
 * built. Two candidates with opposite fixes — a contact radius holding bodies
 * apart, or a mesh drawn inset from the collision boundary.
 *
 * This file is the measurement that decided it, kept as a regression guard.
 * Settled surface gaps came out at -6e-7 to -1.9e-4 world units across every
 * quality tier: zero to within float noise. The bodies are in contact. The
 * renderer draws each one a particle radius small on every side, so two
 * touching bodies show `2 * particleRadius` of background between them.
 *
 * ## What these assertions are defending
 *
 * The tempting fix for a visible gap is to shrink `particleRadius` until the
 * drawn shapes meet. That would be wrong twice over: it is the *physics*
 * quantity, so shrinking it makes a body's own particles stop touching each
 * other at rest (the body becomes a bag of separated dots), and it changes the
 * broadphase cell size and therefore stability. These tests fail if anyone
 * takes that route, and they say why here so the next person does not have to
 * re-derive the argument.
 */
class ContactGapTest {

    /** Drops pieces down the middle of the well, exactly as the squish toy does. */
    private fun dropStack(config: SimConfig, count: Int): Simulation {
        val sim = Simulation(config)
        repeat(count) {
            sim.addPiece(
                archetype = it % Simulation.ARCHETYPE_COUNT,
                centerX = config.wellWidth * 0.5f,
                centerY = config.wellHeight - 1.5f,
            )
            sim.clearActivePiece()
            TestScenes.run(sim, 90)
        }
        // Long settle: the claim is about a pile at rest, not mid-collision.
        TestScenes.run(sim, 240)
        return sim
    }

    /** Smallest centre-to-centre distance between particles of different bodies. */
    private fun nearestInterBodyDistance(s: SimState): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until s.particleCount) {
            for (j in i + 1 until s.particleCount) {
                if (s.particleBody[i] == s.particleBody[j]) continue
                val dx = s.positionX[i] - s.positionX[j]
                val dy = s.positionY[i] - s.positionY[j]
                val d = sqrt(dx * dx + dy * dy)
                if (d < best) best = d
            }
        }
        return best
    }

    /**
     * The published radius is the one the solver actually uses. The renderer
     * expands its outline by this number, so a copy that drifts from the
     * solver's value silently reintroduces the gap — or overdraws past it.
     */
    @Test
    fun publishedRadiusIsHalfTheLatticeSpacing() {
        for (lattice in 4..6) {
            val state = Simulation(SimConfig(lattice = lattice)).state
            val expected = 0.5f * (SimConfig.PIECE_WIDTH / (lattice - 1))
            assertEquals(
                expected,
                state.particleRadius,
                1e-7f,
                "lattice $lattice: published particleRadius must be half the lattice spacing",
            )
        }
    }

    /**
     * The load-bearing measurement. Surfaces meet; centres stay one diameter
     * apart because that *is* the surfaces meeting.
     */
    @Test
    fun settledBodiesTouchWithNoPhysicalGap() {
        for (lattice in 4..6) {
            val config = SimConfig(lattice = lattice)
            val state = dropStack(config, 4).state
            val diameter = 2f * state.particleRadius
            val surfaceGap = nearestInterBodyDistance(state) - diameter

            // A hair of penetration is contact being resolved and is expected;
            // a positive gap of any consequence is bodies genuinely held apart.
            // The bound is 1% of a diameter — two orders of magnitude tighter
            // than the 2*radius the renderer was showing.
            assertTrue(
                abs(surfaceGap) < 0.01f * diameter,
                "lattice $lattice: settled bodies must touch, but the nearest " +
                    "inter-body surface gap was $surfaceGap world units " +
                    "(a particle diameter is $diameter)",
            )
        }
    }

    /**
     * The same claim against the floor, which is where the margin is most
     * obvious on screen: a resting body's lowest particle centres sit exactly
     * one radius up, so its *surface* is on the floor and its drawn outline
     * hovers a radius above it.
     */
    @Test
    fun settledBodiesRestOnTheFloorSurface() {
        for (lattice in 4..6) {
            val config = SimConfig(lattice = lattice)
            val state = dropStack(config, 4).state
            var lowest = Float.MAX_VALUE
            for (i in 0 until state.particleCount) {
                if (state.positionY[i] < lowest) lowest = state.positionY[i]
            }
            assertEquals(
                state.particleRadius,
                lowest,
                1e-3f,
                "lattice $lattice: a body resting on the floor must have its lowest " +
                    "particle centres one radius above y=0, so its surface is on the floor",
            )
        }
    }
}
