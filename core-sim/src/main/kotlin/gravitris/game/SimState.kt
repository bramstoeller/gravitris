package gravitris.game

/**
 * Simulation output, read by `:app` to build its vertex buffer.
 *
 * **Read-only by CONVENTION, not by compiler.** Arrays are exposed directly
 * rather than copied because a per-frame defensive copy of 1 500 particles is
 * ~12 KB of garbage per frame, which is exactly the GC pressure ADR 0001 chose
 * a structure-of-arrays layout to avoid. The renderer must never write to
 * them. ADR 0008 makes code review the enforcement mechanism, and says so
 * explicitly rather than pretending the type system helps here.
 *
 * All particle arrays are valid in `[0, particleCount)` and all body arrays in
 * `[0, bodyCount)`. They are longer than that — capacity is allocated once at
 * construction so the per-frame path allocates nothing — so **never** use
 * `array.size` as a loop bound.
 */
interface SimState {
    // --- particles, for the vertex buffer ---
    val particleCount: Int
    val positionX: FloatArray
    val positionY: FloatArray

    /**
     * Position at the **start of the current tick**, for render interpolation
     * (ADR 0006): `lerp(prevPosition, position, accumulator / TICK)`.
     *
     * Note this is the frame-start position, not the solver's internal
     * per-substep previous position. ADR 0006 says "the solver already retains
     * previous positions for its own integration", which conflates the two;
     * interpolating against a substep-previous position would cover only 1/8
     * of a tick and produce visible stutter. These are separate arrays.
     */
    val prevPositionX: FloatArray
    val prevPositionY: FloatArray

    /** Index into the body arrays. */
    val particleBody: IntArray

    /** Body-local lattice coordinate, 0..1. -> `vBodyUv` */
    val particleU: FloatArray
    val particleV: FloatArray

    /**
     * Current area / rest area of the lattice cells this particle belongs to.
     * 1 = undeformed, < 1 = compressed, > 1 = stretched. -> `vCompression`
     */
    val particleCompression: FloatArray

    /**
     * Free-surface boundary: 0 interior, 1 on the body's outer edge. Static
     * per particle. Drives the BRIGHTENING rim light. -> `vEdge`
     */
    val particleEdge: FloatArray

    /**
     * Contact occlusion: 0 = no neighbour, 1 = fully pressed against other
     * material. Derived from the contact solve. Drives the DARKENING seam
     * between touching pieces. -> `vContact`
     *
     * Deliberately separate from [particleEdge]: a free surface against empty
     * space and a contact surface against a neighbour render oppositely.
     *
     * Includes contact with the well floor and walls, not only with other
     * pieces — a piece resting on the floor reads as pressed against
     * something. If UX wants boundary contact excluded, that is a one-line
     * change in the contact solve.
     */
    val particleContact: FloatArray

    // --- bodies ---
    val bodyCount: Int

    /**
     * Piece archetype index, NOT a hue. `:app` owns the palette and indexes it
     * with this. -> `vBodyIndex` (flat)
     */
    val bodyArchetype: IntArray

    /** Particles per piece edge (per cell); the same for every body in a run. */
    val bodyLattice: Int

    /**
     * Particle count of one body, uniform for the whole run. Size vertex buffers
     * and the per-body/per-cell stride against **this**, not `bodyLattice²`.
     *
     * A single-square piece has `bodyLattice²` particles. A tetromino
     * (ADR 0015) is four cells, so `4·bodyLattice²`, and the renderer draws it
     * as `particlesPerBody / bodyLattice²` cells, each reusing [triangleIndices]
     * at a `cell·bodyLattice²` vertex offset. Constant across all seven piece
     * types — the single reused index buffer of ADR 0007 still holds, at cell
     * granularity. Published so `:app` need not know the cell count to size its
     * buffers.
     */
    val particlesPerBody: Int

