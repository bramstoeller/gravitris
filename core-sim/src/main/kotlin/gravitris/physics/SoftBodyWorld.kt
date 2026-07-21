package gravitris.physics

import gravitris.game.SimConfig
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Particle, constraint and body storage for the soft-body solver, in a
 * structure-of-arrays layout.
 *
 * **SoA is adopted for allocation, not for cache.** ADR 0001 measured the
 * layout at 1–3% at the scale this game runs (100–255 KB working set, which
 * fits in L2); the crossover to a real cache win only appears an order of
 * magnitude past our budget. The payoff that does hold is that primitive
 * arrays let the steady-state loop allocate nothing, and on ART a per-frame
 * allocation stream buys GC pauses, and a pause on a 16.67 ms budget is a
 * dropped frame. Every array here is sized to capacity once, at construction.
 *
 * A body is a `lattice` x `lattice` grid of particles held together by
 * structural and shear distance constraints plus two area constraints per
 * lattice cell, so it resists collapsing flat rather than only resisting
 * stretch (ADR 0001). For `lattice` 4/5/6 that is 60/104/160 constraints per
 * body, which reproduces ADR 0001's measured constraint counts exactly.
 */
internal class SoftBodyWorld(val config: SimConfig) {

    // --- geometry -----------------------------------------------------------

    /** Particles along one cell edge (the quality tier, ADR 0009). */
    val lattice: Int = config.lattice

    /** Cells in a piece — a tetromino, so four (ADR 0015). */
    val cellsPerBody: Int = PieceShapes.CELLS

    /** Particles in one `L×L` cell. */
    val particlesPerCell: Int = lattice * lattice

    /**
     * Particles in a whole piece: four cells (ADR 0015), constant across all
     * seven shapes. The shape lives in the cells' rest positions, not in this
     * count, which is what keeps every fixed-stride assumption below intact.
     */
    val particlesPerBody: Int = cellsPerBody * particlesPerCell

    /**
     * Full material width of a body, outer edge to outer edge. **The gameplay
     * constant** — everything below is derived from it, not the other way
     * round (ADR 0011), so changing [lattice] leaves piece size untouched.
     */
    val pieceExtent: Float = config.pieceExtent

    /** Distance between adjacent lattice particles at rest. */
    val spacing: Float = config.spacing

    /**
     * Contact radius. Half the lattice spacing, so adjacent particles within a
     * body exactly touch at rest and the body reads as solid material rather
     * than as a bag of separated dots.
     */
    val particleRadius: Float = config.particleRadius

    /** Centre-to-centre span of the lattice: [pieceExtent] less a radius a side. */
    val pieceWidth: Float = config.pieceWidth

    // Well bounds. The floor is y = 0 and the left wall is x = 0, so band
    // geometry (ADR 0004) starts at the origin and `:app` can map insets onto
    // it directly.
    val wellMinX: Float = 0f
    val wellMaxX: Float = config.wellWidth
    val wellFloorY: Float = 0f

    // --- capacity -----------------------------------------------------------

    /**
     * Derived rather than configured: `SimConfig` carries no body-capacity
     * field and adding one would cross a module boundary (docs/contracts.md).
     * Enough bodies to pack the well twice over. A tetromino covers
     * [cellsPerBody] cells, so the divisor is that many piece-extents of area,
     * not one (ADR 0015). Floor of 32 so a narrow benchmark well still holds the
     * reference scene.
     */
    val maxBodies: Int = max(
        32,
        2 * ceil(
            (config.wellWidth * config.wellHeight) /
                (cellsPerBody * pieceExtent * pieceExtent).toDouble(),
        ).toInt(),
    )

    val particleCapacity: Int = maxBodies * particlesPerBody

    // Constraints per cell (as a single square lattice, ADR 0001) and per seam
    // (the bridge that welds two adjacent cells into one body, ADR 0015).
    private val cellDistance: Int = 2 * lattice * (lattice - 1) + 2 * (lattice - 1) * (lattice - 1)
    private val cellArea: Int = 2 * (lattice - 1) * (lattice - 1)

    // A seam bridges two facing `L`-particle edges: `L` structural + `2(L-1)`
    // shear distance constraints, and `2(L-1)` area constraints on the seam
    // cells. Every body reserves [MAX_SEAMS] seams' worth so the stride is
    // constant; a tree shape (three seams) pads the fourth inert (ADR 0015).
    private val seamDistance: Int = lattice + 2 * (lattice - 1)
    private val seamArea: Int = 2 * (lattice - 1)

    // A junction is the single 2r x 2r square where FOUR cells meet at a point —
    // the O alone has one (ADR 0018). The seam model is strictly edge-to-edge, so
    // four pairwise seams clip its corners and leave the render mesh open there: a
    // hole straight through the O's centre (handoff 0040). It is closed by two
    // RENDER triangles and NO solver constraint. Two facts make that the right
    // fix, both measured, not assumed:
    //  - The square's four edges are already welded by the four seams' structural
    //    constraints, so the hole cannot reopen and the centre cannot tear open.
    //  - Backing the fill with the obvious near-rigid AREA constraint destabilises
    //    a heavy pile: a mass-8 O-bearing pile that used to settle rings at KE ~0.4
    //    and never falls below the 0.05 quiet line over 6000 ticks, because the
    //    single global areaCompliance makes the extra centre constraint a stiff
    //    mode XPBD cannot damp in 8 substeps. The O settled fine before; the defect
    //    was render-only, so the fix is render-only. particleCompression is
    //    per-particle (accumulated from surrounding constraints), so the fill
    //    triangle shades correctly by interpolating its corners without one of its
    //    own. See ADR 0018's centre-junction section.
    private val junctionTriangles: Int = 2

    private val distancePerBody: Int = cellsPerBody * cellDistance + MAX_SEAMS * seamDistance
    private val areaPerBody: Int = cellsPerBody * cellArea + MAX_SEAMS * seamArea

