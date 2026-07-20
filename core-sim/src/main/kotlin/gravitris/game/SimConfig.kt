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
    /**
     * How readily the lattice changes **shape**. This is the squash dial, and
     * at Milestone 1 it was 100x too stiff: the client's device showed
     * geometrically perfect squares, and measurement agreed — a hard landing
     * compressed a body's height by 2%.
     *
     * Measured on a hard drop (lattice 5, 10x20 well, 8 substeps), the
     * silhouette a landing produces:
     *
     * | distanceCompliance | height | width | aspect ratio |
     * | ------------------ | ------ | ----- | ------------ |
     * | 1e-6 (Milestone 1) | 0.980  | 1.149 | 1.17         |
     * | **1e-4 (shipped)** | 0.852  | 1.203 | **1.41**     |
     * | 1e-3               | 0.721  | 1.569 | 2.18         |
     *
     * **1e-3 is not available**, and the ceiling is stability rather than
     * cost: softer material compresses far enough that particles of different
     * bodies interpenetrate deeply, and ADR 0003's contacts are rigid, so
     * resolving that depth injects energy. Above ~2e-4 a deep pile or heavy
     * material diverges instead of settling. More substeps do not buy it back
     * — measured, 6 substeps is *worse* than 8 in exactly these cases — so
     * this is not a budget the substep floor can be spent on.
     *
     * Note this is the *shape* dial, not the area one: see [areaCompliance].
     */
    val distanceCompliance: Float = 1e-4f,
    /**
     * How readily the lattice changes **area**. Deliberately left 100x stiffer
     * than [distanceCompliance] so a squashed body bulges sideways rather than
     * losing volume — bulging into gaps is the coverage-band mechanic
     * (ADR 0001), and a body that simply shrinks reads as a rendering bug.
     *
     * This is why `particleCompression` — an area ratio — barely moved when
     * squash was fixed: at impact it spans ~0.895..1.0 both before and after.
     * Area was never the rigid quantity. **Shape was**, and area compression is
     * the wrong number to judge squash by.
     */
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
