package gravitris.app.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the frame-time instrument.
 *
 * These matter more than they look. The client will install this APK on their
 * own phone and report the numbers back as evidence about the project's
 * largest open risk, so a readout that flatters the truth would send the whole
 * team the wrong way. The tests below are therefore mostly about the instrument
 * *not* hiding a hitch.
 */
class FrameStatsTest {

    private val ms = 1_000_000L
    private val stats = FrameStats()
    private val snapshot = FrameSnapshot()

    /**
     * A frame duration is only known once the *next* frame starts, so the
     * pattern throughout is "advance time, then record" — with one baseline
     * record first, which [FrameStats] discards because it has no predecessor.
     */
    private var clock = ms

    private fun baseline(cpuMs: Long = 2) {
        stats.record(clock, cpuMs * ms)
    }

    private fun frame(durationMs: Long, cpuMs: Long = 2) {
        clock += durationMs * ms
        stats.record(clock, cpuMs * ms)
    }

    private fun frames(count: Int, durationMs: Long, cpuMs: Long = 2) {
        repeat(count) { frame(durationMs, cpuMs) }
    }

    @Test
    fun `reports nothing before it has seen a frame`() {
        assertFalse(stats.snapshot(0L, snapshot), "no history means no numbers")
    }

    @Test
    fun `first frame is discarded because it has no predecessor`() {
        stats.record(ms, 0L)
        assertFalse(
            stats.snapshot(ms, snapshot),
            "a single frame has no duration to report",
        )
    }

    @Test
    fun `steady sixty hertz reads as sixteen point seven milliseconds`() {
        stats.record(clock, 4 * ms)
        repeat(58) {
            clock += 16_666_667L
            stats.record(clock, 4 * ms)
        }

        assertTrue(stats.snapshot(clock, snapshot))
        assertEquals(16.67f, snapshot.meanMs, 0.05f)
        assertEquals(60f, snapshot.fps, 0.5f)
        assertEquals(0, snapshot.jankPerSecond, "60Hz frames are not jank")
    }

    @Test
    fun `a single hitch survives in max and jank while the mean stays healthy`() {
        // The headline failure this readout exists to prevent: 55 good frames
        // and one 50ms stall. An average alone would read ~17.2ms and look
        // fine; the player would feel the stall.
        baseline(cpuMs = 4)
        frames(count = 55, durationMs = 16, cpuMs = 4)
        frame(durationMs = 50, cpuMs = 4)

        assertTrue(stats.snapshot(clock, snapshot))
        assertTrue(snapshot.meanMs < 20f, "mean stays healthy: ${snapshot.meanMs}")
        assertEquals(50f, snapshot.maxMs, 0.5f, "the stall must be visible in max")
        assertEquals(1, snapshot.jankPerSecond, "the stall must be counted")
    }

    @Test
    fun `jank counts every frame over the sixteen point seven millisecond budget`() {
        baseline()
        // Alternate comfortable and over-budget frames: exactly half are jank.
        repeat(5) {
            frame(durationMs = 10)
            frame(durationMs = 25)
        }

        assertTrue(stats.snapshot(clock, snapshot))
        assertEquals(5, snapshot.jankPerSecond)
    }

    @Test
    fun `p95 ignores the single worst frame but max does not`() {
        baseline()
        frames(count = 50, durationMs = 10)
        frame(durationMs = 90)

        assertTrue(stats.snapshot(clock, snapshot))
        assertEquals(10f, snapshot.p95Ms, 0.5f, "p95 is robust to one outlier")
        assertEquals(90f, snapshot.maxMs, 0.5f, "max is not, and must not be")
    }

    @Test
    fun `samples older than the window are excluded`() {
        // A burst of terrible frames, then more than a second of good ones.
        // The readout must recover rather than reporting a stall from two
        // seconds ago forever.
        baseline()
        frames(count = 5, durationMs = 100)
        frames(count = 70, durationMs = 16) // 1120ms — pushes the burst out

        assertTrue(stats.snapshot(clock, snapshot))
        assertEquals(
            16f, snapshot.maxMs, 0.5f,
            "the 100ms frames are outside the 1s window and must not be reported",
        )
        assertEquals(0, snapshot.jankPerSecond)
    }

    @Test
    fun `cpu time is tracked separately from frame time`() {
        // The split that tells us whether we are CPU or GPU bound. A 16ms
        // frame with 3ms of our own work means 13ms spent in the driver and
        // the GPU.
        baseline(cpuMs = 3)
        frames(count = 30, durationMs = 16, cpuMs = 3)

        assertTrue(stats.snapshot(clock, snapshot))
        assertEquals(3f, snapshot.cpuMs, 0.1f)
        assertEquals(3f, snapshot.meanCpuMs, 0.1f)
        assertEquals(16f, snapshot.meanMs, 0.5f)
    }

    @Test
    fun `reset discards history so a pause is not reported as a stall`() {
        baseline()
        frames(count = 30, durationMs = 16)
        stats.reset()

        assertFalse(stats.snapshot(100 * ms, snapshot))

        // The frame after a reset has no predecessor and is discarded, exactly
        // like the first frame ever — so a 10-second pause cannot appear as a
        // 10-second frame.
        stats.record(10_000 * ms, 2 * ms)
        assertFalse(stats.snapshot(10_000 * ms, snapshot))
    }

    @Test
    fun `non monotonic timestamps are ignored rather than recorded as negative`() {
        stats.record(100 * ms, ms)
        stats.record(90 * ms, ms)

        assertFalse(
            stats.snapshot(100 * ms, snapshot),
            "a backwards timestamp must not enter the window",
        )
    }

    @Test
    fun `the ring buffer wraps without corrupting the window`() {
        // Far more frames than the 256-entry capacity.
        baseline()
        frames(count = 1000, durationMs = 16)

        assertTrue(stats.snapshot(clock, snapshot))
        assertEquals(16f, snapshot.meanMs, 0.5f)
        assertEquals(16f, snapshot.maxMs, 0.5f)
        assertTrue(snapshot.sampleCount in 1..256)
    }
}