    // --- piece shapes (ADR 0015) --------------------------------------------
    //
    // Everything about a shape that does not depend on where it is spawned is
    // precomputed once here, so [addBody] stays a straight fill of pre-sized
    // arrays and allocates nothing on the spawn path.

    /** Body-local rest offset of each particle from the piece's bbox centre. */
    private val shapeLocalX = Array(PieceShapes.COUNT) { FloatArray(particlesPerBody) }
    private val shapeLocalY = Array(PieceShapes.COUNT) { FloatArray(particlesPerBody) }

    /** Free-surface flag per particle: 1 on the shape's true outline, 0 inside (incl. seams). */
    private val shapeEdge = Array(PieceShapes.COUNT) { FloatArray(particlesPerBody) }

    /**
     * Per-direction free-surface mask per particle (§17, D11): a bitwise OR of
     * [FREE_LEFT]/[FREE_RIGHT]/[FREE_DOWN]/[FREE_UP], set on the side a particle
     * has free surface on and clear on a side facing a neighbouring cell (a
     * seam). It is the per-direction detail behind [shapeEdge], which is only its
     * OR — `shapeEdge == 1 ⇔ mask != 0`.
     *
     * `:app` needs the direction, not just the fact, of the free surface: the
     * ADR 0011 silhouette extrusion must push a boundary particle out ONLY along
     * the sides that are true outline and leave the seam-facing sides where they
     * are, so the per-archetype bridge geometry ([bodyTriangleIndices]) fills the
     * 2r seam at its natural width rather than the extrusion collapsing the two
     * facing columns coincident (which is what put the UV discontinuity — the
     * visible "+" — right at the join). See handoff 0038 and ADR 0018.
     */
    private val shapeFreeEdges = Array(PieceShapes.COUNT) { IntArray(particlesPerBody) }

    /**
     * Body-local surface UV per particle, precomputed per shape (§15, D10).
     *
     * Aspect-preserving and body-wide: both axes are divided by the SAME
     * footprint span (the longer of the two, in particle units), so the
     * coordinate runs 0..1 across the whole tetromino's longer side and 0..k
     * (k<=1) across the shorter one. It is continuous across cell seams — a
     * particle one row up in the next cell reads one grid step further along,
     * not a fresh 0 — so the grain, the subsurface depth and the specular sweep
     * all read across the entire piece instead of restarting per cell (the "four
     * separate squares" complaint). Dividing both axes by one span keeps the
     * pattern isotropic; per-axis 0..1 would stretch the grain on the I piece.
     */
    private val shapeU = Array(PieceShapes.COUNT) { FloatArray(particlesPerBody) }
    private val shapeV = Array(PieceShapes.COUNT) { FloatArray(particlesPerBody) }

    /**
     * True-outer-silhouette corner flag per particle (§16), 1 at a convex corner
     * of the whole piece, 0 everywhere else including internal cell corners.
     *
     * A lattice-corner particle is a silhouette corner only when its cell has no
     * neighbouring cell in EITHER of the two directions that meet at that corner
     * — a bottom-left corner needs no cell to the left and none below. That keeps
     * an L sharp at its inner elbow (which has a neighbour on one side) and
     * rounds only the real outline. Vertex-interpolated on `:app` exactly like
     * [shapeEdge], so the 0/1 here ramps to a soft curve with no new geometry.
     */
    private val shapeCorner = Array(PieceShapes.COUNT) { FloatArray(particlesPerBody) }

    /**
     * Per-archetype grain-frequency compensation (§15, D10), consumed by `:app`.
     *
     * Body-wide UV means a piece's footprint sets its grain frequency: without
     * compensation a four-cell-long I piece would carry coarser grain than a
     * 2x2 O. This is the factor that cancels that — the footprint's longer side
     * measured in cells (`maxSpan / (lattice - 1)`) — so folding it into the
     * shader's per-archetype `uGrainScale` restores the SAME per-cell grain
     * frequency the single-cell pieces had, now continuous across the whole
     * piece. `:app` multiplies its palette grain scale by this; the palette's
     * own per-archetype variation (the identity cue) survives untouched.
     */
    val grainCompensation = FloatArray(PieceShapes.COUNT)

    /** Seam distance-constraint index pairs (body-local), padded inert to [MAX_SEAMS]. */
    private val shapeSeamDistA = Array(PieceShapes.COUNT) { IntArray(MAX_SEAMS * seamDistance) }
    private val shapeSeamDistB = Array(PieceShapes.COUNT) { IntArray(MAX_SEAMS * seamDistance) }

    /** Seam area-constraint index triples (body-local), padded inert to [MAX_SEAMS]. */
    private val shapeSeamAreaA = Array(PieceShapes.COUNT) { IntArray(MAX_SEAMS * seamArea) }
    private val shapeSeamAreaB = Array(PieceShapes.COUNT) { IntArray(MAX_SEAMS * seamArea) }
    private val shapeSeamAreaC = Array(PieceShapes.COUNT) { IntArray(MAX_SEAMS * seamArea) }

    /** Junction RENDER-triangle index triples (body-local), padded inert to [MAX_JUNCTIONS]. */
    private val shapeJunctionTriA = Array(PieceShapes.COUNT) { IntArray(MAX_JUNCTIONS * junctionTriangles) }
    private val shapeJunctionTriB = Array(PieceShapes.COUNT) { IntArray(MAX_JUNCTIONS * junctionTriangles) }
    private val shapeJunctionTriC = Array(PieceShapes.COUNT) { IntArray(MAX_JUNCTIONS * junctionTriangles) }

    /** Per-shape bounding half-extents, for the placement bbox reject. */
    private val shapeHalfW = FloatArray(PieceShapes.COUNT)
    private val shapeHalfH = FloatArray(PieceShapes.COUNT)

