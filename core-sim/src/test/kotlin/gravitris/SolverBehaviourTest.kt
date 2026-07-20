package gravitris

import gravitris.game.InputFrame
import gravitris.game.Phase
import gravitris.game.SimConfig
import gravitris.game.Simulation
import gravitris.physics.SoftBodyWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behaviour of the solver and of piece control: containment, deformation,
 * scene validation and the render-facing outputs `:app` consumes.
 */
class SolverBehaviourTest {

    private fun config() = SimConfig(lattice = 5, wellWidth = 10f, wellHeight = 20f)

    // --- topology -----------------------------------------------------------

    @Test
    fun `lattice topology matches the measured constraint counts`() {
        // ADR 0001's cost table is indexed by particle and constraint count,
        // and every budget in the architecture is derived from it. If this
        // drifts, the budget silently stops describing the thing being built.
        val expected = mapOf(4 to (16 to 60), 5 to (25 to 104), 6 to (36 to 160))
        for ((lattice, counts) in expected) {
            val world = SoftBodyWorld(config().copy(lattice = lattice))
            world.addBody(archetype = 0, centerX = 5f, centerY = 5f)
            val (particles, constraints) = counts
            assertEquals(particles, world.particleCount, "particles per body at lattice $lattice")
            assertEquals(
                constraints,
                world.distanceCount + world.areaCount,
                "constraints per body at lattice $lattice",
            )
        }
    }

    @Test
    fun `triangle indices cover every lattice cell twice over`() {
        val world = SoftBodyWorld(config())
        val cells = (world.lattice - 1) * (world.lattice - 1)
        assertEquals(cells * 6, world.triangleIndices.size, "two triangles per cell")
        assertTrue(
            world.triangleIndices.all { it in 0 until world.particlesPerBody },
            "indices must be body-local, so :app can reuse them with an offset per body",
        )
    }

    // --- containment --------------------------------------------------------

    @Test
    fun `a piece dropped from height stays inside the well`() {
        // Boundary contacts are solved directly every substep and never go
        // through the broadphase, so this must hold regardless of fall speed.
        val config = config()
        val sim = Simulation(config)
        sim.addPiece(archetype = 0, centerX = 5f, centerY = 18f)

        val world = SoftBodyWorld(config)
        val r = world.particleRadius
        val input = InputFrame()

        repeat(600) {
            sim.step(input)
            val s = sim.state
            for (i in 0 until s.particleCount) {
                assertTrue(
                    s.positionY[i] >= -PENETRATION_TOLERANCE,
                    "particle $i fell through the floor to y=${s.positionY[i]}",
                )
                assertTrue(
                    s.positionX[i] >= -PENETRATION_TOLERANCE &&
                        s.positionX[i] <= config.wellWidth + PENETRATION_TOLERANCE,
                    "particle $i escaped the walls at x=${s.positionX[i]}",
                )
            }
        }
        // And it should have come to rest on the floor, not hovered.
        assertTrue(
            TestScenes.stackHeight(sim.state) < 2f + r,
            "the piece should have landed, top was ${TestScenes.stackHeight(sim.state)}",
        )
    }

    @Test
    fun `a hard-dropped piece does not tunnel through a resting piece`() {
        // The broadphase grid is rebuilt once per frame, not once per substep
        // (ADR 0003 §1). That is the assumption most likely to break at speed,
        // so it gets an explicit test rather than an argument.
        val config = config()
        val sim = Simulation(config)
        val resting = sim.addPiece(archetype = 0, centerX = 5f, centerY = 1.5f)
        TestScenes.run(sim, 240)

        val faller = sim.addPiece(archetype = 1, centerX = 5f, centerY = 18f)
        val input = InputFrame()
        input.hardDrop = true
        input.hardDropVelocity = 30f
        sim.step(input)
        input.clear()
        TestScenes.run(sim, 600)

        val restingTop = bodyMaxY(sim, resting)
        val fallerBottom = bodyMinY(sim, faller)
        assertTrue(
            fallerBottom > restingTop - config.wellWidth * 0.1f,
            "the dropped piece ended below the resting one (bottom=$fallerBottom, " +
                "resting top=$restingTop) — it tunnelled through",
        )
    }

    // --- deformation --------------------------------------------------------

