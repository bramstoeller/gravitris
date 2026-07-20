package gravitris.app.perf

import gravitris.game.InputFrame
import gravitris.game.Simulation

/**
 * The on-device solver benchmark — the hidden one-tap action ADR 0009 asks for.
 *
 * This is the measurement the project's largest open unknown closes on.
 * `.team/blockers.md` records that on-device frame timing cannot be measured in
 * the build container (no device, no emulator with meaningful GPU access), and
 * ADR 0009 says the blocker "closes at Milestone 1 by running the reference
 * configuration on the client's Fairphone 6 and dividing by the host number in
 * `/work/spike/solver-budget/results-host.txt`. The benchmark should ship in the
 * debug build as a hidden one-tap action so this costs nobody any effort."
 *
 * ## What it measures, and what it does not
 *
 * **Solver CPU only.** No rendering, no buffer fill, no submission, no GPU. It
 * steps a simulation in a loop and times the steps. That is deliberate and it
 * is what the derating ratio is *for*: ADR 0009's 3–7x estimate is a CPU
 * estimate, and every budget in ADR 0001 is built on the same reference
 * workload. Mixing rendering into it would produce a number that cannot be
 * divided by the host figure, because the host never rendered anything.
 *
 * The fragment-shader cost — the other large unknown — is not measured here and
 * cannot be. That one is read off the live frame-time readout instead, and the
 * two numbers answer different questions.
 *
 * ## Why the scene comes from `:core-sim`
 *
 * [Simulation.buildBenchmarkScene] is shared with the JVM benchmark
 * specifically so the two cannot drift and produce a ratio that compares
 * different scenes. It reproduces ADR 0001's measured workload exactly: 60
 * bodies at lattice 4, 960 particles, 3 600 constraints, 8 substeps,
 * compliance 1e-6.
 *
 * ## Why the warm-up is as long as it is
 *
 * The host protocol settles and JIT-warms for 900 frames before timing, and
 * this matches it — a ratio between a warm host and a cold device would
 * measure ART's JIT rather than the hardware. On a phone that argument is
 * stronger than on the host, not weaker: the interpreter-to-JIT gap on ART is
 * large enough to swamp the derating factor entirely.
 *
 * The scene is also *settling* during those frames. A freshly seeded stack is
 * falling, which is a different contact workload from a settled one, and the
 * host number was taken on a settled stack.
 */
object SolverBenchmark {

    /**
     * Median frame time for this exact benchmark on the build host, measured
     * 2026-07-20 by running the shipped protocol three times: 0.4443, 0.4443,
     * 0.4443 ms — repeatable to within 0.1%.
     *
     * **This is the divisor, and it is deliberately not the spike's 0.497 ms.**
     * ADR 0009 and `.team/blockers.md` both say to divide by the figure in
     * `/work/spike/solver-budget/results-host.txt`, and that instruction was
     * written before `:core-sim` existed. That 0.497 ms was measured on the
     * *spike's* solver — a different implementation, with different friction
     * and contact ordering. The production solver runs the same scene about 11%
     * faster on the same host.
     *
     * Dividing a device figure by 0.497 would therefore fold an 11%
     * implementation difference into what is supposed to be a pure
     * hardware-derating number, and would understate the derating by that much.
     * Same code, different hardware is the only comparison that means anything,
     * so the divisor is this solver's own host figure. [SPIKE_HOST_P50_MS] is
     * kept so anyone holding ADR 0009 can still reconcile the two.
     */
    const val HOST_P50_MS: Float = 0.4443f

    /** The spike's figure for the same scene on a different solver, kept for
     *  continuity with ADR 0009's text. Not the divisor — see [HOST_P50_MS]. */
    const val SPIKE_HOST_P50_MS: Float = 0.497f

    /** Settle plus JIT warm-up, matching the host protocol's 900 frames. */
    const val WARMUP_FRAMES: Int = 900

    /** Timed frames. Enough for a stable p95 without freezing the screen for
     *  longer than the client will tolerate on a single tap. */
    const val MEASURED_FRAMES: Int = 600

    /**
     * @param p50Ms median frame time — the figure to divide by [HOST_P50_MS].
     * @param maxMs the worst single frame, kept because a solver that is fast
     *   on average and occasionally slow is a solver that drops frames, and an
     *   average alone would hide it.
     */
    class Result(
        val p50Ms: Float,
        val p95Ms: Float,
        val meanMs: Float,
        val maxMs: Float,
        val particles: Int,
        val bodies: Int,
        val frames: Int,
    ) {
        /**
         * How much slower this device is than the build host, on the median
         * frame, with the same solver on both sides.
         *
         * This is the number the project's largest unknown closes on. ADR 0009
         * estimates 3–7x and says plainly that the band is uncomfortably wide —
         * "the low end and high end differ by more than a factor of two, which
         * is the difference between comfortable and tight at the high tier".
         */
        val deratingVsHost: Float get() = p50Ms / HOST_P50_MS

        /** Share of a 60Hz frame the solver alone consumes at this workload. */
        val budgetFraction: Float get() = p50Ms / FRAME_BUDGET_MS
    }

    /**
     * Run the benchmark. Blocking, and expected to take a few seconds.
     *
     * Called on the GL thread so it competes with nothing and runs at the same
     * thread priority the real solver does. The screen stops updating while it
     * runs, which is why the caller shows a running state first.
     */
    fun run(
        warmupFrames: Int = WARMUP_FRAMES,
        measuredFrames: Int = MEASURED_FRAMES,
    ): Result {
        val simulation = Simulation.buildBenchmarkScene()

        // A single frame, reused. The core does not clear the one-shot flags
        // (mutating the caller's frame would break replay determinism), and
        // there are none set here — no input is applied, because the host
        // reference applied none either.
        val input = InputFrame()

        repeat(warmupFrames) { simulation.step(input) }

        val samples = LongArray(measuredFrames)
        for (frame in 0 until measuredFrames) {
            val start = System.nanoTime()
            simulation.step(input)
            samples[frame] = System.nanoTime() - start
        }

        samples.sort()
        var total = 0L
        for (sample in samples) total += sample

        return Result(
            p50Ms = percentileMs(samples, 0.50f),
            p95Ms = percentileMs(samples, 0.95f),
            meanMs = total / measuredFrames.toFloat() / NANOS_PER_MS,
            maxMs = samples[measuredFrames - 1] / NANOS_PER_MS,
            particles = simulation.state.particleCount,
            bodies = simulation.state.bodyCount,
            frames = measuredFrames,
        )
    }

    /** @param sorted ascending frame times in nanoseconds. */
    private fun percentileMs(sorted: LongArray, fraction: Float): Float {
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index] / NANOS_PER_MS
    }

    private const val NANOS_PER_MS = 1_000_000f

    /** 60Hz. ADR 0006 protects this budget; ADR 0009's tiers exist to fit it. */
    private const val FRAME_BUDGET_MS = 1000f / 60f
}