    /**
     * Largest half-height of any shape. `Simulation` spawns every piece with its
     * centre this far below the well top plus a radius, so even the tallest
     * shape starts wholly inside.
     */
    var pieceMaxHalfHeight: Float = 0f
        private set

    /** Largest half-width of any shape (the I piece). For safe scene packing. */
    var pieceMaxHalfWidth: Float = 0f
        private set

    /**
     * Half the side of the smallest square box that contains any shape in any
     * orientation — `max` of the two half-extents. Two pieces placed this far
     * apart, centre to centre, cannot overlap whatever their shapes, so scene
     * builders pack against it (a rotation never exceeds it either).
     */
    val pieceMaxHalfExtent: Float get() = max(pieceMaxHalfWidth, pieceMaxHalfHeight)

    init { buildShapes() }

    private fun bodyLocal(cell: Int, row: Int, col: Int): Int =
        cell * particlesPerCell + row * lattice + col

    private fun buildShapes() {
        for (arch in 0 until PieceShapes.COUNT) {
            var minCol = Int.MAX_VALUE
            var maxCol = Int.MIN_VALUE
            var minRow = Int.MAX_VALUE
            var maxRow = Int.MIN_VALUE
            for (cell in 0 until PieceShapes.CELLS) {
                val cx = PieceShapes.cellX(arch, cell)
                val cy = PieceShapes.cellY(arch, cell)
                if (cx * lattice < minCol) minCol = cx * lattice
                if (cx * lattice + lattice - 1 > maxCol) maxCol = cx * lattice + lattice - 1
                if (cy * lattice < minRow) minRow = cy * lattice
                if (cy * lattice + lattice - 1 > maxRow) maxRow = cy * lattice + lattice - 1
            }
            val centreCol = 0.5f * (minCol + maxCol)
            val centreRow = 0.5f * (minRow + maxRow)

            // Body-wide UV divides both axes by the longer footprint span so the
            // coordinate is isotropic and reaches 1 on the longer side (§15). The
            // grain compensation is that span measured in cells, which cancels
            // the footprint's effect on grain frequency (§15). Every shape is
            // wider than one particle, so maxSpan is never zero.
            val maxSpan = max(maxCol - minCol, maxRow - minRow).toFloat()
            grainCompensation[arch] = maxSpan / (lattice - 1).toFloat()

            for (cell in 0 until PieceShapes.CELLS) {
                val cx = PieceShapes.cellX(arch, cell)
                val cy = PieceShapes.cellY(arch, cell)
                val hasLeft = PieceShapes.neighbour(arch, cell, -1, 0) >= 0
                val hasRight = PieceShapes.neighbour(arch, cell, 1, 0) >= 0
                val hasDown = PieceShapes.neighbour(arch, cell, 0, -1) >= 0
                val hasUp = PieceShapes.neighbour(arch, cell, 0, 1) >= 0
                for (row in 0 until lattice) {
                    for (col in 0 until lattice) {
                        val local = bodyLocal(cell, row, col)
                        val gcol = cx * lattice + col
                        val grow = cy * lattice + row
                        val x = (gcol - centreCol) * spacing
                        val y = (grow - centreRow) * spacing
                        shapeLocalX[arch][local] = x
                        shapeLocalY[arch][local] = y
                        shapeU[arch][local] = (gcol - minCol) / maxSpan
                        shapeV[arch][local] = (grow - minRow) / maxSpan
                        // A particle is free surface only on a cell edge with no
                        // neighbouring cell — seam-facing edges read as interior
                        // so they carry no rim light (ADR 0015, B2 rendering).
                        val freeLeft = col == 0 && !hasLeft
                        val freeRight = col == lattice - 1 && !hasRight
                        val freeDown = row == 0 && !hasDown
                        val freeUp = row == lattice - 1 && !hasUp
                        shapeEdge[arch][local] = if (freeLeft || freeRight || freeDown || freeUp) 1f else 0f
                        // Per-direction detail behind shapeEdge, for the app's
                        // silhouette extrusion (see shapeFreeEdges). LEFT/RIGHT
                        // and DOWN/UP are mutually exclusive per particle, so this
                        // OR never double-counts an axis.
                        var mask = 0
                        if (freeLeft) mask = mask or FREE_LEFT
                        if (freeRight) mask = mask or FREE_RIGHT
                        if (freeDown) mask = mask or FREE_DOWN
                        if (freeUp) mask = mask or FREE_UP
                        shapeFreeEdges[arch][local] = mask
                        // A convex outer corner: a lattice corner particle whose
                        // cell has a free surface on BOTH the meeting sides. An
                        // internal cell corner has a neighbour on at least one
                        // side, so it is 0 — the elbow of an L stays sharp (§16).
                        val cornerH = freeLeft || freeRight
                        val cornerV = freeDown || freeUp
                        shapeCorner[arch][local] = if (cornerH && cornerV) 1f else 0f
                        val ax = if (x < 0) -x else x
                        val ay = if (y < 0) -y else y
                        if (ax > shapeHalfW[arch]) shapeHalfW[arch] = ax
                        if (ay > shapeHalfH[arch]) shapeHalfH[arch] = ay
                    }
                }
            }
            if (shapeHalfH[arch] > pieceMaxHalfHeight) pieceMaxHalfHeight = shapeHalfH[arch]
            if (shapeHalfW[arch] > pieceMaxHalfWidth) pieceMaxHalfWidth = shapeHalfW[arch]
            buildSeams(arch)
            buildJunctions(arch)
        }
    }