    @Test
    fun `material compresses under its own weight`() {
        // This is the product, not a detail: "if the blocks end up feeling
        // stiff, we have built the wrong thing". A pile that settles with
        // every cell still at its rest area is a pile of rigid boxes.
        val sim = TestScenes.pile(config(), bodies = 24)
        TestScenes.run(sim, 900)

        val s = sim.state
        var minCompression = Float.MAX_VALUE
        for (i in 0 until s.particleCount) {
            if (s.particleCompression[i] < minCompression) minCompression = s.particleCompression[i]
        }
        assertTrue(
            minCompression < 1f,
            "no material was compressed under load; least compression was $minCompression",
        )
        assertTrue(
            minCompression > 0.1f,
            "material collapsed rather than compressed ($minCompression); the area " +
                "constraints should stop a body flattening out",
        )
    }

    @Test
    fun `a settled pile reports contact and edge information for the renderer`() {
        val sim = TestScenes.pile(config(), bodies = 12)
        TestScenes.run(sim, 900)
        val s = sim.state

        assertTrue(
            (0 until s.particleCount).any { s.particleContact[it] > 0f },
            "a settled pile must report contact occlusion; it drives the seam shading",
        )
        assertTrue(
            (0 until s.particleCount).all { s.particleEdge[it] == 0f || s.particleEdge[it] == 1f },
            "particleEdge is a boundary flag, not a gradient",
        )
        assertTrue(
            (0 until s.particleCount).all { s.particleU[it] in 0f..1f && s.particleV[it] in 0f..1f },
            "lattice coordinates must stay in 0..1 — they are material coordinates",
        )
    }

    @Test
    fun `landing produces an impact event and resting does not`() {
        val sim = Simulation(config())
        sim.addPiece(archetype = 0, centerX = 5f, centerY = 18f)
        val input = InputFrame()

        var sawImpact = false
        repeat(300) {
            sim.step(input)
            if (sim.state.impacts.count > 0) {
                sawImpact = true
                val strength = sim.state.impacts.strength[0]
                assertTrue(strength in 0f..1f, "impact strength must be normalised, was $strength")
            }
        }
        assertTrue(sawImpact, "a piece falling onto the floor must report an impact for haptics")

        // Once settled, a resting piece must not fire a haptic every tick.
        TestScenes.run(sim, 300)
        var impactsWhileResting = 0
        repeat(120) {
            sim.step(input)
            impactsWhileResting += sim.state.impacts.count
        }
        assertEquals(0, impactsWhileResting, "a resting piece must not keep firing impacts")
    }

    // --- scene validation ---------------------------------------------------

    @Test
    fun `seeding a body overlapping existing material is rejected`() {
        // Spike bug 1. The contact solver turns a seeded overlap into launch
        // energy, and the symptom looks like a solver defect, so this fails at
        // the point of the mistake instead.
        val sim = Simulation(config())
        sim.addPiece(archetype = 0, centerX = 5f, centerY = 5f)
        assertThrows(IllegalStateException::class.java) {
            sim.addPiece(archetype = 1, centerX = 5.1f, centerY = 5.1f)
        }
    }

    @Test
    fun `seeding a body outside the well is rejected`() {
        val sim = Simulation(config())
        assertThrows(IllegalStateException::class.java) {
            sim.addPiece(archetype = 0, centerX = 0.1f, centerY = 5f)
        }
        assertThrows(IllegalStateException::class.java) {
            sim.addPiece(archetype = 0, centerX = 5f, centerY = 0.1f)
        }
    }

