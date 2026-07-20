package gravitris.app.toy

import gravitris.app.Palette
import gravitris.game.InputFrame
import gravitris.game.SimConfig
import gravitris.game.SimState
import gravitris.game.Simulation

/**
 * # The Milestone 1 toy. NOT game rules.
 *
 * `docs/build-order.md` Milestone 1 is one soft-body piece falling into an
 * empty well: drag it, drop it, watch it squash and settle. There is no piece
 * sequence, no lock rule, no coverage, no clearing, no losing and no score, and
 * this class must not grow any of them — the milestone exists to answer one
 * question ("does the squish feel heavy?") and anything else dilutes the
 * answer.
 *
 * What it does own is the small amount of *staging* a toy needs to be usable
 * for more than four seconds: something to drop, and a way to start over when
 * the well fills. The backend engineer built [Simulation.addPiece] and
 * [Simulation.clearActivePiece] as "a Milestone 1 harness affordance, not the
 * spawner", anticipating exactly this call site.
 *
 * ## Why the release rule is here and not in `:core-sim`
 *
 * Deciding a piece is finished is, in embryo, Stage 3's lock rule — and Stage 3
 * will want it in the core, where it can be deterministic and replayable. It is
 * deliberately *not* there yet, because a lock rule that exists before the
 * physics has been felt is a rule that gets tuned twice. So this is the crudest
 * thing that works, it lives on the shell side of the boundary where it cannot
 * affect simulation results, and it is written to be deleted.
 *
 * **Nothing here may be promoted to a game rule without the lock rule being
 * designed properly.** Releasing the piece has no consequence beyond handing
 * the player a new one: it does not score, lock, clear or end anything.
 */