    /**
     * Bridges every pair of adjacent cells in shape [arch] with structural,
     * shear and area constraints across the 2r seam, then pads the unused seam
     * slots inert (self-referential, skipped by the solver's EPS guards) so the
     * constraint stride is the same for all seven shapes (ADR 0015).
     */
    private fun buildSeams(arch: Int) {
        var d = 0
        var a = 0
        val L = lattice
        for (cell in 0 until PieceShapes.CELLS) {
            val right = PieceShapes.neighbour(arch, cell, 1, 0)
            if (right >= 0) {
                for (r in 0 until L) {
                    shapeSeamDistA[arch][d] = bodyLocal(cell, r, L - 1)
                    shapeSeamDistB[arch][d] = bodyLocal(right, r, 0); d++
                }
                for (r in 0 until L - 1) {
                    shapeSeamDistA[arch][d] = bodyLocal(cell, r, L - 1)
                    shapeSeamDistB[arch][d] = bodyLocal(right, r + 1, 0); d++
                    shapeSeamDistA[arch][d] = bodyLocal(cell, r + 1, L - 1)
                    shapeSeamDistB[arch][d] = bodyLocal(right, r, 0); d++
                    // Two CCW triangles of the seam cell, matching addArea's sign.
                    shapeSeamAreaA[arch][a] = bodyLocal(cell, r, L - 1)
                    shapeSeamAreaB[arch][a] = bodyLocal(right, r, 0)
                    shapeSeamAreaC[arch][a] = bodyLocal(right, r + 1, 0); a++
                    shapeSeamAreaA[arch][a] = bodyLocal(cell, r, L - 1)
                    shapeSeamAreaB[arch][a] = bodyLocal(right, r + 1, 0)
                    shapeSeamAreaC[arch][a] = bodyLocal(cell, r + 1, L - 1); a++
                }
            }
            val up = PieceShapes.neighbour(arch, cell, 0, 1)
            if (up >= 0) {
                for (c in 0 until L) {
                    shapeSeamDistA[arch][d] = bodyLocal(cell, L - 1, c)
                    shapeSeamDistB[arch][d] = bodyLocal(up, 0, c); d++
                }
                for (c in 0 until L - 1) {
                    shapeSeamDistA[arch][d] = bodyLocal(cell, L - 1, c)
                    shapeSeamDistB[arch][d] = bodyLocal(up, 0, c + 1); d++
                    shapeSeamDistA[arch][d] = bodyLocal(cell, L - 1, c + 1)
                    shapeSeamDistB[arch][d] = bodyLocal(up, 0, c); d++
                    shapeSeamAreaA[arch][a] = bodyLocal(cell, L - 1, c)
                    shapeSeamAreaB[arch][a] = bodyLocal(cell, L - 1, c + 1)
                    shapeSeamAreaC[arch][a] = bodyLocal(up, 0, c + 1); a++
                    shapeSeamAreaA[arch][a] = bodyLocal(cell, L - 1, c)
                    shapeSeamAreaB[arch][a] = bodyLocal(up, 0, c + 1)
                    shapeSeamAreaC[arch][a] = bodyLocal(up, 0, c); a++
                }
            }
        }
        // Inert padding: a==b (distance) and a==b==c (area) are zero-length /
        // zero-area, so the solver's `d < EPS` / `wsum < EPS` guards skip them.
        while (d < shapeSeamDistA[arch].size) {
            shapeSeamDistA[arch][d] = 0; shapeSeamDistB[arch][d] = 0; d++
        }
        while (a < shapeSeamAreaA[arch].size) {
            shapeSeamAreaA[arch][a] = 0; shapeSeamAreaB[arch][a] = 0; shapeSeamAreaC[arch][a] = 0; a++
        }
    }

    /**
     * Closes the four-cell junction of shape [arch] — the 2r x 2r square at the
     * point where four cells meet (only the O has one, ADR 0018). A junction is
     * detected at a cell that has a right, an up AND an up-right neighbour: those
     * four cells form a 2x2 block, and the square is bounded by their four inner
     * corners. The square's four edges are already welded by the four seams; this
     * only triangulates the open middle for the RENDER mesh (no solver constraint,
     * see the [junctionTriangles] note). Unused slots are padded inert like
     * [buildSeams], and the padding is filtered out of [bodyTriangleIndices].
     */
    private fun buildJunctions(arch: Int) {
        var a = 0
        val L = lattice
        for (cell in 0 until PieceShapes.CELLS) {
            val right = PieceShapes.neighbour(arch, cell, 1, 0)
            val up = PieceShapes.neighbour(arch, cell, 0, 1)
            val upRight = PieceShapes.neighbour(arch, cell, 1, 1)
            if (right < 0 || up < 0 || upRight < 0) continue

            // The square's four inner corners, in the same grid orientation as a
            // lattice cell: p00 bottom-left, p10 bottom-right, p01 top-left, p11
            // top-right — so the winding below matches every other render triangle.
            val p00 = bodyLocal(cell, L - 1, L - 1)   // this cell, top-right
            val p10 = bodyLocal(right, L - 1, 0)      // right cell, top-left
            val p01 = bodyLocal(up, 0, L - 1)         // up cell, bottom-right
            val p11 = bodyLocal(upRight, 0, 0)        // up-right cell, bottom-left

            // Two CCW triangles, split on the p00-p11 diagonal like a cell.
            shapeJunctionTriA[arch][a] = p00; shapeJunctionTriB[arch][a] = p10
            shapeJunctionTriC[arch][a] = p11; a++
            shapeJunctionTriA[arch][a] = p00; shapeJunctionTriB[arch][a] = p11
            shapeJunctionTriC[arch][a] = p01; a++
        }
        // Inert padding: a == b == c, dropped by the realTriples filter.
        while (a < shapeJunctionTriA[arch].size) {
            shapeJunctionTriA[arch][a] = 0; shapeJunctionTriB[arch][a] = 0
            shapeJunctionTriC[arch][a] = 0; a++
        }
    }

    // --- particle state -----------------------------------------------------

    val posX = FloatArray(particleCapacity)
    val posY = FloatArray(particleCapacity)

