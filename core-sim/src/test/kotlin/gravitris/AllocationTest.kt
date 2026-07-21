package gravitris

import com.sun.management.ThreadMXBean
import gravitris.game.InputFrame
import gravitris.game.SimConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * ADR 0001 adopts a structure-of-arrays layout for **allocation, not cache**:
 * the measured cache win at this scale is only 1–3%, but primitive arrays let
 * the steady-state loop allocate nothing, and on ART a per-frame allocation
 * stream buys GC pauses — and a pause on a 16.67 ms budget is a dropped frame.
 *
 * That property is only true while someone checks it. The spike's broadphase
 * allocated a per-frame `IntArray` and it was caught by exactly this
 * measurement (spike README, bug 3), so the check is part of the suite rather
 * than a one-off.
 */
class AllocationTest {

    @Test
    fun `the steady-state tick allocates nothing`() {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null, "allocation measurement needs the HotSpot ThreadMXBean")
        assumeTrue(bean!!.isThreadAllocatedMemorySupported, "JVM does not report thread allocation")
        bean.isThreadAllocatedMemoryEnabled = true

        val sim = TestScenes.pile(
            SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f),
            bodies = 8,
        )
        val input = InputFrame()

        // Settle first, then warm up hard. The measurement must not include
        // class loading, JIT compilation or first-call lazy initialisation —
        // those allocate legitimately and only once.
        TestScenes.run(sim, 600)
        repeat(WARMUP_FRAMES) { sim.step(input) }

        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        repeat(MEASURED_FRAMES) { sim.step(input) }
        val after = bean.getThreadAllocatedBytes(id)

        val perFrame = (after - before).toDouble() / MEASURED_FRAMES
        assertTrue(
            perFrame <= TOLERANCE_BYTES_PER_FRAME,
            "steady-state tick allocated %.1f bytes/frame (budget %d). ADR 0001 measured 0."
                .format(perFrame, TOLERANCE_BYTES_PER_FRAME),
        )
    }

    @Test
    fun `driving input every tick allocates nothing`() {
        // Input handling is on the per-frame path too, and rotation in
        // particular does a rollback that would be the obvious place to
        // allocate a scratch array.
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null, "allocation measurement needs the HotSpot ThreadMXBean")
        assumeTrue(bean!!.isThreadAllocatedMemorySupported, "JVM does not report thread allocation")
        bean.isThreadAllocatedMemoryEnabled = true

        val sim = TestScenes.pile(
            SimConfig(lattice = 5, wellWidth = 18f, wellHeight = 30f),
            bodies = 6,
        )
        sim.addPiece(archetype = 0, centerX = 9f, centerY = TestScenes.stackHeight(sim.state) + 5f)
        val input = InputFrame()

        fun drive(tick: Int) {
            input.clear()
            input.dragX = if (tick % 2 == 0) 0.05f else -0.05f
            input.rotate = tick % 7 == 0
            sim.step(input)
        }

        repeat(WARMUP_FRAMES) { drive(it) }

        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        repeat(MEASURED_FRAMES) { drive(it) }
        val after = bean.getThreadAllocatedBytes(id)

        val perFrame = (after - before).toDouble() / MEASURED_FRAMES
        assertTrue(
            perFrame <= TOLERANCE_BYTES_PER_FRAME,
            "tick with input allocated %.1f bytes/frame (budget %d)"
                .format(perFrame, TOLERANCE_BYTES_PER_FRAME),
        )
    }

    @Test
    fun `the running mechanic allocates nothing per tick`() {
        // The physics steady state is covered above. The *game* adds spawning
        // (a seeded PRNG draw and an addBody into pre-sized arrays) and lock
        // detection to the per-tick path; both must stay on the zero-allocation
        // budget ADR 0001 buys with its structure-of-arrays layout.
        //
        // A clear is the one deliberate exception: it allocates a small
        // IntArray and one Phase.Clearing per clear *event* — documented in
        // SimState.Phase.Clearing as a handful of times a minute, not per
        // frame. So this measures the spawn/settle/lock loop with clears
        // suppressed (an unreachable threshold), which is the actual 60 Hz
        // steady state. The clear-event allocation is asserted-by-design
        // elsewhere, not budgeted here.
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        assumeTrue(bean != null, "allocation measurement needs the HotSpot ThreadMXBean")
        assumeTrue(bean!!.isThreadAllocatedMemorySupported, "JVM does not report thread allocation")
        bean.isThreadAllocatedMemoryEnabled = true

        // A tall well so the game keeps dealing pieces — spawning and locking —
        // right through the measured window rather than filling up and idling.
        val sim = gravitris.game.Simulation(
            SimConfig(lattice = 5, wellWidth = 20f, wellHeight = 44f),
        )
        sim.tuning.clearThreshold = 1.01f // no fill can reach this: never clears
        sim.start()
        val input = InputFrame()

        repeat(WARMUP_FRAMES) { sim.step(input) }

        val bodiesAtWarmupEnd = sim.state.bodyCount
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        repeat(MEASURED_FRAMES) { sim.step(input) }
        val after = bean.getThreadAllocatedBytes(id)

        // Guard the guard: if the well had filled and the game stopped dealing,
        // the measurement would trivially pass without exercising spawn/lock.
        assertTrue(
            sim.state.bodyCount > bodiesAtWarmupEnd,
            "no pieces were dealt during the measured window; the spawn/lock path was not tested",
        )

        val perFrame = (after - before).toDouble() / MEASURED_FRAMES
        assertTrue(
            perFrame <= TOLERANCE_BYTES_PER_FRAME,
            "running mechanic allocated %.1f bytes/frame (budget %d)"
                .format(perFrame, TOLERANCE_BYTES_PER_FRAME),
        )
    }

    private companion object {
        const val WARMUP_FRAMES = 3000
        const val MEASURED_FRAMES = 2000

        /**
         * Expected to be 0. A small non-zero budget absorbs the JVM's own
         * bookkeeping on the measuring thread rather than the solver's, and
         * still fails loudly on a real per-frame allocation — the spike's
         * stray `IntArray` was hundreds of bytes per frame.
         */
        const val TOLERANCE_BYTES_PER_FRAME = 8L
    }
}
