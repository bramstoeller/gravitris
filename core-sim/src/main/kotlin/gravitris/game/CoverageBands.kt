package gravitris.game

/**
 * Per-band material coverage, via the coarse occupancy bitmap of ADR 0004.
 *
 * The well is cut into [bandCount] horizontal bands. Each band carries a small
 * grid of cells roughly one particle across. Every tick the bitmap is cleared,
 * every particle's disk is stamped into it, and the set cells per band are
 * counted. Band fill is `set / total`.
 *
 * ### Why span and not area
 *
 * The obvious cheaper answer — sum the particle disk areas in each band — was
 * rejected in ADR 0004 and it is worth restating next to the code, because it
 * is the thing someone will "optimise" this into. Summing areas double-counts
 * overlapping particles, so a compressed clump reads as *over*-full; and a
 * ring of particles around a void reads as completely full. Both are exactly
 * backwards for a mechanic about squeezing material into gaps. The bitmap
 * measures **span**, which is what the player sees.
 *
 * ### Stamping the disk, not the centre
 *
 * A particle's *centre* is not where its material ends: the solver treats
 * material as extending [SimConfig.particleRadius] past the centre, and holds
 * two touching bodies' centres exactly one diameter apart. Stamping centres
 * alone would inset every body by a radius on all sides and make 100% fill
 * unreachable — a well packed solid with material would read as roughly 80%,
 * and the ~90% threshold in the brief would never fire. ADR 0004 specifies the
 * disk for this reason; so does the rendering contract, which had the mirror
 * image of this bug.
 *
 * The disk is stamped as its bounding cells rather than by testing each cell
 * centre against the radius. That over-reports slightly and deliberately: it
 * is what ADR 0004 measured the threshold against, and the tuned threshold
 * absorbs the bias. It is also branch-free in the inner loop.
 *
 * ### Cost
 *
 * At the default resolution this is 3 200 cells cleared, ~9 stamps per
 * particle, and 3 200 cells counted, per tick. ADR 0004 measured the algorithm
 * at 0.05% of a 16.67 ms frame. Nothing here allocates.
 */
internal class CoverageBands(
    private val bandCount: Int,
    private val columns: Int,
    private val rowsPerBand: Int,
    private val minX: Float,
    wellWidth: Float,
    private val bottomY: Float,
    private val bandHeight: Float,
) {

    /**
     * Per-band fill as measured this tick, 0..1. **What the clear rule reads.**
     *
     * Deliberately not the published value. See [fill].
     */
    val fillRaw = FloatArray(bandCount)

    /**
     * Per-band fill, damped. **What the renderer reads**, through
     * `SimState.bandFill`.
     *
     * ### Why the eye and the rule read different arrays
     *
     * `docs/ux/band-glow.md` flags this as an open question and it has a real
     * artefact behind it: a band's fill spikes for a few frames during the
     * bounce of a heavy landing and falls back. The glow curve accelerates
     * hard between 70% and 90% — that asymmetry *is* how the rule teaches
     * itself without a tutorial — so an undamped value would flash the well
     * amber on every heavy landing and teach the player something false.
     *
     * Damping it in the renderer instead was rejected, and the Frontend
     * Engineer and I agreed on that explicitly: it would make the *displayed*
     * coverage disagree with the coverage the clear rule actually fires on,
     * which is a worse bug than the flicker.
     *
     * So the damping lives here, once, and the rule keeps reading [fillRaw].
     * The two agree at the only moment that matters: a piece must be still for
     * `lockDebounceTicks` before it locks, which is several times the rise
     * time constant below, so by the time a clear is evaluated this array has
     * converged onto [fillRaw]. The residual disagreement is that the glow
     * lags a clear by a frame or two — unobservable, because the moment a
     * clear fires the ignition flash is driven by `bandClearProgress` instead,
     * not by this.
     */
    val fill = FloatArray(bandCount)

    private val totalRows = bandCount * rowsPerBand
    private val cellWidth = wellWidth / columns
    private val cellHeight = bandHeight / rowsPerBand
    private val cellsPerBand = columns * rowsPerBand

    /**
     * One byte per cell rather than a packed bitset. The bitmap is 3 200 cells
     * — 3 KB — which is noise against the working set, and a packed set would
     * put a shift and a mask in the innermost loop of the stamp to save memory
     * nobody is short of.
     */
    private val cells = BooleanArray(totalRows * columns)

    /**
     * Recomputes [fill] from the current particle positions.
     *
     * Material above the top of the well is ignored rather than clamped into
     * the top band: a piece still falling in from above has not covered
     * anything yet, and counting it would make the top band read as full at
     * the exact moment ADR 0005 wants to ask whether it is.
     */
    fun update(posX: FloatArray, posY: FloatArray, particleCount: Int, radius: Float) {
        cells.fill(false)

        for (i in 0 until particleCount) {
            val loX = ((posX[i] - radius - minX) / cellWidth).toInt().coerceAtLeast(0)
            val hiX = ((posX[i] + radius - minX) / cellWidth).toInt().coerceAtMost(columns - 1)
            if (hiX < loX) continue
            val loY = ((posY[i] - radius - bottomY) / cellHeight).toInt().coerceAtLeast(0)
            val hiY = ((posY[i] + radius - bottomY) / cellHeight).toInt().coerceAtMost(totalRows - 1)
            if (hiY < loY) continue

            for (row in loY..hiY) {
                val base = row * columns
                for (col in loX..hiX) cells[base + col] = true
            }
        }

        val inv = 1f / cellsPerBand
        for (band in 0 until bandCount) {
            var set = 0
            val from = band * rowsPerBand * columns
            for (k in from until from + cellsPerBand) if (cells[k]) set++
            val raw = set * inv
            fillRaw[band] = raw
            val current = fill[band]
            // Asymmetric on purpose. Rising slowly is the whole point — it is
            // what swallows the bounce spike. Falling fast is what makes a
            // clear read as material leaving *now* rather than as a band
            // fading out over a quarter of a second, and a spurious dip costs
            // nothing because the glow curve does nothing below 40% anyway.
            val rate = if (raw > current) RISE_PER_TICK else FALL_PER_TICK
            fill[band] = current + (raw - current) * rate
        }
    }

    /** Drops the damping and snaps [fill] onto [fillRaw]. */
    fun snap() {
        fillRaw.copyInto(fill)
    }

    /** The band a world-space `y` falls in, or -1 if outside the well. */
    fun bandAt(y: Float): Int {
        val band = ((y - bottomY) / bandHeight).toInt()
        return if (band in 0 until bandCount) band else -1
    }

    /** Lower edge of [band] in world space. */
    fun bandBottom(band: Int): Float = bottomY + band * bandHeight

    private companion object {
        /**
         * Time constant ~4 ticks (67 ms). Short enough to converge well inside
         * `lockDebounceTicks`, so the rule and the eye agree when it counts;
         * long enough that a 3-frame bounce spike arrives at barely half
         * height and never crosses the ignition threshold.
         */
        const val RISE_PER_TICK = 0.25f
        const val FALL_PER_TICK = 0.50f
    }
}