    /** Position at the start of the current tick, for render interpolation. */
    val framePrevX = FloatArray(particleCapacity)
    val framePrevY = FloatArray(particleCapacity)

    /** Position at the start of the current substep, for velocity derivation. */
    val substepPrevX = FloatArray(particleCapacity)
    val substepPrevY = FloatArray(particleCapacity)

    val velX = FloatArray(particleCapacity)
    val velY = FloatArray(particleCapacity)

    val mass = FloatArray(particleCapacity)
    val invMass = FloatArray(particleCapacity)

    /**
     * Per-particle gravity multiplier, 1 normally and 0 while a piece is being
     * positioned (ADR 0016). Suppressing gravity this way keeps the piece a
     * normal *dynamic* soft body — it still deforms and its drag still resolves
     * per substep — rather than pinning it rigid (`invMass = 0`), which would
     * turn a piece slid into the stack into an immovable shover. The solver
     * multiplies rather than branches, so a positioned piece costs no extra
     * control flow in the integrate loop.
     */
    val gravityScale = FloatArray(particleCapacity) { 1f }

    val particleBody = IntArray(particleCapacity)
    val particleU = FloatArray(particleCapacity)
    val particleV = FloatArray(particleCapacity)
    val particleEdge = FloatArray(particleCapacity)

    /**
     * Per-direction free-surface mask (§17, D11), set once at spawn from
     * [shapeFreeEdges] and never touched per frame. Drives `:app`'s silhouette
     * extrusion so only true-outline sides are pushed out; see [shapeFreeEdges].
     */
    val particleFreeEdges = IntArray(particleCapacity)

    /**
     * True-outer-silhouette corner flag (§16), 1 at a convex corner of the whole
     * piece and 0 elsewhere. Static per particle, set once at spawn from
     * [shapeCorner]; never touched per frame. Drives `:app`'s corner rounding.
     */
    val particleCorner = FloatArray(particleCapacity)
    val particleCompression = FloatArray(particleCapacity) { 1f }
    val particleContact = FloatArray(particleCapacity)

    /** Per-particle accumulators for [particleCompression], refilled each tick. */
    val compressionCurrent = FloatArray(particleCapacity)
    val compressionRest = FloatArray(particleCapacity)

    /** Contact bookkeeping for impact detection, refilled each tick. */
    val inContactThisTick = BooleanArray(particleCapacity)
    val inContactLastTick = BooleanArray(particleCapacity)

    /** Peak approach speed seen by a particle this tick, for impact strength. */
    val impactSpeed = FloatArray(particleCapacity)

    var particleCount: Int = 0
        private set

    // --- constraints --------------------------------------------------------

    val dcA = IntArray(maxBodies * distancePerBody)
    val dcB = IntArray(maxBodies * distancePerBody)
    val dcRest = FloatArray(maxBodies * distancePerBody)
    val dcLambda = FloatArray(maxBodies * distancePerBody)
    var distanceCount: Int = 0
        private set

    val acA = IntArray(maxBodies * areaPerBody)
    val acB = IntArray(maxBodies * areaPerBody)
    val acC = IntArray(maxBodies * areaPerBody)
    val acRest = FloatArray(maxBodies * areaPerBody)
    val acLambda = FloatArray(maxBodies * areaPerBody)
    var areaCount: Int = 0
        private set

    // --- bodies -------------------------------------------------------------

    val bodyArchetype = IntArray(maxBodies)
    var bodyCount: Int = 0
        private set

    /**
     * Body-local triangle indices, constant for the whole run (ADR 0007). Two
     * triangles per lattice cell, wound counter-clockwise so signed areas are
     * positive and the area constraint does not fight its own rest value.
     */
    val triangleIndices: IntArray = buildTriangleIndices()

    private fun buildTriangleIndices(): IntArray {
        val cells = (lattice - 1) * (lattice - 1)
        val out = IntArray(cells * 6)
        var n = 0
        for (row in 0 until lattice - 1) {
            for (col in 0 until lattice - 1) {
                val p00 = row * lattice + col
                val p10 = row * lattice + col + 1
                val p01 = (row + 1) * lattice + col
                val p11 = (row + 1) * lattice + col + 1
                out[n++] = p00; out[n++] = p10; out[n++] = p11
                out[n++] = p00; out[n++] = p11; out[n++] = p01
            }
        }
        return out
    }

    /**
     * Per-archetype full-footprint render topology (§17, D11 / ADR 0018): the
     * body-local triangle indices for a WHOLE tetromino of each shape — the four
     * cells' own triangles PLUS the seam-bridge triangles that weld them into one
     * continuous mesh, so an internal seam is an interior mesh line rather than
     * the boundary between two abutting cell meshes. Built once at construction,
     * constant for the run.
     *
     * This is the render form of the solver's area constraints: interior cells
     * use the same `p00,p10,p11 / p00,p11,p01` split as [triangleIndices] (and as
     * the cell area constraints), and the seam bridges are exactly the real seam
     * area triples in [shapeSeamAreaA]/[shapeSeamAreaB]/[shapeSeamAreaC] (the
     * inert padding of ADR 0015 dropped). So every render triangle across the
     * whole piece — seams included — is one area constraint, which is what makes
     * both the body-wide UV (§15) and [particleCompression] continuous across a
     * seam instead of stepping by one grid unit at it (the "+" of handoff 0038).
     *
     * Values are in `[0, particlesPerBody)`; `:app` draws body `b` at vertex
     * offset `b * particlesPerBody`. Length varies by shape: an O bridges four
     * seams, the other six three, so this is a jagged array, not a fixed stride.
     */
    val bodyTriangleIndices: Array<IntArray> =
        Array(PieceShapes.COUNT) { buildBodyTriangleIndices(it) }

