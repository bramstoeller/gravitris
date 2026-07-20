package gravitris.game

/**
 * Every tunable in the product. Constructed once; a change means a new
 * [Simulation], not mutation — that is what keeps determinism intact
 * (ADR 0006).
 *
 * The field list is fixed by `docs/contracts.md` and is shared with `:app`.
 * Fields belonging to mechanics that do not exist yet (coverage bands, the
 * clear rule, the losing condition, the difficulty ramp) are present because
 * the contract declares them, and are documented below as **not yet read**.
 * See `handoffs/0006-backend-engineer-to-product-lead.md`.
 *
 * Compliance is in simulation units and has no physical meaning (ADR 0001):
 * every value here is a tuned number, not a derived one.
 */
data class SimConfig(
    // --- solver (ADR 0001, 0003) ---
    /**
     * PINNED at 8. ADR 0003 measured this as the *stability floor*, not a
     * quality dial: below 8, a settled pile jitters for some stiffness values
     * and not others, which is worse than uniformly bad because it makes
     * stability depend on a compliance dial the designer expects to be free.
     *
     * Lower values are accepted rather than rejected so the floor can be
     * re-measured (see `SubstepFloorTest`), but shipping below 8 is a defect.
     * Changing this changes simulation results and therefore invalidates every
     * recorded replay fixture.
     */
    val substeps: Int = 8,
    val distanceCompliance: Float = 1e-6f,
    val areaCompliance: Float = 1e-6f,
    /** Per-substep velocity damping, ADR 0003 §5. Without it a pile hums. */
    val linearDamping: Float = 0.005f,
    val friction: Float = 0.55f,
    val gravity: Float = -30f,

    // --- quality tier (ADR 0009) ---
    /** Particles per piece edge: 4 | 5 | 6. A piece is a `lattice`x`lattice` grid. */
    val lattice: Int = 5,

    // --- well geometry (ADR 0010 — derived from insets at runtime) ---
    val wellWidth: Float = 10f,
    val wellHeight: Float = 20f,

    // --- coverage bands (ADR 0004) — NOT YET READ, Stage 3 ---
    val bandCount: Int = 20,
    val bandColumns: Int = 40,
    val bandRows: Int = 4,
    val clearThreshold: Float = 0.90f,

    // --- losing condition (ADR 0005) — NOT YET READ, Stage 4 ---
    val overflowThreshold: Float = 0.50f,
    val graceTicks: Int = 90,
    val quietKineticEnergy: Float = 0.05f,

    // --- difficulty ramp ---
    /**
     * Read as mass **per particle**, not per piece. ADR 0003's stability table
     * measured "per-particle mass 1, 2, 4 and 8", so taking this per-particle
     * is what keeps the default (1.0) on the measured-stable line. The field
     * name suggests per-piece; that ambiguity is flagged for the Architect in
     * the handoff and is a one-line change if the other reading is intended.
     */
    val initialPieceMass: Float = 1f,
    /** NOT YET READ, Stage 4 (difficulty ramp). */
    val massPerLevel: Float = 0.5f,
    /** NOT YET READ, Stage 3 (piece spawning drives fall speed). */
    val initialFallSpeed: Float = 1.5f,

    /**
     * NOT YET READ. Nothing in the simulation is stochastic until piece
     * sequencing lands in Stage 3, so no PRNG is wired up — adding one now
     * would be an unused abstraction. Determinism is currently proven by
     * replay (same construction + same input sequence = bit-identical state),
     * which is the half of ADR 0006's contract that has something to test.
     */
    val seed: Long = 0L,
) {
    init {
        require(substeps >= 1) { "substeps must be >= 1, was $substeps (ADR 0003 floor is 8)" }
        require(lattice in 4..6) { "lattice must be 4, 5 or 6, was $lattice (ADR 0009 quality tiers)" }
        require(wellWidth > 0f) { "wellWidth must be > 0, was $wellWidth" }
        require(wellHeight > 0f) { "wellHeight must be > 0, was $wellHeight" }
        require(bandCount >= 1) { "bandCount must be >= 1, was $bandCount" }
        require(distanceCompliance >= 0f) { "distanceCompliance must be >= 0, was $distanceCompliance" }
        require(areaCompliance >= 0f) { "areaCompliance must be >= 0, was $areaCompliance" }
        require(linearDamping in 0f..1f) { "linearDamping must be in 0..1, was $linearDamping" }
        require(friction >= 0f) { "friction must be >= 0, was $friction" }
        require(initialPieceMass > 0f) { "initialPieceMass must be > 0, was $initialPieceMass" }
        // Downward is negative. Zero is allowed and used — a weightless scene
        // isolates constraint behaviour from settling. Positive is always a
        // sign error, and a non-finite value poisons every position in the
        // world within one tick with no other symptom.
        require(gravity.isFinite() && gravity <= 0f) {
            "gravity must be finite and <= 0 (downward is negative), was $gravity"
        }
        require(wellWidth >= PIECE_WIDTH) {
            "wellWidth ($wellWidth) is narrower than a piece ($PIECE_WIDTH); no piece could fit"
        }
    }

    companion object {
        /**
         * Width of a piece in well units, edge particle to edge particle.
         *
         * Fixed, not derived from [wellWidth]: a narrower well should hold
         * fewer pieces per row, not smaller pieces. 1.8 is the spike's value
         * (`/work/spike/solver-budget/`, "about 2 well-units across, like a
         * classic tetromino") and every measured number in ADR 0001 and
         * ADR 0003 was taken at it.
         */
        const val PIECE_WIDTH: Float = 1.8f
    }
}
