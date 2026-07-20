package gravitris.app.harness

import gravitris.app.WellLayout
import gravitris.app.sim.ImpactList
import gravitris.app.sim.InputFrame
import gravitris.app.sim.SimState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * # A RENDER HARNESS. NOT PHYSICS. DELETE AT STAGE 2 INTEGRATION.
 *
 * This produces `SimState`-shaped output so the renderer, the input path and
 * the haptics driver can be built, run and *measured* while `:core-sim` is
 * written in parallel on Track A (`docs/build-order.md` Stage 1). At Stage 2
 * it is deleted and the real `Simulation` takes its place — the renderer does
 * not change, because it only ever sees the interface.
 *
 * ## What it deliberately does and does not do
 *
 * It **is not a soft-body solver** and must never be mistaken for one, nor
 * grown into one. There are no constraints, no substeps, no contacts and no
 * friction. Deformation is a decaying kinematic squash: an ease-out
 * oscillation applied to each particle's offset from its body centre. It will
 * not stack believably, it will not settle believably, and the material has no
 * stiffness to tune. **Nothing about how the game feels can be judged from
 * this.** That judgement is what Milestone 1 exists for, after integration.
 *
 * What it *is* faithful about is the things the renderer's cost depends on,
 * because measuring that cost is this stage's job:
 *
 * - the same particle count as the real default tier — 5x5 lattice, up to 60
 *   bodies, so 1500 particles, exactly the figure ADR 0007 budgets against;
 * - **per-particle positions written on the CPU every tick**, never an affine
 *   transform the vertex shader could have done. A harness that cheated here
 *   would make the per-frame buffer-fill cost — which ADR 0007 warns "must be
 *   counted in the frame budget, not treated as free because it is
 *   rendering" — look far cheaper than it will be;
 * - previous-tick positions retained, so the ADR 0006 interpolation lerp is
 *   exercised rather than stubbed;
 * - impact events with a plausible energy distribution, so the haptic curve
 *   and its energy floor are exercised across their range.
 */
class RenderHarness(private val layout: WellLayout) : SimState {

    override val bodyLattice = LATTICE

    private val particlesPerBody = LATTICE * LATTICE
    private val capacity = MAX_BODIES * particlesPerBody

    override val positionX = FloatArray(capacity)
    override val positionY = FloatArray(capacity)
    override val prevPositionX = FloatArray(capacity)
    override val prevPositionY = FloatArray(capacity)
    override val particleBody = IntArray(capacity)
    override val bodyArchetype = IntArray(MAX_BODIES)

    override var bodyCount = 0
        private set

    override val particleCount: Int get() = bodyCount * particlesPerBody

    override val impacts = MutableImpactList(MAX_IMPACTS)

    // --- per-body kinematic state ---
    private val centreX = FloatArray(MAX_BODIES)
    private val centreY = FloatArray(MAX_BODIES)
    private val halfWidth = FloatArray(MAX_BODIES)
    private val halfHeight = FloatArray(MAX_BODIES)
    private val velocityY = FloatArray(MAX_BODIES)

    /** Seconds since this body's impact; negative means "still falling". */
    private val squashAge = FloatArray(MAX_BODIES)

    /** Peak squash amplitude, from the impact speed. */
    private val squashAmplitude = FloatArray(MAX_BODIES)

    private var activeBody = -1
    private var spawnCounter = 0

    /** Deterministic-enough pseudo-randomness for shape selection. No
     *  `Random`: the harness should behave the same on every launch so the
     *  client's frame-time readings are comparable between runs. */
    private var shapeCursor = 0

    init {
        spawn()
    }

    /**
     * Advance one fixed 1/60s tick. Mirrors `Simulation.step(input)` so the
     * call site does not change at integration.
     */
    fun step(input: InputFrame) {
        impacts.clear()

        System.arraycopy(positionX, 0, prevPositionX, 0, particleCount)
        System.arraycopy(positionY, 0, prevPositionY, 0, particleCount)

        val active = activeBody
        if (active >= 0) {
            applyInput(active, input)
            fall(active)
        }

        for (body in 0 until bodyCount) {
            if (squashAge[body] >= 0f) squashAge[body] += DT
            writeParticles(body)
        }
    }