    private fun buildBodyTriangleIndices(arch: Int): IntArray {
        val trianglesPerCell = 2 * (lattice - 1) * (lattice - 1)

        // Bridge and junction triangles are the real (non-inert) area triples.
        // The inert padding is a == b == c (a degenerate point), so a triple with
        // any two indices differing is real — an exact filter.
        val bridgeTriangles = realTriples(shapeSeamAreaA[arch], shapeSeamAreaB[arch], shapeSeamAreaC[arch]) +
            realTriples(shapeJunctionTriA[arch], shapeJunctionTriB[arch], shapeJunctionTriC[arch])

        val out = IntArray((cellsPerBody * trianglesPerCell + bridgeTriangles) * 3)
        var n = 0
        // Interior: each cell's own lattice, offset into the body's particle
        // block. Identical winding to triangleIndices, so a cell renders exactly
        // as it does today.
        for (cell in 0 until cellsPerBody) {
            val cellBase = cell * particlesPerCell
            for (row in 0 until lattice - 1) {
                for (col in 0 until lattice - 1) {
                    val p00 = cellBase + row * lattice + col
                    val p10 = p00 + 1
                    val p01 = p00 + lattice
                    val p11 = p01 + 1
                    out[n++] = p00; out[n++] = p10; out[n++] = p11
                    out[n++] = p00; out[n++] = p11; out[n++] = p01
                }
            }
        }
        // Seam bridges (the solver's seam area triples, so the mesh matches the
        // constraints) then the O centre-junction fill (render-only, no constraint
        // — see junctionTriangles). Both already body-local and wound CCW.
        n = appendRealTriples(out, n, shapeSeamAreaA[arch], shapeSeamAreaB[arch], shapeSeamAreaC[arch])
        appendRealTriples(out, n, shapeJunctionTriA[arch], shapeJunctionTriB[arch], shapeJunctionTriC[arch])
        return out
    }

    /** Count of non-inert triangles in a parallel A/B/C area-triple store. */
    private fun realTriples(a: IntArray, b: IntArray, c: IntArray): Int {
        var count = 0
        for (k in a.indices) if (a[k] != b[k] || b[k] != c[k]) count++
        return count
    }

    /** Appends every non-inert triple of an area store to [out], returning the new cursor. */
    private fun appendRealTriples(out: IntArray, start: Int, a: IntArray, b: IntArray, c: IntArray): Int {
        var n = start
        for (k in a.indices) {
            if (a[k] == b[k] && b[k] == c[k]) continue
            out[n++] = a[k]; out[n++] = b[k]; out[n++] = c[k]
        }
        return n
    }

    // --- construction -------------------------------------------------------

    /**
     * Places a tetromino of shape [archetype] with its bounding-box centre on
     * ([centerX], [centerY]) and returns its index (ADR 0015). Four cells, laid
     * out from the precomputed shape offsets and welded by seam constraints.
     *
     * **Placement is validated, not trusted.** The spike
     * (`/work/spike/solver-budget/README.md`) records that seeding bodies less
     * than a particle diameter apart let the contact solver convert the
     * overlap into launch energy, which read as a solver problem for a while
     * before it was found to be a setup problem — and that clamping an
     * overflowing body back inside the well reproduced it a second time. So
     * this throws rather than silently seeding a scene whose physics cannot be
     * trusted.
     *
     * @throws IllegalStateException if capacity is exhausted, or the piece
     *   would start outside the well or overlapping existing material.
     */
    fun addBody(archetype: Int, centerX: Float, centerY: Float): Int {
        check(bodyCount < maxBodies) {
            "soft-body capacity exhausted: $maxBodies bodies for a " +
                "${config.wellWidth}x${config.wellHeight} well at lattice $lattice"
        }
        check(fitsInWell(archetype, centerX, centerY)) {
            "piece $archetype at ($centerX, $centerY) does not fit inside the well " +
                "x=[$wellMinX, $wellMaxX] y>=$wellFloorY"
        }
        check(!overlapsExistingMaterial(archetype, centerX, centerY)) {
            "piece $archetype at ($centerX, $centerY) would be seeded overlapping existing " +
                "material; the contact solver would convert the overlap into launch energy " +
                "(see spike README bug 1). Callers that can legitimately be blocked " +
                "must ask canPlace() first rather than catching this"
        }

        val body = bodyCount++
        bodyArchetype[body] = archetype
        val base = body * particlesPerBody
        val particleMass = config.initialPieceMass
        val inv = 1f / particleMass
        val lx = shapeLocalX[archetype]
        val ly = shapeLocalY[archetype]
        val edge = shapeEdge[archetype]
        val freeEdges = shapeFreeEdges[archetype]
        val u = shapeU[archetype]
        val v = shapeV[archetype]
        val corner = shapeCorner[archetype]

        for (cell in 0 until cellsPerBody) {
            for (row in 0 until lattice) {
                for (col in 0 until lattice) {
                    val local = cell * particlesPerCell + row * lattice + col
                    val i = base + local
                    posX[i] = centerX + lx[local]
                    posY[i] = centerY + ly[local]
                    framePrevX[i] = posX[i]
                    framePrevY[i] = posY[i]
                    substepPrevX[i] = posX[i]
                    substepPrevY[i] = posY[i]
                    velX[i] = 0f
                    velY[i] = 0f
                    mass[i] = particleMass
                    invMass[i] = inv
                    particleBody[i] = body
                    // Body-wide, aspect-preserving surface UV (§15): continuous
                    // across cells so the piece reads as one shape, not four.
                    particleU[i] = u[local]
                    particleV[i] = v[local]
                    particleEdge[i] = edge[local]
                    particleFreeEdges[i] = freeEdges[local]
                    particleCorner[i] = corner[local]
                    particleCompression[i] = 1f
                    particleContact[i] = 0f
                    gravityScale[i] = 1f
                    inContactThisTick[i] = false
                    inContactLastTick[i] = false
                }
            }
        }
        particleCount += particlesPerBody

        addConstraints(archetype, base)
        return body
    }