    /**
     * How far the material surface extends beyond a particle's centre, in
     * world units. Half the lattice spacing, so adjacent particles inside a
     * body exactly touch at rest.
     *
     * **This is the difference between where a body is and where it is drawn**,
     * and it is published because the renderer was drawing the second one.
     * Every position in this contract is a particle *centre*; the solver treats
     * the material as extending [particleRadius] past it. Contacts hold two
     * touching bodies' particle centres exactly `2 * particleRadius` apart, and
     * boundary contacts hold a resting body's centres exactly
     * [particleRadius] above the floor — both measured, both exact.
     *
     * So a mesh built from these positions alone is inset by this much on every
     * side: two bodies pressed together with a *measured* surface gap of zero
     * draw with a visible gap of `2 * particleRadius`, which at lattice 5 is
     * 0.45 world units — a quarter of a piece's width. That is the "margin
     * around the blocks" the client reported, and it is a rendering inset, not
     * a physics separation.
     *
     * Consumers that need the material's true extent — the mesh outline, and
     * ADR 0004's occupancy stamp, which specifies stamping each particle's
     * *disk* — must expand by this radius rather than re-derive it from
     * [SimConfig.PIECE_WIDTH] and [bodyLattice]. A re-derived copy is one that
     * drifts silently the first time the original changes.
     */
    val particleRadius: Float

    /**
     * Upper bound on [particleCount] for this simulation's lifetime.
     *
     * Body capacity is derived from the well's area rather than configured
     * (`SimConfig` carries no capacity field, and adding one would cross a
     * module boundary), so `:app` cannot compute it without reproducing the
     * derivation — and a reproduced derivation is one that drifts silently the
     * first time the original changes. The renderer needs a real bound to size
     * its vertex and index buffers against and to know when the well is full,
     * so the bound is published rather than guessed.
     *
     * Additive per `docs/contracts.md` §5: adding a field to `SimState` does
     * not cross the module boundary.
     */
    val particleCapacity: Int

    // --- rendering topology (static per tier, ADR 0007) ---
    /**
     * Body-local triangle indices. Valid for one body and reused for every body
     * with an offset of `bodyIndex * bodyLattice * bodyLattice`. Constant for
     * the whole run.
     *
     * **Length is `6 * (bodyLattice - 1) * (bodyLattice - 1)`** — six indices
     * per lattice cell, two triangles each. The *values* it contains run
     * `0 until bodyLattice * bodyLattice` (the particle indices), a different
     * and smaller number: 96 versus 25 at lattice 5. Size an index buffer from
     * the length, never from the value range.
     *
     * For the cell at lattice row `r`, column `c` — row 0 at the bottom, so
     * world `y` increases with `r` — name the four corners by particle index
     * (`L = bodyLattice`):
     * ```
     * p00 = r*L + c        p10 = r*L + c + 1
     * p01 = (r+1)*L + c    p11 = (r+1)*L + c + 1
     * ```
     * The two triangles are emitted **counter-clockwise**:
     * ```
     * p00, p10, p11    then    p00, p11, p01
     * ```
     * so **each cell splits on the `p00`–`p11` diagonal** (bottom-left to
     * top-right). That diagonal is not the consumer's to re-derive: the solver
     * defines its two *area* constraints per cell on these exact two triangles
     * (`SoftBodyWorld.addConstraints`), and [particleCompression] is their
     * current/rest area ratio — split the other diagonal and the rendered
     * halves are not the halves the solver measured, so a sheared cell creases
     * the wrong way, visible in motion. `:app`'s `TopologyMatchesSolverTest`
     * holds its topology to this array index-for-index.
     */
    val triangleIndices: IntArray

    // --- coverage (ADR 0004, 0007) ---
    /**
     * Per-band fill, 0..1, bottom band first. Drives the band glow and the
     * clear rule (ADR 0004).
     *
     * **Look this up by world-space Y, never by body**: `band = (y -
     * bandBottomY) / bandHeight`. Fill is measured over the whole well by
     * stamping every particle's disk into an occupancy bitmap, so a body
     * spanning three bands contributes to all three independently and there is
     * no per-body coverage number anywhere in the system. That is what makes
     * the glow a property of a *zone* — two fragments at the same height in
     * different pieces read the identical value automatically, which
     * `docs/ux/band-glow.md` calls the strongest single legibility cue the
     * game has.
     *
     * Note a band (`bandHeight`, 1.0 world units by default) is well under
     * half a piece's height (`SimConfig.pieceExtent`, 2.40), so one piece
     * spans about three bands and can legitimately show three different fill
     * values across its own height. Sample per fragment, not per vertex.
     *
     * **Damped, not instantaneous.** A band's raw fill spikes for a few frames
     * during the bounce of a heavy landing, and an undamped value would flash
     * the well amber on every hard drop — teaching the player a rule that is
     * not the rule. The clear rule reads this same damped value: the damping is
     * deliberately its quiescence gate, so a spike glows briefly here but
     * cannot trigger a clear. **This does not make "glow crossed threshold" a
     * clear**, though — a clear also requires a piece to lock and a body to
     * actually sit in the band, and the threshold is runtime-tunable and
     * invisible to the shader. Drive clear visuals off [bandClearProgress],
     * never off this crossing a value.
     */
    val bandFill: FloatArray
    val bandBottomY: Float

