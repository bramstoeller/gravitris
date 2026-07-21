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

    // --- quality tier ---
    /**
     * Particles per **cell** edge; a tetromino is four `lattice`x`lattice` cells
     * (ADR 0015). Accepts 4 | 5 | 6, but the shipping tier is **pinned at 4**
     * (ADR 0014): a tetromino is ~4x the material of the old single block, and a
     * near-full well runs ~9 ms device-est at 4 versus ~19 ms at 5 (over the
     * 16.67 ms budget). Pinning also makes `pieceExtent` a single constant, so it
     * no longer varies by tier. 5 and 6 remain for tests and for a future
     * build-time re-pin on a faster reference device; there is no runtime tier
     * selection.
     */
    val lattice: Int = 4,

    // --- well geometry (ADR 0010 — derived from insets at runtime) ---
    // Defaults sized for the shaped game (an I piece is ~9.6 wide at lattice 4).
    // The real geometry comes from the display insets at runtime; these are the
    // construction defaults and what the JVM scenes lean on.
    val wellWidth: Float = 20f,
    val wellHeight: Float = 40f,

    // --- coverage bands (ADR 0004) ---
    val bandCount: Int = 20,
    /**
     * Occupancy-bitmap resolution per band. **Changing either invalidates the
     * tuned [clearThreshold]** — a coarser grid counts a cell as full if any
     * particle touches it at all, so it systematically over-reports fill, and
     * the tuned threshold absorbs exactly that bias (ADR 0004).
     */
    val bandColumns: Int = 40,
    val bandRows: Int = 4,
    /**
     * Fraction of a band's cells that must be occupied for it to clear.
     *
     * **This number is a guess.** The brief says ~90%; nobody has played it.
     * It is therefore not read from here on the hot path — [MechanicTuning]
     * holds the live value and this is only its starting point, so the dial
     * can be turned during a demo without a rebuild.
     *
     * Also **per-lattice**: coarser lattices stamp larger particle disks, so
     * the same pile reads as a different fill percentage at each quality tier.
     * See ADR 0004 and ADR 0009.
     */
    val clearThreshold: Float = 0.90f,

    // --- losing condition (ADR 0005) — NOT YET READ, Stage 4 ---
    val overflowThreshold: Float = 0.50f,
    val graceTicks: Int = 90,
    /**
     * Total kinetic energy below which the stack counts as quiet. Read by the
     * clear rule (ADR 0005: "a clear also requires quiescence"), and by the
     * re-settle watch window.
     *
     * **Never wait on this unboundedly.** A settled pile in this solver does
     * not fully stop — it compacts slowly for as long as it is observed — so
     * every wait on this predicate is paired with a tick ceiling. See
     * [MechanicTuning.clearMaxTicks] and [lockTimeoutTicks].
     */
    val quietKineticEnergy: Float = 0.05f,

    // --- piece lock detection (Stage 3) ---
    /**
     * Kinetic energy **per particle of the active piece** below which the
     * piece counts as settled. Per-particle rather than total so the value
     * does not move when [lattice] changes.
     */
    val lockKineticEnergy: Float = 0.02f,
    /**
     * Consecutive ticks the active piece must stay under
     * [lockKineticEnergy], while touching something, before it locks.
     *
     * A debounce rather than an instantaneous test because a bouncing piece
     * passes through zero velocity at the top of every bounce, and a piece
     * spawns at rest — both would read as settled on a single-tick test.
     */
    val lockDebounceTicks: Int = 12,
    /**
     * Ceiling on how long a piece may remain active before it locks anyway,
     * counted from the tick it first touched something.
     *
     * This exists because "the piece has stopped moving" is not guaranteed to
     * ever become true: a pile in this solver keeps creeping (measured, a
     * settled 16-body pile goes from 142 to 149 contacting particles over 300
     * idle frames). Without a ceiling a piece resting in a live pile could
     * stay active forever and the game would stop dealing pieces.
     */
    val lockTimeoutTicks: Int = 240,

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
    /**
     * Seeds the piece sequence (Stage 3). Two simulations built with the same
     * seed deal the same pieces, which is what makes a replay fixture a
     * fixture rather than a recording.
     */
    val seed: Long = 0L,

    /**
     * Safety valve for frame overrun (ADR 0013). Beyond this many catch-up
     * ticks in one frame the excess wall-clock time is **discarded** — the one
     * and only place time dilation is permitted, and reaching it means the
     * device is below the hardware floor rather than that the game hiccuped.
     * See [FrameDriver].
     */
    val maxCatchupTicks: Int = 8,
) {
    /** Distance between adjacent lattice particles at rest. */
    val spacing: Float get() = PIECE_WIDTH / (lattice - 1)

    /**
     * Contact radius: half the lattice spacing, so adjacent particles within a
     * body exactly touch at rest and the body reads as solid material rather
     * than as a bag of separated dots.
     */
    val particleRadius: Float get() = 0.5f * spacing

    /**
     * Centre-to-centre span of a piece's lattice. **The geometry constant** —
     * this is [PIECE_WIDTH], the same at every quality tier, so a piece's
     * particles occupy the same footprint whether the tier renders 4, 5 or 6 of
     * them per edge (ADR 0009). Pieces-per-row is calibrated against it.
     */
    val pieceWidth: Float get() = spacing * (lattice - 1)

    /**
     * The piece's **material** extent — outer edge to outer edge, i.e.
     * [pieceWidth] plus a [particleRadius] on each side. Use this for anything
     * the player can see (the silhouette, ADR 0011); use [pieceWidth] for where
     * the particles are.
     *
     * Note this varies slightly across tiers (2.40 / 2.25 / 2.16 for lattice
     * 4 / 5 / 6) because the constant is the particle *footprint*, not the
     * rendered surface. Lattice 5 is the shipping tier and the one the client
     * approved the feel of. Whether the constant should instead be the extent —
     * making rendered size tier-invariant at the cost of the approved lattice-5
     * feel — is an open architecture question, see handoff 0019.
     */
    val pieceExtent: Float get() = pieceWidth + 2f * particleRadius

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
        require(bandColumns >= 1) { "bandColumns must be >= 1, was $bandColumns" }
        require(bandRows >= 1) { "bandRows must be >= 1, was $bandRows" }
        require(clearThreshold in 0f..1f) { "clearThreshold must be in 0..1, was $clearThreshold" }
        require(lockKineticEnergy >= 0f) { "lockKineticEnergy must be >= 0, was $lockKineticEnergy" }
        require(lockDebounceTicks >= 1) { "lockDebounceTicks must be >= 1, was $lockDebounceTicks" }
        require(lockTimeoutTicks >= lockDebounceTicks) {
            "lockTimeoutTicks ($lockTimeoutTicks) must be >= lockDebounceTicks ($lockDebounceTicks)"
        }
        require(maxCatchupTicks >= 1) { "maxCatchupTicks must be >= 1, was $maxCatchupTicks" }
        require(wellWidth >= pieceExtent) {
            "wellWidth ($wellWidth) is narrower than a piece ($pieceExtent); no piece could fit"
        }
        require(wellHeight >= pieceExtent) {
            "wellHeight ($wellHeight) is shorter than a piece ($pieceExtent); none could spawn"
        }
    }

    companion object {
        /**
         * Centre-to-centre width of a piece, in world units — the geometry
         * constant every other length is derived from. Constant across quality
         * tiers so the particle footprint does not change with the lattice
         * (ADR 0009). Published to `:app` derivations via [SimState] rather than
         * re-derived there.
         */
        const val PIECE_WIDTH: Float = 1.8f
    }
}