    /**
     * Whether a piece of shape [archetype] could be seeded at
     * ([centerX], [centerY]) right now.
     *
     * The spawner needs to *ask* rather than to try and recover, because a
     * blocked spawn is not an error: it is the state ADR 0005 turns into the
     * losing condition. [addBody] still throws on a bad placement — a scene
     * builder that seeds an overlap has a bug — but the game loop has a
     * legitimate reason to find the well full, and it must find out without
     * relying on an exception for control flow.
     */
    fun canPlace(archetype: Int, centerX: Float, centerY: Float): Boolean =
        bodyCount < maxBodies &&
            fitsInWell(archetype, centerX, centerY) &&
            !overlapsExistingMaterial(archetype, centerX, centerY)

    private fun fitsInWell(archetype: Int, centerX: Float, centerY: Float): Boolean {
        val lx = shapeLocalX[archetype]
        val ly = shapeLocalY[archetype]
        for (k in 0 until particlesPerBody) {
            val x = centerX + lx[k]
            val y = centerY + ly[k]
            // Open top: a piece may extend above the well while it spawns.
            if (x - particleRadius < wellMinX ||
                x + particleRadius > wellMaxX ||
                y - particleRadius < wellFloorY
            ) {
                return false
            }
        }
        return true
    }

    /**
     * O(n*m) and deliberately so: this runs on spawn and on scene setup, not
     * on the per-frame path. A per-existing-particle bounding-box reject keeps
     * the inner loop off most of the pile.
     */
    private fun overlapsExistingMaterial(archetype: Int, centerX: Float, centerY: Float): Boolean {
        val lx = shapeLocalX[archetype]
        val ly = shapeLocalY[archetype]
        val minGapSq = (2f * particleRadius) * (2f * particleRadius)
        val reachX = shapeHalfW[archetype] + 2f * particleRadius
        val reachY = shapeHalfH[archetype] + 2f * particleRadius
        for (i in 0 until particleCount) {
            val dx = posX[i] - centerX
            val dy = posY[i] - centerY
            if (dx < -reachX || dx > reachX || dy < -reachY || dy > reachY) continue
            for (k in 0 until particlesPerBody) {
                val ex = posX[i] - (centerX + lx[k])
                val ey = posY[i] - (centerY + ly[k])
                if (ex * ex + ey * ey < minGapSq) return true
            }
        }
        return false
    }

    /**
     * Removes a body and everything belonging to it, by moving the last body
     * into the vacated slot.
     *
     * **Body indices are not stable across this call.** The body that was last
     * takes [body]'s index. Callers holding a body index over a removal — the
     * active piece, above all — must remap it, and the removal list itself
     * must be walked in descending order so that the swap source is never a
     * body still waiting to be removed.
     *
     * Swap-remove rather than compaction because every array here is indexed
     * by a body-major stride: a body owns particles
     * `[b*particlesPerBody, (b+1)*particlesPerBody)` and, because
     * [addConstraints] adds exactly the same number of constraints for every
     * body in body order, it owns constraints `[b*distancePerBody, ...)` and
     * `[b*areaPerBody, ...)` too. Moving one body is therefore three
     * contiguous copies and an index rebase, with nothing to search for.
     *
     * Constraint multipliers are not carried across. They are reset at the top
     * of every substep, so a stale value cannot outlive the copy.
     */
    fun removeBody(body: Int) {
        require(body in 0 until bodyCount) { "no such body: $body of $bodyCount" }

        val last = bodyCount - 1
        if (body != last) {
            val dst = body * particlesPerBody
            val src = last * particlesPerBody
            val n = particlesPerBody

            posX.copyInto(posX, dst, src, src + n)
            posY.copyInto(posY, dst, src, src + n)
            framePrevX.copyInto(framePrevX, dst, src, src + n)
            framePrevY.copyInto(framePrevY, dst, src, src + n)
            substepPrevX.copyInto(substepPrevX, dst, src, src + n)
            substepPrevY.copyInto(substepPrevY, dst, src, src + n)
            velX.copyInto(velX, dst, src, src + n)
            velY.copyInto(velY, dst, src, src + n)
            mass.copyInto(mass, dst, src, src + n)
            invMass.copyInto(invMass, dst, src, src + n)
            gravityScale.copyInto(gravityScale, dst, src, src + n)
            particleU.copyInto(particleU, dst, src, src + n)
            particleV.copyInto(particleV, dst, src, src + n)
            particleEdge.copyInto(particleEdge, dst, src, src + n)
            particleFreeEdges.copyInto(particleFreeEdges, dst, src, src + n)
            particleCorner.copyInto(particleCorner, dst, src, src + n)
            particleCompression.copyInto(particleCompression, dst, src, src + n)
            particleContact.copyInto(particleContact, dst, src, src + n)
            inContactThisTick.copyInto(inContactThisTick, dst, src, src + n)
            inContactLastTick.copyInto(inContactLastTick, dst, src, src + n)
            impactSpeed.copyInto(impactSpeed, dst, src, src + n)
            for (k in 0 until n) particleBody[dst + k] = body

            bodyArchetype[body] = bodyArchetype[last]

            // Constraints reference absolute particle indices, so rebase them
            // by the same distance the particles moved.
            val shift = dst - src
            rebase(dcA, dcB, null, body, last, distancePerBody, shift, dcRest)
            rebase(acA, acB, acC, body, last, areaPerBody, shift, acRest)
        }

        bodyCount--
        particleCount -= particlesPerBody
        distanceCount -= distancePerBody
        areaCount -= areaPerBody
    }