    private fun applyInput(body: Int, input: InputFrame) {
        if (input.dragX != 0f) {
            val limit = layout.widthWorld - halfWidth[body]
            centreX[body] = (centreX[body] + input.dragX).coerceIn(halfWidth[body], limit)
        }

        if (input.rotate) {
            // Rotation is a 90-degree swap of the body's extents. In the real
            // solver this is a rotation of the lattice; here it only needs to
            // be visibly *something*, so that a tap is demonstrably wired
            // through to a response the client can see and feel.
            val w = halfWidth[body]
            halfWidth[body] = halfHeight[body]
            halfHeight[body] = w
            val limit = layout.widthWorld - halfWidth[body]
            centreX[body] = centreX[body].coerceIn(halfWidth[body], limit)
        }

        if (input.hardDrop) {
            // The flick's own speed carries into the drop, so a harder flick
            // lands harder and therefore vibrates harder. That coupling is the
            // whole point of routing hardDropVelocity through the contract
            // rather than treating hard drop as a boolean.
            velocityY[body] = -max(HARD_DROP_MIN_SPEED, abs(input.hardDropVelocity))
        }
    }

    private fun fall(body: Int) {
        velocityY[body] += GRAVITY * DT
        velocityY[body] = max(velocityY[body], -TERMINAL_SPEED)
        centreY[body] += velocityY[body] * DT

        val restY = restingCentreY(body)
        if (centreY[body] > restY) return

        // Landed.
        centreY[body] = restY
        val impactSpeed = abs(velocityY[body])
        velocityY[body] = 0f
        squashAge[body] = 0f

        // energy = clamp(mass_norm * velocity_norm, 0, 1) — the formula in
        // docs/ux/feel-feedback.md. Mass here stands in for the real solver's
        // per-body mass; area is the only proxy the harness has.
        val area = 4f * halfWidth[body] * halfHeight[body]
        val massNorm = (area / MAX_BODY_AREA).coerceIn(0f, 1f)
        val velocityNorm = (impactSpeed / IMPACT_SPEED_FOR_FULL_ENERGY).coerceIn(0f, 1f)
        val energy = (massNorm * velocityNorm).coerceIn(0f, 1f)

        squashAmplitude[body] = MAX_SQUASH * velocityNorm
        impacts.add(centreX[body], centreY[body] - halfHeight[body], energy)

        if (body == activeBody) {
            activeBody = -1
            spawn()
        }
    }

    /**
     * Height at which [body] comes to rest: the floor, or the top of the
     * highest already-landed body whose horizontal extent overlaps this one.
     *
     * A crude axis-aligned drop test, and deliberately no more than that —
     * anything cleverer would be re-implementing Track A's job badly. It exists
     * so the well fills up as the client plays, which is what puts a realistic
     * number of bodies in front of the renderer.
     */
    private fun restingCentreY(body: Int): Float {
        var surface = 0f
        for (other in 0 until bodyCount) {
            if (other == body || squashAge[other] < 0f) continue
            val gap = abs(centreX[body] - centreX[other])
            if (gap >= halfWidth[body] + halfWidth[other]) continue
            surface = max(surface, centreY[other] + halfHeight[other])
        }
        return surface + halfHeight[body]
    }

    /**
     * Write this body's particle positions for the current tick.
     *
     * The squash is an ease-out oscillation on the vertical offset, with the
     * horizontal offset expanding to roughly conserve area, plus a per-particle
     * phase term so the body does not deform as a rigid affine transform. That
     * last part is what keeps the renderer honest: every particle position is
     * computed and uploaded individually, exactly as it will be when a real
     * solver is producing them.
     */
    private fun writeParticles(body: Int) {
        val age = squashAge[body]
        var verticalScale = 1f
        var horizontalScale = 1f
        var ring = 0f

        if (age >= 0f) {
            val decay = exp(-SQUASH_DECAY * age)
            val amplitude = squashAmplitude[body] * decay
            verticalScale = 1f - amplitude * cosApprox(SQUASH_FREQUENCY * age)
            verticalScale = verticalScale.coerceIn(MIN_SQUASH_SCALE, 1.5f)
            horizontalScale = 1f / verticalScale
            ring = amplitude * RING_GAIN
        }

        val base = body * particlesPerBody
        val cx = centreX[body]
        val cy = centreY[body]
        val hw = halfWidth[body]
        val hh = halfHeight[body]
        val step = 1f / (LATTICE - 1)

        var index = base
        for (row in 0 until LATTICE) {
            val v = row * step
            // Local coordinates in -1..1 so the scale terms act about the
            // body's centre.
            val localY = v * 2f - 1f
            for (column in 0 until LATTICE) {
                val u = column * step
                val localX = u * 2f - 1f

                // Per-particle wobble: highest in the middle of the body,
                // zero at the corners, so the silhouette bulges rather than
                // shearing. Cheap, and enough to make the deformation
                // per-vertex rather than affine.
                val bulge = ring * (1f - localX * localX) * (1f - localY * localY)
                val lateral = ring * sin(localY * BULGE_WAVES) * (1f - localX * localX)

                particleBody[index] = body
                positionX[index] = cx + (localX * horizontalScale + lateral) * hw
                positionY[index] = cy + (localY * verticalScale + bulge * localY) * hh
                index++
            }
        }
    }

