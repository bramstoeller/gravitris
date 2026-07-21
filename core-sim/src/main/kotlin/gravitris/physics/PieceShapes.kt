package gravitris.physics

/**
 * The seven tetromino shapes, as cell layouts on an integer grid (ADR 0015).
 *
 * Each shape is four cells; a cell becomes an `L×L` lattice block in
 * [SoftBodyWorld]. The coordinates are `(cx, cy)` with `cx` increasing right and
 * `cy` increasing up — the same axis convention as the solver's world, so a
 * shape here spawns the right way up without a flip.
 *
 * **The order of the four cells is the contract**, not an implementation detail:
 * cell index `c` here owns body-local particle indices `[c·L², (c+1)·L²)` and
 * body-local render cell `c`. Reordering a shape's cells would silently move its
 * particles and its seams, so the layouts are frozen and `PieceShapesTest`
 * pins them.
 *
 * Canonical orientation only. Rotation is a runtime transform of particle
 * positions (ADR 0016), not a second table — there is no per-orientation data
 * here to keep in sync.
 */
internal object PieceShapes {

    /** Distinct shapes. Matches `Simulation.ARCHETYPE_COUNT`. */
    const val COUNT: Int = 7

    /** Cells per piece. A tetromino, by definition. */
    const val CELLS: Int = 4

    /**
     * `[archetype][2·cell + 0/1]` = the cell's `(cx, cy)`. Four cells, so eight
     * ints per shape. Archetype indices are the palette/identity indices `:app`
     * maps hues onto, so their order is also frozen: 0=I 1=O 2=T 3=S 4=Z 5=J 6=L.
     */
    val cells: Array<IntArray> = arrayOf(
        //   c0     c1     c2     c3
        intArrayOf(0, 0, 1, 0, 2, 0, 3, 0), // 0 I — flat bar
        intArrayOf(0, 0, 1, 0, 0, 1, 1, 1), // 1 O — square
        intArrayOf(0, 0, 1, 0, 2, 0, 1, 1), // 2 T — bar + top centre
        intArrayOf(0, 0, 1, 0, 1, 1, 2, 1), // 3 S
        intArrayOf(1, 0, 2, 0, 0, 1, 1, 1), // 4 Z
        intArrayOf(0, 0, 1, 0, 2, 0, 0, 1), // 5 J — bar + top left
        intArrayOf(0, 0, 1, 0, 2, 0, 2, 1), // 6 L — bar + top right
    )

    fun cellX(archetype: Int, cell: Int): Int = cells[archetype][2 * cell]
    fun cellY(archetype: Int, cell: Int): Int = cells[archetype][2 * cell + 1]

    /**
     * The cell index adjacent to [cell] one step in direction ([dx], [dy]),
     * or -1 if this shape has no cell there. Used to decide seams (a neighbour
     * exists) and free edges (none does).
     */
    fun neighbour(archetype: Int, cell: Int, dx: Int, dy: Int): Int {
        val tx = cellX(archetype, cell) + dx
        val ty = cellY(archetype, cell) + dy
        for (other in 0 until CELLS) {
            if (other != cell && cellX(archetype, other) == tx && cellY(archetype, other) == ty) {
                return other
            }
        }
        return -1
    }
}