    @Test
    fun `a config that could never hold a piece is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            SimConfig(wellWidth = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimConfig(lattice = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SimConfig(substeps = 0)
        }
    }

    // --- input --------------------------------------------------------------

    @Test
    fun `drag moves the piece without injecting velocity`() {
        // Moving position alone would make the solver derive a velocity spike
        // of drag/h on the next substep and fling the piece across the well.
        //
        // The comparison is against an identical simulation stepped the same
        // number of ticks *without* the drag. Comparing against the previous
        // tick of the same run would just measure gravity, which is a much
        // larger effect than the one under test.
        val dragged = Simulation(config())
        val free = Simulation(config())
        dragged.addPiece(archetype = 0, centerX = 5f, centerY = 10f)
        free.addPiece(archetype = 0, centerX = 5f, centerY = 10f)

        val input = InputFrame()
        input.dragX = 0.3f
        val before = centroidX(dragged)
        dragged.step(input)
        free.step(InputFrame())
        val after = centroidX(dragged)

        assertTrue(after > before + 0.2f, "drag should move the piece, moved ${after - before}")
        assertTrue(
            dragged.state.kineticEnergy < free.state.kineticEnergy + VELOCITY_INJECTION_BUDGET,
            "drag injected kinetic energy (${dragged.state.kineticEnergy} against an " +
                "undragged ${free.state.kineticEnergy}); position and previous-position " +
                "buffers must move together",
        )
    }

    @Test
    fun `drag is clamped to the well`() {
        val config = config()
        val sim = Simulation(config)
        sim.addPiece(archetype = 0, centerX = 5f, centerY = 10f)
        val input = InputFrame()

        repeat(60) {
            input.dragX = 5f
            sim.step(input)
        }
        val s = sim.state
        for (i in 0 until s.particleCount) {
            assertTrue(
                s.positionX[i] <= config.wellWidth + PENETRATION_TOLERANCE,
                "drag pushed particle $i out of the well to x=${s.positionX[i]}",
            )
        }
    }

    @Test
    fun `four quarter turns return the piece exactly to where it started`() {
        // Quarter turns are (x, y) -> (y, -x): no trigonometry, so no platform
        // ulp variance (ADR 0006), and — unlike a lookup table — exact, so
        // repeated application cannot drift.
        //
        // Gravity is off so the rotation is the only thing moving anything.
        val sim = Simulation(config().copy(gravity = 0f))
        sim.addPiece(archetype = 0, centerX = 5f, centerY = 10f)
        val n = sim.state.particleCount
        val startX = sim.state.positionX.copyOf(n)
        val startY = sim.state.positionY.copyOf(n)

        val input = InputFrame()
        repeat(4) {
            input.clear()
            input.rotate = true
            sim.step(input)
        }

        for (i in 0 until n) {
            assertEquals(startX[i], sim.state.positionX[i], ROTATION_TOLERANCE, "x drifted at particle $i")
            assertEquals(startY[i], sim.state.positionY[i], ROTATION_TOLERANCE, "y drifted at particle $i")
        }
    }

    @Test
    fun `rotating a piece wedged against its neighbours injects no energy`() {
        // A rotation that cannot be taken must be refused, not applied and
        // left for the contact solver to push apart — that push is launch
        // energy, the same failure as seeding an overlap.
        //
        // Note this is a weak test today by design: every Stage 1 archetype is
        // a square lattice, so a quarter turn maps a piece onto its own
        // footprint and can rarely be blocked. It becomes load-bearing when
        // real piece silhouettes arrive in Stage 3.
        val sim = Simulation(config())
        sim.addPiece(archetype = 0, centerX = 2.5f, centerY = 2f)
        sim.addPiece(archetype = 1, centerX = 5.5f, centerY = 2f)
        sim.addPiece(archetype = 2, centerX = 8.5f, centerY = 2f)
        TestScenes.run(sim, 300)

        // Measured as *displacement*, not as instantaneous kinetic energy.
        //
        // Once the material became genuinely soft (distanceCompliance 1e-4) an
        // energy bound stopped measuring what this test is for. Turning a body
        // that has settled under gravity reorients the strain it settled with,
        // and it wobbles as that relaxes: energy right after the turn is ~3.9,
        // against ~0.08 when the material was rigid. That looks alarming and is
        // not — measured over the two seconds that follow, the rotated piece's
        // centroid moves 0.0000 well units and its neighbours' move 0.0000.
        // Nothing is launched; a squishy block jiggles in place, which is the
        // behaviour the product wants.
        //
        // So the assertion is the one the test always meant: the piece must not
        // be flung, and the pile must return to quiet. Both are stricter than
        // the energy bound they replace, and neither is fooled by soft material.
        val selfBefore = centroidOf(sim.state, body = 0)
        val neighbourBefore = centroidOf(sim.state, body = 1)

        val input = InputFrame()
        input.rotate = true
        sim.step(input)
        input.clear()

        var selfDrift = 0f
        var neighbourDrift = 0f
        repeat(ROTATION_SETTLE_FRAMES) {
            sim.step(input)
            selfDrift = maxOf(selfDrift, distance(centroidOf(sim.state, 0), selfBefore))
            neighbourDrift = maxOf(neighbourDrift, distance(centroidOf(sim.state, 1), neighbourBefore))
        }

        assertTrue(
            selfDrift < ROTATION_DRIFT_TOLERANCE,
            "rotation launched the piece: its centroid moved $selfDrift well units",
        )
        assertTrue(
            neighbourDrift < ROTATION_DRIFT_TOLERANCE,
            "rotation disturbed a neighbouring piece: its centroid moved " +
                "$neighbourDrift well units",
        )
        assertTrue(
            sim.state.kineticEnergy < config().quietKineticEnergy,
            "the pile should be quiet again ${ROTATION_SETTLE_FRAMES} frames after a " +
                "rotation, kinetic energy was ${sim.state.kineticEnergy}",
        )
    }

    private fun centroidOf(s: gravitris.game.SimState, body: Int): FloatArray {
        var cx = 0f
        var cy = 0f
        var n = 0
        for (i in 0 until s.particleCount) {
            if (s.particleBody[i] != body) continue
            cx += s.positionX[i]
            cy += s.positionY[i]
            n++
        }
        return floatArrayOf(cx / n, cy / n)
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // --- contract surface ---------------------------------------------------

    @Test
    fun `stage one state fields hold their documented inert values`() {
        // `:app` builds against these now. If any of them silently starts
        // reporting something, the frontend would render a mechanic that does
        // not exist.
        val config = config()
        val sim = TestScenes.pile(config, bodies = 4)
        TestScenes.run(sim, 60)
        val s = sim.state

        assertEquals(Phase.Playing, s.phase)
        assertEquals(0, s.score)
        assertEquals(1, s.level)
        assertFalse(s.landing.valid, "the landing silhouette is Stage 4")
        assertEquals(config.bandCount, s.bandFill.size)
        assertTrue(s.bandClearProgress.all { it == -1f }, "-1 means 'not clearing'")
        assertEquals(config.wellHeight / config.bandCount, s.bandHeight)
        assertEquals(config.lattice, s.bodyLattice)

        // Coverage bands were Stage 3 and are now live, so this no longer
        // asserts they read zero. What it does assert is that a simulation
        // nobody called `start()` on deals no pieces: `TestScenes.pile` and the
        // reference benchmark both seed a scene and measure it, and a piece
        // arriving mid-measurement would corrupt every number they produce.
        assertEquals(4, s.bodyCount, "an unstarted simulation must not spawn")
        assertEquals(-1, s.activePieceBody, "an unstarted simulation has no active piece")
    }

    @Test
    fun `the benchmark reference scene matches the measured workload`() {
        // ADR 0001's reference row: 960 particles, 3 600 constraints at
        // lattice 4. This is the scene `:app` runs on the device to close the
        // host-to-device derating blocker, so it must not drift.
        val config = Simulation.benchmarkReferenceConfig()
        assertEquals(4, config.lattice)
        assertEquals(8, config.substeps)

        val sim = Simulation.buildBenchmarkScene(config)
        assertEquals(960, sim.state.particleCount, "ADR 0001 reference row: 960 particles")
        assertEquals(Simulation.BENCHMARK_BODIES, sim.state.bodyCount)

        val world = SoftBodyWorld(config)
        world.addBody(archetype = 0, centerX = 5f, centerY = 5f)
        assertEquals(
            3600,
            (world.distanceCount + world.areaCount) * Simulation.BENCHMARK_BODIES,
            "ADR 0001 reference row: 3 600 constraints",
        )

        // And it must actually run.
        TestScenes.run(sim, 120)
        assertTrue(sim.state.kineticEnergy.isFinite(), "reference scene diverged")
    }

    // --- helpers ------------------------------------------------------------

    private fun centroidX(sim: Simulation): Float {
        val s = sim.state
        var sum = 0f
        for (i in 0 until s.particleCount) sum += s.positionX[i]
        return sum / s.particleCount
    }

    private fun bodyMaxY(sim: Simulation, body: Int): Float {
        val s = sim.state
        var top = -Float.MAX_VALUE
        for (i in 0 until s.particleCount) {
            if (s.particleBody[i] == body && s.positionY[i] > top) top = s.positionY[i]
        }
        return top
    }

    private fun bodyMinY(sim: Simulation, body: Int): Float {
        val s = sim.state
        var bottom = Float.MAX_VALUE
        for (i in 0 until s.particleCount) {
            if (s.particleBody[i] == body && s.positionY[i] < bottom) bottom = s.positionY[i]
        }
        return bottom
    }

    private companion object {
        /** Contacts are rigid, but one substep of overlap before correction is normal. */
        const val PENETRATION_TOLERANCE = 0.05f

        const val ROTATION_TOLERANCE = 1e-4f

        /** Kinetic energy a single kinematic move is allowed to add. */
        const val VELOCITY_INJECTION_BUDGET = 1f

        /** Two seconds — long enough for a rotated soft body to stop wobbling. */
        const val ROTATION_SETTLE_FRAMES = 120

        /**
         * Well units a piece may drift after a rotation. Measured, both the
         * rotated piece and its neighbours move 0.0000; a piece is 1.8 across,
         * so this bound is a hundredth of a piece and would catch a launch of
         * any size while tolerating float noise.
         */
        const val ROTATION_DRIFT_TOLERANCE = 0.02f
    }
}