    /**
     * Number of coverage bands, and the length of both [bandFill] and
     * [bandClearProgress].
     *
     * Published for the same reason those arrays must not be measured with
     * `.size`: the renderer's `uBandFill` is a fixed-size GLSL uniform array
     * whose length is baked into the shader at compile time, so `:app` needs
     * this to assert the two agree rather than discover a mismatch as
     * out-of-range indexing, which is undefined behaviour in GLSL. Reading it
     * from here rather than from a default-constructed `SimConfig` is what keeps
     * the assertion honest once ADR 0010 varies the well geometry at runtime.
     */
    val bandCount: Int

    /** `wellHeight / bandCount`. */
    val bandHeight: Float

    /**
     * Clear-envelope progress per band: -1 = not clearing, else 0..1.
     *
     * Fill alone cannot drive the clear animation — a band at fill 1.0 and a
     * band mid-dissolve are indistinguishable — so this is the signal the
     * ignition flash and the dissolve hang off. Within the envelope
     * (`MechanicTuning.clearEnvelopeTicks`, 24 ticks / 400 ms by default) the
     * phases from `docs/ux/feel-feedback.md` fall at:
     *
     * | progress | phase |
     * | -------- | ----- |
     * | 0.00 – 0.29 | ignition flash, 120 ms — the one moment the emissive cap may be exceeded |
     * | 0.29 – 0.50 | hold at full brightness, 80 ms |
     * | 0.50 – 1.00 | dissolve, 200 ms |
     *
     * The material is still present for all of it; it is removed on the tick
     * progress reaches 1, at which point this returns to -1 and [bandFill]
     * collapses. What follows is the stack dropping under real physics, which
     * is not part of this envelope and has no fixed duration.
     */
    val bandClearProgress: FloatArray

    /**
     * The band a new piece spawns into.
     *
     * Published because ADR 0005 builds the losing condition on the fill of
     * the spawn region, which only means anything if the spawn region is one
     * of the coverage bands. It is, and this is which one.
     */
    val spawnBandIndex: Int

    // --- game ---
    /** [Phase.Playing] or [Phase.Clearing]. Overflow and game over are Stage 4. */
    val phase: Phase

    /**
     * Ticks elapsed since construction. The simulation's own clock, and the
     * only clock anything in the game may be timed against (ADR 0013) — a
     * duration derived from wall-clock would stretch on a device that drops
     * frames, and the client ruled that out explicitly.
     */
    val tick: Int

    /** **Stage 1: always 0.** Scoring is Stage 4. */
    val score: Int

    /** **Stage 1: always 1.** The difficulty ramp is Stage 4. */
    val level: Int

    /** The body currently under player control, or -1 when none. */
    val activePieceBody: Int

    /**
     * Which phase the active piece is in, for gating input (ADR 0016).
     *
     * **Defaults to [PiecePhase.FALLING] whenever [activePieceBody] < 0** — never
     * POSITIONING — so a stray drag can never slide a piece that is not being
     * positioned. The guarantee the shell relies on:
     * `activePiecePhase == POSITIONING ⇒ activePieceBody >= 0`.
     *
     * - **POSITIONING**: parked at the spawn row, gravity suppressed; the player
     *   slides it horizontally and releases it (or the window expires) to drop.
     * - **FALLING**: falling under real gravity; the player rotates it. Deform,
     *   settle and lock all happen here — there is no separate settling phase.
     */
    val activePiecePhase: PiecePhase

