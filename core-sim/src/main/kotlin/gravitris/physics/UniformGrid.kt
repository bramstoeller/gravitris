package gravitris.physics

import kotlin.math.ceil

/**
 * Uniform-grid broadphase built by counting sort (ADR 0003 §1).
 *
 * Cell size is one particle diameter. **Rebuilt once per frame, not once per
 * substep** — particles move a fraction of a cell per substep, so narrowphase
 * can safely run against the frame's grid, and that keeps the rebuild off the
 * substep multiplier.
 *
 * Every array is allocated once, at construction, and refilled in place. The
 * spike's first draft allocated a per-frame `IntArray` here and it showed up
 * immediately in the allocation measurement (spike README, bug 3); an
 * allocation assertion over this path is part of the test suite.
 */
internal class UniformGrid(
    private val cellSize: Float,
    private val originX: Float,
    private val originY: Float,
    width: Float,
    height: Float,
    particleCapacity: Int,
) {
    private val cols: Int = ceil(width / cellSize.toDouble()).toInt().coerceAtLeast(1)
    private val rows: Int = ceil(height / cellSize.toDouble()).toInt().coerceAtLeast(1)
    private val cellCount: Int = cols * rows

    /** `cellStart[c] until cellStart[c + 1]` indexes [entries] for cell `c`. */
    private val cellStart = IntArray(cellCount + 1)
    private val cellCursor = IntArray(cellCount)
    private val entries = IntArray(particleCapacity)
    private val cellOfParticle = IntArray(particleCapacity)

    /**
     * Rebuilds the grid over `[0, count)`.
     *
     * Particles outside the grid bounds are clamped into the edge cells rather
     * than dropped. A particle that has escaped the well must still collide —
     * silently losing it would be an invisible failure — and narrowphase
     * rejects the extra candidates by exact distance anyway.
     */
    fun build(posX: FloatArray, posY: FloatArray, count: Int) {
        cellStart.fill(0)

        for (i in 0 until count) {
            val c = cellIndexOf(posX[i], posY[i])
            cellOfParticle[i] = c
            // Counts are accumulated into cellStart shifted by one, so the
            // prefix sum below turns them into start offsets in place with no
            // second array.
            cellStart[c + 1]++
        }
        for (c in 0 until cellCount) {
            cellStart[c + 1] += cellStart[c]
            cellCursor[c] = cellStart[c]
        }
        for (i in 0 until count) {
            entries[cellCursor[cellOfParticle[i]]++] = i
        }
    }

    private fun cellIndexOf(x: Float, y: Float): Int {
        val cx = ((x - originX) / cellSize).toInt().coerceIn(0, cols - 1)
        val cy = ((y - originY) / cellSize).toInt().coerceIn(0, rows - 1)
        return cy * cols + cx
    }

    val columns: Int get() = cols
    val rowCount: Int get() = rows

    /**
     * The cell particle [i] was bucketed into by the last [build].
     *
     * The narrowphase centres its stencil on this rather than on the
     * particle's current cell. Because the grid is rebuilt once per frame but
     * the narrowphase runs once per substep, a particle's current cell can
     * drift from the one it was sorted into; keying the stencil off the stored
     * cell makes which pairs get visited a function of the frame's bucketing
     * alone, and therefore reproducible (ADR 0006 — contact ordering is part
     * of the determinism contract).
     */
    fun cellOf(i: Int): Int = cellOfParticle[i]

    fun cellIndex(cx: Int, cy: Int): Int = cy * cols + cx

    fun startOf(cell: Int): Int = cellStart[cell]

    fun endOf(cell: Int): Int = cellStart[cell + 1]

    fun entryAt(k: Int): Int = entries[k]
}