    private fun spawn() {
        if (bodyCount >= MAX_BODIES) {
            // The well is full. Reset rather than stop: the client is holding
            // the phone to feel impacts and read frame times, and a toy that
            // silently stops doing anything after 60 pieces gives them neither.
            clearAll()
        }

        val body = bodyCount
        bodyCount++

        val shape = shapeCursor % SHAPE_HALF_EXTENTS.size
        shapeCursor++
        halfWidth[body] = SHAPE_HALF_EXTENTS[shape][0]
        halfHeight[body] = SHAPE_HALF_EXTENTS[shape][1]

        bodyArchetype[body] = spawnCounter % gravitris.app.Palette.PIECE_COUNT
        spawnCounter++

        // Spawn spread across the well rather than always centred, so a player
        // who never drags still fills the well and still sees a stack.
        val span = layout.widthWorld - 2f * halfWidth[body]
        val fraction = SPAWN_OFFSETS[spawnCounter % SPAWN_OFFSETS.size]
        centreX[body] = halfWidth[body] + span * fraction
        centreY[body] = layout.heightWorld + halfHeight[body]
        velocityY[body] = -FALL_SPEED
        squashAge[body] = -1f
        squashAmplitude[body] = 0f

        activeBody = body
        writeParticles(body)

        // Seed the previous-position arrays for the newly added particles so
        // the interpolation lerp does not smear the piece in from wherever the
        // recycled slots happened to be last.
        val base = body * particlesPerBody
        System.arraycopy(positionX, base, prevPositionX, base, particlesPerBody)
        System.arraycopy(positionY, base, prevPositionY, base, particlesPerBody)
    }

    private fun clearAll() {
        bodyCount = 0
        activeBody = -1
    }

    /**
     * Cosine via the sine already imported, so the harness stays on one
     * transcendental. `:core-sim` may not use these at all (ADR 0006 forbids
     * transcendentals in the solver for cross-device determinism) — the
     * harness is not the solver and is not bound by that rule, but it is worth
     * noting the difference so nobody copies this into `:core-sim`.
     */
    private fun cosApprox(x: Float): Float = sin(x + HALF_PI)

    private companion object {
        const val LATTICE = 5
        const val MAX_BODIES = 60
        const val MAX_IMPACTS = 16

        const val DT = 1f / 60f
        const val GRAVITY = -30f
        const val TERMINAL_SPEED = 40f
        const val FALL_SPEED = 1.5f
        const val HARD_DROP_MIN_SPEED = 20f

        const val IMPACT_SPEED_FOR_FULL_ENERGY = 30f
        const val MAX_BODY_AREA = 6f

        const val MAX_SQUASH = 0.45f
        const val MIN_SQUASH_SCALE = 0.25f
        const val SQUASH_DECAY = 6f
        const val SQUASH_FREQUENCY = 22f
        const val RING_GAIN = 0.5f
        const val BULGE_WAVES = 3f
        const val HALF_PI = 1.5707964f

        /** Half-extents in world units. Varied aspect ratios so a tap-rotate
         *  is visible — a square would rotate to itself. */
        val SHAPE_HALF_EXTENTS = arrayOf(
            floatArrayOf(1.2f, 0.6f),
            floatArrayOf(0.6f, 1.2f),
            floatArrayOf(0.9f, 0.9f),
            floatArrayOf(1.5f, 0.5f),
            floatArrayOf(0.6f, 0.6f),
            floatArrayOf(1.0f, 0.7f),
        )

        val SPAWN_OFFSETS = floatArrayOf(0.5f, 0.15f, 0.8f, 0.35f, 0.65f)
    }
}

/**
 * Fixed-capacity impact list. Mirrors `gravitris.game.ImpactList` — no
 * allocation, cleared at the start of each tick.
 */
class MutableImpactList(capacity: Int) : ImpactList {
    override val x = FloatArray(capacity)
    override val y = FloatArray(capacity)
    override val strength = FloatArray(capacity)

    override var count = 0
        private set

    fun clear() {
        count = 0
    }

    fun add(xWorld: Float, yWorld: Float, energy: Float) {
        // Silently dropping the overflow is correct here: impacts are
        // feedback, not state, and the shell plays only the strongest one
        // anyway. Growing the array mid-tick would allocate on the GL thread.
        if (count >= x.size) return
        x[count] = xWorld
        y[count] = yWorld
        strength[count] = min(1f, energy)
        count++
    }
}
