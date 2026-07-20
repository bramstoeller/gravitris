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

    /** Particles per piece edge; the same for every body in a run. */
    val bodyLattice: Int

    // --- rendering topology (static per tier, ADR 0007) ---
    /**
     * Body-local triangle indices. Valid for one body and reused for every
     * body with an offset of `bodyIndex * bodyLattice * bodyLattice`.
     * Constant for the whole run.
     *
     * **Length is `6 * (bodyLattice - 1) * (bodyLattice - 1)`** — six indices
     * per lattice cell, two triangles each. The *values* it contains run
     * `0 until bodyLattice * bodyLattice`, which is a different number: 96
     * versus 25 at lattice 5. Do not size a buffer from the value range.
     *
     * **Winding is counter-clockwise** and the cell is split on the
     * `p00`–`p11` diagonal:
     * ```
     * p00, p10, p11   then   p00, p11, p01
     * ```
     * where `pRC = row * bodyLattice + col`, row 0 at the bottom. The
     * diagonal is not a free choice for the consumer to re-derive: these are
     * *deforming* cells, and splitting on the other diagonal makes a sheared
     * cell crease the wrong way, which is visible in motion.
     */
    val triangleIndices: IntArray

    // --- coverage (ADR 0004, 0007) ---
    /**
     * Per-band fill, 0..1.
     *
     * **Stage 1: always 0.** Coverage bands are Stage 3 work and are
     * deliberately not built yet (docs/build-order.md: the clear rule "is
     * untunable until the physics is felt. Building it early means tuning it
     * twice."). The array exists at the right size so `:app` compiles and can
     * wire `uBandFill` now.
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
     * this to assert the two agree rather than to discover the mismatch as
     * out-of-range indexing, which is undefined behaviour in GLSL.
     */
    val bandCount: Int

    /** `wellHeight / bandCount`. */
    val bandHeight: Float

    /**
     * Clear-envelope progress per band: -1 = not clearing, else 0..1.
     *
     * **Stage 1: always -1.** See [bandFill].
     */
    val bandClearProgress: FloatArray

    // --- game ---
    /** **Stage 1: always [Phase.Playing].** Overflow and clearing are Stage 3/4. */
    val phase: Phase

    /** **Stage 1: always 0.** Scoring is Stage 4. */
    val score: Int

    /** **Stage 1: always 1.** The difficulty ramp is Stage 4. */
    val level: Int

    /** The body currently under player control, or -1 when none. */
    val activePieceBody: Int

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

    /** Spawn region blocked; the stack is being given time to settle (ADR 0005). */
    data class Overflow(val remainingTicks: Int) : Phase

    /** A band cleared; the stack is dropping and re-settling. */
    data class Clearing(val bands: IntArray, val remainingTicks: Int) : Phase {
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
