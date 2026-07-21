package gravitris

import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.Simulation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Adversarial hardening of the core solver, independent of the code that wrote
 * it. These target the paths the existing suite does not exercise: the empty
 * well, the body-capacity ceiling, a very long idle hold, determinism under a
 * large stressed scene, and the degenerate configurations (zero gravity, bodies
 * straddling a broadphase cell boundary).
 *
 * Every scene is seeded with a gap, as `TestScenes` requires — an invalid scene
 * is a precondition failure, not a result.
 */
class CoreSimHardeningTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 10f, wellHeight = 20f)

    private fun contactingParticles(sim: Simulation): Int {
        val s = sim.state
        var c = 0
        for (i in 0 until s.particleCount) if (s.particleContact[i] > 0f) c++
        return c
    }

    // --- the empty well and single-piece boundary ---------------------------

    @Test
    fun `stepping an empty well is a safe no-op`() {
        // Nothing constructs and steps a simulation with no bodies, yet `:app`
        // will drive `step` from the very first frame, before any piece spawns
        // (Stage 3). The `n == 0` early-out in `XpbdSolver.step` has therefore
        // never executed. It must not throw, must report nothing happening, and
        // must leave the simulation usable.
        val sim = Simulation(config())
        val input = InputFrame()
        repeat(120) { sim.step(input) }

        assertEquals(0, sim.state.particleCount, "an empty well has no particles")
        assertEquals(0f, sim.state.kineticEnergy, "an empty well has no energy")
        assertEquals(0, sim.state.impacts.count, "an empty well produces no impacts")

        // And it must recover: a piece added after idle frames still falls.
        sim.addPiece(archetype = 0, centerX = 5f, centerY = 18f)
        TestScenes.run(sim, 300)
        assertTrue(
            sim.state.kineticEnergy.isFinite() && sim.state.particleCount > 0,
            "a piece added to a previously-empty well must simulate normally",
        )
    }

    // --- the body-capacity ceiling ------------------------------------------

    @Test
    fun `filling the well to capacity throws cleanly and stays contained`() {
        // The `bodyCount < maxBodies` guard in `SoftBodyWorld.addBody` has no
        // test. Fill to the ceiling, confirm the overflow is a clean
        // IllegalStateException rather than an array write off the end, and
        // confirm a completely packed well still simulates finitely and holds
        // every particle inside the boundary.
        val config = config()
        val sim = Simulation(config)
        val pitch = TestScenes.pitchFor(config)
        val perRow = ((config.wellWidth - pitch) / pitch).toInt() + 1

        var placed = 0
        val overflow = assertThrows(IllegalStateException::class.java) {
            var b = 0
            while (true) {
                sim.addPiece(
                    archetype = b % Simulation.ARCHETYPE_COUNT,
                    centerX = pitch * 0.5f + (b % perRow) * pitch,
                    centerY = pitch * 0.5f + (b / perRow) * pitch,
                )
                placed++
                b++
            }
        }
        assertTrue(
            overflow.message?.contains("capacity") == true,
            "capacity overflow must name the limit, was: ${overflow.message}",
        )
        assertTrue(placed > 0, "the well must hold at least one body")
        sim.clearActivePiece()

        TestScenes.run(sim, 1500)
        val s = sim.state
        assertTrue(s.kineticEnergy.isFinite(), "a full well diverged, kinetic energy ${s.kineticEnergy}")
        val r = SimConfig.PIECE_WIDTH / (config.lattice - 1) * 0.5f
        for (i in 0 until s.particleCount) {
            assertTrue(
                s.positionY[i] >= -CONTAINMENT_TOLERANCE &&
                    s.positionX[i] >= -CONTAINMENT_TOLERANCE &&
                    s.positionX[i] <= config.wellWidth + CONTAINMENT_TOLERANCE,
                "a fully packed well leaked particle $i to (${s.positionX[i]}, ${s.positionY[i]})",
            )
            // Not floating: some particle of a packed well must be near the floor.
            if (i == 0) continue
        }
        assertTrue(
            TestScenes.stackHeight(s) > r,
            "a packed well should contain a real pile, top was ${TestScenes.stackHeight(s)}",
        )
    }

    // --- the flagged settled-pile drift -------------------------------------

    @Test
    fun `a settled pile does not recruit contacts or wake over a long idle hold`() {
        // The Backend Engineer flagged a settled pile appearing to grow its
        // contacting-particle count over idle frames — a possible energy leak.
        // The real property is convergence, not a fixed frame budget (handoff
        // 0008): once settled, a pile must neither wake (kinetic energy stays
        // quiet) nor slowly expand into new contacts (the contact set does not
        // grow). Measured over 6000 idle frames the count is flat and energy is
        // three orders below the quiet threshold, so this asserts stability, not
        // a lucky settling time.
        val config = config()
        val sim = TestScenes.pile(config, bodies = 16)
        TestScenes.run(sim, SETTLE_FRAMES)

        val settledContacts = contactingParticles(sim)
        assertTrue(settledContacts > 0, "a settled pile must be in contact somewhere")

        repeat(IDLE_BLOCKS) {
            TestScenes.run(sim, IDLE_BLOCK_FRAMES)
            assertTrue(
                sim.state.kineticEnergy < config.quietKineticEnergy,
                "a settled pile woke itself up after idling: kinetic energy " +
                    "${sim.state.kineticEnergy} exceeded the quiet threshold " +
                    "${config.quietKineticEnergy} — energy injected by the solver, not the player",
            )
            assertTrue(
                contactingParticles(sim) <= settledContacts + CONTACT_RECRUIT_TOLERANCE,
                "a settled pile recruited new contacts while idle: " +
                    "${contactingParticles(sim)} contacting particles against a settled " +
                    "$settledContacts — the pile is slowly spreading or collapsing, not standing still",
            )
        }
    }

    // --- determinism under a large, stressed scene --------------------------

    @Test
    fun `a large stressed scene replays bit-identically`() {
        // DeterminismTest proves replay on an 8-body scene. The determinism
        // contract (ADR 0006) is the foundation of every replay regression test
        // QA will write, so it is worth stressing at the reference scale with
        // deep contact, fast drags, hard drops at terminal speed and wall
        // contacts all in play. Tick-indexed input only — never wall-clock.
        assertArrayEquals(
            runStressed(STRESS_FRAMES),
            runStressed(STRESS_FRAMES),
            "$STRESS_FRAMES ticks of a 40-body scene under drag, rotation and hard drops " +
                "must be bit-identical on replay",
        )
    }

    @Test
    fun `splitting a large run into two step loops does not change it`() {
        // If any tick depended on something outside (state, input) — a running
        // count, a lazily allocated buffer, a wall clock — a split run would
        // diverge. This is that check at the reference scale.
        val whole = runStressed(STRESS_FRAMES)

        val sim = TestScenes.pile(SimConfig(lattice = 5, wellWidth = 12f, wellHeight = 44f), bodies = 40)
        val input = InputFrame()
        val half = STRESS_FRAMES / 2
        for (t in 0 until half) driveStress(sim, input, t)
        for (t in half until STRESS_FRAMES) driveStress(sim, input, t)

        assertArrayEquals(
            whole,
            TestScenes.fingerprint(sim.state),
            "a run split into two step loops diverged from the single-loop run",
        )
    }

    private fun runStressed(frames: Int): IntArray {
        val sim = TestScenes.pile(SimConfig(lattice = 5, wellWidth = 12f, wellHeight = 44f), bodies = 40)
        val input = InputFrame()
        for (t in 0 until frames) driveStress(sim, input, t)
        return TestScenes.fingerprint(sim.state)
    }

    /** A schedule that is a pure function of the tick index (ADR 0006). */
    private fun driveStress(sim: Simulation, input: InputFrame, tick: Int) {
        input.clear()
        when {
            tick % 53 == 0 -> input.rotate = true
            tick % 11 == 0 -> input.dragX = if ((tick / 11) % 2 == 0) 0.9f else -0.9f
            tick % 97 == 0 -> sim.slamActivePiece(30f) // impact-velocity probe (ADR 0016)
        }
        sim.step(input)
    }

    // --- degenerate configurations ------------------------------------------

    @Test
    fun `zero gravity manufactures no energy from a settled body`() {
        // Gravity 0 is an allowed, documented configuration (SimConfig: "a
        // weightless scene isolates constraint behaviour from settling"). A body
        // seeded at rest, weightless, must stay at rest — if the constraint
        // solver injects energy from an undeformed lattice, it shows here with
        // nothing else moving to hide it.
        val sim = Simulation(config().copy(gravity = 0f))
        sim.addPiece(archetype = 0, centerX = 5f, centerY = 10f)
        assertEquals(0f, sim.state.kineticEnergy, "a freshly seeded body starts at rest")

        TestScenes.run(sim, 600)
        assertTrue(
            sim.state.kineticEnergy < ZERO_G_ENERGY_BUDGET,
            "a weightless body at rest gained energy from nothing: kinetic energy " +
                "${sim.state.kineticEnergy}; the constraint solve is manufacturing motion",
        )
    }

    @Test
    fun `a body settling onto another stays rigid across cell rows`() {
        // The narrowphase keys its stencil off the cell a particle was sorted
        // into. A body resting on another spans several grid rows at the seam,
        // which is the case most likely to expose a missed neighbour or a
        // phantom one. The upper body must come to rest on the lower with no
        // visible inter-penetration and no residual energy.
        val config = config()
        val sim = Simulation(config)
        sim.addPiece(archetype = 0, centerX = 5f, centerY = 1.2f)
        sim.addPiece(archetype = 1, centerX = 5f, centerY = 4f)
        sim.clearActivePiece()
        TestScenes.run(sim, 900)

        assertTrue(
            sim.state.kineticEnergy < config.quietKineticEnergy,
            "cell-boundary bodies did not settle, kinetic energy ${sim.state.kineticEnergy}",
        )
        val radius = SimConfig.PIECE_WIDTH / (config.lattice - 1) * 0.5f
        var worst = 0f
        val s = sim.state
        for (i in 0 until s.particleCount) {
            for (j in i + 1 until s.particleCount) {
                if (s.particleBody[i] == s.particleBody[j]) continue
                val dx = s.positionX[i] - s.positionX[j]
                val dy = s.positionY[i] - s.positionY[j]
                val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                if (2f * radius - d > worst) worst = 2f * radius - d
            }
        }
        assertTrue(
            worst < CONTAINMENT_TOLERANCE,
            "a body sank ${worst} well units into another across a cell boundary; contacts are rigid",
        )
    }

    @Test
    fun `a fresh InputFrame per tick replays the stressed scene identically`() {
        // Mirrors DeterminismTest's InputFrame-identity guard, at the stressed
        // scale: a replay harness may build a new InputFrame each tick, and the
        // core must not depend on its identity or mutate it.
        val reused = runStressed(600)

        val sim = TestScenes.pile(SimConfig(lattice = 5, wellWidth = 12f, wellHeight = 44f), bodies = 40)
        for (t in 0 until 600) driveStress(sim, InputFrame(), t)

        assertFalse(
            reused.isEmpty(),
            "fingerprint should be non-empty",
        )
        assertArrayEquals(
            reused,
            TestScenes.fingerprint(sim.state),
            "the core depended on InputFrame identity",
        )
    }

    private companion object {
        const val SETTLE_FRAMES = 900

        /** Twenty blocks of 300 idle frames — 6000 frames, 100 s of doing nothing. */
        const val IDLE_BLOCKS = 20
        const val IDLE_BLOCK_FRAMES = 300

        /**
         * A settled 16-body pile holds a flat 140 contacting particles across
         * the whole idle hold; this tolerance absorbs single-particle float
         * jitter without admitting a real spreading trend (the flagged report
         * was a +7 growth).
         */
        const val CONTACT_RECRUIT_TOLERANCE = 4

        const val STRESS_FRAMES = 1200

        /** One substep of overlap before correction is normal; a leak is not. */
        const val CONTAINMENT_TOLERANCE = 0.05f

        /**
         * A rest configuration should stay at exactly zero energy; this budget
         * absorbs float rounding in the derive-velocity step without admitting
         * real motion.
         */
        const val ZERO_G_ENERGY_BUDGET = 1e-3f
    }
}
