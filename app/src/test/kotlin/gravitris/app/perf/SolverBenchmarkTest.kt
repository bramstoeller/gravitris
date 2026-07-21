package gravitris.app.perf

import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The benchmark has one job and no second chance at it: the client runs it once
 * on their phone and reads a number off the screen. If the scene is wrong, the
 * ratio is wrong, and nobody looking at the photograph will be able to tell.
 *
 * These run the real thing at reduced frame counts — the shipped counts are
 * timing parameters, not correctness ones.
 */
class SolverBenchmarkTest {

    private fun quickRun() = SolverBenchmark.run(warmupFrames = 60, measuredFrames = 60)

    /**
     * The reference workload the derating ratio is built on. It is now
     * [Simulation.BENCHMARK_BODIES] tetrominoes (ADR 0015), so the particle count
     * is derived from the core's own scene rather than a literal — pinning a
     * hard-coded number here is exactly how the two modules drift apart and start
     * comparing different scenes. The scene is a whole number of four-cell bodies.
     */
    @Test
    fun `it measures the core's reference workload`() {
        val scene = Simulation.buildBenchmarkScene().state
        val result = quickRun()

        assertEquals(Simulation.BENCHMARK_BODIES, result.bodies)
        assertEquals(scene.bodyCount, result.bodies, "not the core's reference body count")
        assertEquals(scene.particleCount, result.particles, "not the core's reference scene")
        assertEquals(
            Simulation.BENCHMARK_BODIES * scene.particlesPerBody,
            result.particles,
            "the reference scene is not a whole number of four-cell tetrominoes",
        )
    }

    /**
     * The divisor and its scene must travel together. [SolverBenchmark.HOST_P50_MS]
     * was measured on a scene of exactly [SolverBenchmark.HOST_REF_PARTICLES]
     * particles; if the core's scene changes without that pairing being updated,
     * the derating ratio silently divides a new workload by an old host time. This
     * fails first, so the divisor gets re-measured instead.
     */
    @Test
    fun `the host divisor is paired with the scene it was measured on`() {
        assertEquals(
            SolverBenchmark.HOST_REF_PARTICLES,
            Simulation.buildBenchmarkScene().state.particleCount,
            "the benchmark scene changed but SolverBenchmark.HOST_P50_MS / HOST_REF_PARTICLES did " +
                "not; re-measure the host p50 on the new scene and update both",
        )
    }

    @Test
    fun `it uses the core's own benchmark scene rather than rebuilding one`() {
        // Shared construction is what stops the JVM benchmark and the device
        // benchmark drifting apart. If the core's scene changes shape, this
        // fails rather than the two quietly measuring different things.
        val fromCore = Simulation.buildBenchmarkScene().state
        val result = quickRun()

        assertEquals(fromCore.particleCount, result.particles)
        assertEquals(fromCore.bodyCount, result.bodies)
    }

    @Test
    fun `it reports plausible, ordered statistics`() {
        val result = quickRun()

        assertEquals(60, result.frames)
        assertTrue(result.p50Ms > 0f, "a median frame time of zero means the clock was not read")
        assertTrue(result.p50Ms <= result.p95Ms, "p50 ${result.p50Ms} above p95 ${result.p95Ms}")
        assertTrue(result.p95Ms <= result.maxMs, "p95 ${result.p95Ms} above max ${result.maxMs}")
        assertTrue(result.meanMs > 0f)
    }

    /**
     * The whole point of the number. On the build host this should land near
     * 1x, because this *is* the host; on the client's phone it is the derating
     * factor ADR 0009 estimates at 3-7x and calls the project's largest
     * unknown.
     */
    @Test
    fun `the derating ratio is computed against the host reference`() {
        val result = quickRun()

        assertEquals(
            result.p50Ms / SolverBenchmark.HOST_P50_MS,
            result.deratingVsHost,
            1e-6f,
        )
        assertTrue(
            SolverBenchmark.HOST_P50_MS > 0f,
            "the host reference from spike/solver-budget/results-host.txt is missing",
        )
    }

    @Test
    fun `the budget fraction is against a 60Hz frame`() {
        val result = quickRun()
        assertEquals(result.p50Ms / (1000f / 60f), result.budgetFraction, 1e-6f)
    }

    /**
     * Two runs must be comparable. A benchmark that mutated shared state, or
     * that left the scene settled differently each time, would give the client
     * a different answer on the second tap and there would be no way to tell
     * which one to believe.
     */
    @Test
    fun `repeated runs measure the same scene`() {
        val first = quickRun()
        val second = quickRun()

        assertEquals(first.particles, second.particles)
        assertEquals(first.bodies, second.bodies)
    }
}