    /**
     * Ticks left in the positioning window, or **0** when not positioning
     * (ADR 0016). Draw the urgency countdown as
     * `positioningTicksRemaining / positioningWindowTicks`.
     */
    val positioningTicksRemaining: Int

    /**
     * Length of the current positioning window in ticks — the live
     * `MechanicTuning.positioningTicks`. Published so the shell draws a 0..1
     * countdown without re-deriving it (a re-derived copy drifts the first time
     * the dial is turned).
     */
    val positioningWindowTicks: Int

    val landing: LandingEstimate

    // --- feedback, drained by the shell each frame ---
    /** Cleared by the core at the start of each tick. */
    val impacts: ImpactList

    /**
     * Total kinetic energy of the stack. The stability metric, and later the
     * "is the stack quiet" predicate for lock detection (Stage 3) and the
     * losing condition (ADR 0005).
     */
    val kineticEnergy: Float
}

sealed interface Phase {
    object Playing : Phase

    /**
     * The spawn band is over the overflow line and a piece is due; the stack is
     * being given [remainingTicks] more ticks to settle back below it before the
     * game ends (ADR 0005). If it settles, play resumes; if the grace runs out
     * with the band still over the line, the phase becomes [GameOver].
     *
     * [remainingTicks] is a `var` mutated in place, and the shell must **read it,
     * not retain it** — the same bargain [Clearing] makes, and for the same
     * reason: one instance is reused across an overflow so the grace does not
     * allocate on the per-frame path. A captured `Overflow` will have changed
     * underneath a tick later.
     */
    data class Overflow(var remainingTicks: Int) : Phase

    /**
     * A band cleared; the stack is dropping and re-settling.
     *
     * [remainingTicks] is a `var`, and is mutated in place as the sequence
     * runs. That is the same read-only-by-convention bargain [SimState]'s
     * arrays already make, for the same reason: a fresh instance per tick
     * would put an allocation on the per-frame path, and ADR 0001 chose this
     * whole layout to keep the steady-state tick allocating nothing. One
     * instance is allocated per clear *event* — a handful of times a minute,
     * not sixty times a second.
     *
     * The shell must therefore read it, not retain it: a `Clearing` captured
     * and compared a second later will have changed underneath.
     */
    class Clearing(val bands: IntArray, var remainingTicks: Int) : Phase {
        // IntArray uses identity equals/hashCode, which would make two
        // structurally identical Clearing phases compare unequal and break
        // any state-comparison test. Generated by hand for that reason.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Clearing) return false
            return remainingTicks == other.remainingTicks && bands.contentEquals(other.bands)
        }

        override fun hashCode(): Int = 31 * bands.contentHashCode() + remainingTicks
    }

    object GameOver : Phase
}

/**
 * The phase of the active piece, for input gating (ADR 0016). Orthogonal to the
 * game-level [Phase]: it only qualifies *how* the player's intents are read
 * while a piece is active, and is [FALLING] whenever no piece is active.
 */
enum class PiecePhase {
    /** Parked at the spawn row, gravity suppressed; the player slides and releases it. */
    POSITIONING,

    /** Falling under real gravity; the player rotates it. Deform/settle/lock happen here. */
    FALLING,
}

/**
 * Projected settle position for the active piece, for the landing silhouette.
 *
 * **Stage 1: [valid] is always false.** The silhouette is Stage 4 work
 * (docs/build-order.md Track 4B). The contract already specifies that `:app`
 * holds the last valid value briefly and then fades out rather than showing
 * stale data, so a permanently-invalid estimate is a supported state and the
 * frontend needs no special case for it.
 */
interface LandingEstimate {
    val yLow: Float
    val yHigh: Float
    val xMin: Float
    val xMax: Float
    val valid: Boolean
}

/**
 * Impact events from this tick, for haptics, shake and audio. Fixed capacity,
 * no allocation. Valid in `[0, count)`.
 */
interface ImpactList {
    val count: Int
    val x: FloatArray
    val y: FloatArray

    /** Scaled by mass and impact speed, 0..1. */
    val strength: FloatArray
}