    /**
     * Suppresses or restores gravity for a whole body (ADR 0016) by setting its
     * particles' [gravityScale]. The positioning phase parks a piece weightless
     * this way, then restores its weight on release so it falls under the normal
     * solver.
     *
     * The body stays **dynamic** — it deforms and its drag resolves per substep
     * as always — because only gravity is removed, not its mass. Restoring sets
     * the scale back to exactly 1, so a suppress/restore round trip is
     * bit-identical and leaves determinism intact.
     */
    fun setBodyWeightless(body: Int, weightless: Boolean) {
        require(body in 0 until bodyCount) { "no such body: $body of $bodyCount" }
        val base = body * particlesPerBody
        val scale = if (weightless) 0f else 1f
        for (k in 0 until particlesPerBody) gravityScale[base + k] = scale
    }

    private fun rebase(
        a: IntArray,
        b: IntArray,
        c: IntArray?,
        dstBody: Int,
        srcBody: Int,
        perBody: Int,
        shift: Int,
        rest: FloatArray,
    ) {
        val dst = dstBody * perBody
        val src = srcBody * perBody
        for (k in 0 until perBody) {
            a[dst + k] = a[src + k] + shift
            b[dst + k] = b[src + k] + shift
            if (c != null) c[dst + k] = c[src + k] + shift
            rest[dst + k] = rest[src + k]
        }
    }

    /**
     * All constraints of one piece: the four cells' own lattices, then the seam
     * bridges that weld adjacent cells together (ADR 0015). The order — cells
     * first, seams (padded to [MAX_SEAMS]) last — is identical for every shape,
     * which is what keeps [distancePerBody]/[areaPerBody] a constant stride and
     * lets [removeBody] swap a body with three contiguous copies. (The O's centre
     * junction is render-only and adds no constraint here, see [buildJunctions].)
     */
    private fun addConstraints(archetype: Int, base: Int) {
        for (cell in 0 until cellsPerBody) {
            addCellConstraints(base + cell * particlesPerCell)
        }
        val sda = shapeSeamDistA[archetype]
        val sdb = shapeSeamDistB[archetype]
        for (k in sda.indices) addDistance(base + sda[k], base + sdb[k])
        val saa = shapeSeamAreaA[archetype]
        val sab = shapeSeamAreaB[archetype]
        val sac = shapeSeamAreaC[archetype]
        for (k in saa.indices) addArea(base + saa[k], base + sab[k], base + sac[k])
    }

    private fun addCellConstraints(base: Int) {
        // Structural: horizontal and vertical lattice edges.
        for (row in 0 until lattice) {
            for (col in 0 until lattice - 1) {
                addDistance(base + row * lattice + col, base + row * lattice + col + 1)
            }
        }
        for (row in 0 until lattice - 1) {
            for (col in 0 until lattice) {
                addDistance(base + row * lattice + col, base + (row + 1) * lattice + col)
            }
        }
        // Shear: both diagonals of every cell. Without these the lattice is a
        // hinge mechanism and shears flat under load at no constraint cost.
        for (row in 0 until lattice - 1) {
            for (col in 0 until lattice - 1) {
                addDistance(base + row * lattice + col, base + (row + 1) * lattice + col + 1)
                addDistance(base + row * lattice + col + 1, base + (row + 1) * lattice + col)
            }
        }
        // Area: two per cell, matching the render triangles.
        for (row in 0 until lattice - 1) {
            for (col in 0 until lattice - 1) {
                val p00 = base + row * lattice + col
                val p10 = base + row * lattice + col + 1
                val p01 = base + (row + 1) * lattice + col
                val p11 = base + (row + 1) * lattice + col + 1
                addArea(p00, p10, p11)
                addArea(p00, p11, p01)
            }
        }
    }

    private fun addDistance(a: Int, b: Int) {
        val k = distanceCount++
        dcA[k] = a
        dcB[k] = b
        val dx = posX[a] - posX[b]
        val dy = posY[a] - posY[b]
        dcRest[k] = sqrt(dx * dx + dy * dy)
        dcLambda[k] = 0f
    }

    private fun addArea(a: Int, b: Int, c: Int) {
        val k = areaCount++
        acA[k] = a
        acB[k] = b
        acC[k] = c
        acRest[k] = signedArea(a, b, c)
        acLambda[k] = 0f
    }

    fun signedArea(a: Int, b: Int, c: Int): Float =
        0.5f * ((posX[b] - posX[a]) * (posY[c] - posY[a]) - (posX[c] - posX[a]) * (posY[b] - posY[a]))

    /** Total kinetic energy, `0.5 * sum(m * v^2)`. The stability metric. */
    fun kineticEnergy(): Float {
        var sum = 0f
        for (i in 0 until particleCount) {
            sum += mass[i] * (velX[i] * velX[i] + velY[i] * velY[i])
        }
        return 0.5f * sum
    }

    companion object {
        /**
         * Seam slots reserved per piece. A tetromino's four cells form a tree
         * (three adjacencies) except the O, which has a cycle (four). Reserving
         * the maximum and padding the unused slot inert keeps the constraint
         * stride constant across all seven shapes (ADR 0015).
         */
        const val MAX_SEAMS = 4

        /**
         * Four-cell render junctions reserved per piece. Only a 2x2 block of cells
         * has one, so only the O (ADR 0018); the other six pad it inert. Sizes the
         * per-shape junction render-triangle store, not a constraint stride — the
         * junction fill adds no solver constraint (see [junctionTriangles]).
         */
        const val MAX_JUNCTIONS = 1

        // Per-direction free-surface bits packed into particleFreeEdges (§17,
        // D11 / ADR 0018). A frozen wire format: `:app` mirrors these values from
        // the contract (docs/contracts.md §3) since it cannot see this internal
        // class. LEFT/RIGHT and DOWN/UP are mutually exclusive per particle.
        const val FREE_LEFT = 1
        const val FREE_RIGHT = 2
        const val FREE_DOWN = 4
        const val FREE_UP = 8
    }
}