class SquishToy(
    private val config: SimConfig,
    /**
     * Upper rail on bodies, so the toy resets on a well that never quite blocks
     * the spawn point. Not a losing condition — see [reset].
     */
    private val maxBodies: Int,
) {

    private var simulation = Simulation(config)

    private var archetypeCursor = 0

    /** Consecutive ticks the active piece has been quiet and untouched. */
    private var settledTicks = 0

    /** How many times the well has been emptied, for the readout. */
    var resets: Int = 0
        private set

    init {
        // The renderer sizes its vertex and index buffers from [maxBodies], so
        // a core whose derived capacity is smaller would throw out of
        // `addPiece` partway through a session rather than here. Checked once,
        // loudly, at construction.
        val required = maxBodies * config.lattice * config.lattice
        check(simulation.state.particleCapacity >= required) {
            "the simulation's derived capacity (${simulation.state.particleCapacity} particles) " +
                "is below the $required the shell is configured for ($maxBodies bodies at " +
                "lattice ${config.lattice}); lower Tunables.TOY_MAX_BODIES or widen the well"
        }
    }

    val state: SimState get() = simulation.state

    /**
     * Advance one fixed tick.
     *
     * The piece is added *before* the step so it exists for the tick it was
     * requested on, and released *after*, so the quietness test reads the
     * positions the step just produced rather than the previous tick's.
     */
    fun step(input: InputFrame) {
        if (simulation.state.activePieceBody < 0) {
            if (wellIsFull()) reset() else addNextPiece()
        }
        simulation.step(input)
        updateRelease(input)
    }

    /**
     * Empty the well and start again.
     *
     * A toy affordance, not a losing condition. The client is holding the phone
     * to feel impacts, and a toy that silently stops responding once the well
     * fills gives them nothing to feel. `SimConfig` is immutable by design
     * (ADR 0006 — a change means a new [Simulation], not mutation), so starting
     * over means constructing one.
     */
    fun reset() {
        simulation = Simulation(config)
        settledTicks = 0
        resets++
    }

    /**
     * True when material has reached the top of the well.
     *
     * Deliberately tested against the well's own height rather than against the
     * spawn footprint's exact geometry: [Simulation.addPiece] *throws* on an
     * overlapping placement, and the backend engineer is explicit that this
     * must not be caught and ignored, so the shell has to be certain before it
     * calls rather than sorry afterwards.
     *
     * Pieces are seeded a full piece extent above the well's top edge,
     * and half a piece plus its contact radius is less than that clearance, so
     * a spawn footprint cannot reach down to `wellHeight`. No particle above
     * `wellHeight` therefore implies no overlap, with margin, using only
     * published contract values.
     */
    private fun wellIsFull(): Boolean {
        val state = simulation.state
        if (state.bodyCount >= maxBodies) return true
        for (i in 0 until state.particleCount) {
            if (state.positionY[i] > config.wellHeight) return true
        }
        return false
    }

    private fun addNextPiece() {
        // Cycled, not random. Milestone 1 has no piece sequence to design, and
        // a deterministic order means two runs on the client's phone are
        // comparable — including the frame-time readings taken off them.
        //
        // Bounded by the PALETTE, not by Simulation.ARCHETYPE_COUNT: the core
        // offers seven archetypes and docs/ux/piece-identity.md specifies six
        // hues, so cycling to the core's count would index past the last piece
        // colour and draw a piece in the well-surface grey.
        val archetype = archetypeCursor % Palette.PIECE_COUNT
        archetypeCursor++

        simulation.addPiece(
            archetype = archetype,
            centerX = config.wellWidth * 0.5f,
            centerY = config.wellHeight + config.pieceExtent,
        )
        settledTicks = 0
    }

    /**
     * Release the active piece once it has stopped moving, so the player is
     * handed another one.
     *
     * Input suppresses the test outright rather than merely resetting the
     * counter's threshold: [Simulation] applies a drag and a rotation
     * *kinematically*, moving the previous-position buffers in lockstep so the
     * move injects no velocity. That is correct physics and it means a piece
     * being dragged along the floor reads as perfectly still by this measure.
     * Without this branch, dragging a settled piece would take it away from the
     * player mid-gesture.
     */
    private fun updateRelease(input: InputFrame) {
        val body = simulation.state.activePieceBody
        if (body < 0) return

        if (input.dragX != 0f || input.rotate || input.hardDrop) {
            settledTicks = 0
            return
        }

        if (!isQuiet(body)) {
            settledTicks = 0
            return
        }

        settledTicks++
        if (settledTicks >= SETTLE_TICKS) {
            simulation.clearActivePiece()
            settledTicks = 0
        }
    }

    /**
     * Whether every particle of [body] moved less than the quiet threshold over
     * the tick just stepped.
     *
     * Derived from `position - prevPosition`, which the contract publishes for
     * render interpolation, rather than from `SimState.kineticEnergy` — that is
     * the whole stack's energy, so a settled piece would read as noisy whenever
     * anything else in the well was still moving.
     *
     * Particles are found by scanning `particleBody` rather than by indexing a
     * `body * particlesPerBody` block. The contract does not promise that
     * layout, and assuming it would be a silent misread the first time the core
     * compacts its arrays. The scan is over at most a few hundred particles,
     * once per tick.
     */
    private fun isQuiet(body: Int): Boolean {
        val state = simulation.state
        val limit = QUIET_SPEED_WORLD_PER_S * Simulation.TICK
        val limitSquared = limit * limit
        for (i in 0 until state.particleCount) {
            if (state.particleBody[i] != body) continue
            val dx = state.positionX[i] - state.prevPositionX[i]
            val dy = state.positionY[i] - state.prevPositionY[i]
            if (dx * dx + dy * dy > limitSquared) return false
        }
        return true
    }

    companion object {

        /**
         * World units per second below which a particle counts as still.
         *
         * Measured, not guessed: a settled body in this solver sits around
         * 1e-4 units/s, and a falling one is in the tens. Anywhere in that gap
         * behaves identically, so this is set well clear of the noise floor
         * rather than tuned.
         */
        const val QUIET_SPEED_WORLD_PER_S: Float = 0.05f

        /**
         * A third of a second of stillness before the piece is handed over.
         *
         * Measured cadence for one drop into an empty 10x20 well is about 210
         * ticks (3.5 s): roughly 135 falling, 55 ringing down to stillness, and
         * this. The piece is at 0.01 units/s — two hundred times under the
         * threshold — well before the count expires, so this is a
         * "has visibly stopped" delay for the player's benefit rather than a
         * margin the measurement needs.
         *
         * The fall dominates that budget, and it is longer than free fall
         * suggests: `linearDamping` caps a falling particle at about 12.2
         * units/s, well below the solver's own 30-unit terminal clamp, which
         * only a hard drop reaches. Flagged rather than compensated for — the
         * fall speed is the physics, and Milestone 1 exists to have the physics
         * judged rather than tuned around.
         */
        const val SETTLE_TICKS: Int = 20
    }
}
