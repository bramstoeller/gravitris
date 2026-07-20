package gravitris.app.gl

import gravitris.game.SimState

/**
 * Builds one frame's interleaved vertex data from a [SimState].
 *
 * Split out of [BodyMesh] purely so it can be tested: everything else in that
 * class touches GL and therefore cannot run in this container, while this is
 * the part where being wrong is both easy and expensive.
 *
 * ## Why this specific function is worth testing on its own
 *
 * The architect's warning for this milestone is that **a wrong interpolation
 * alpha produces judder that looks exactly like a physics bug** — and the
 * solver's stability is now measured in two independent implementations with
 * 2x margin, so a report of "the stack jitters" would send someone into the
 * solver for nothing. The lerp below is where that mistake would live. Getting
 * it backwards (`current -> previous`), or applying alpha to the wrong array
 * pair, still renders, still animates, and still looks like the physics
 * misbehaving.
 */
object VertexFill {

    /**
     * The ADR 0006 render interpolation, fused into the buffer fill.
     *
     * The ADR notes this is where interpolation becomes almost free: the vertex
     * buffer is rebuilt every frame anyway, so it costs one lerp per component
     * rather than a separate pass over the particle arrays.
     *
     * **Compression is deliberately not interpolated.** It is a ratio derived
     * from positions, so lerping it alongside the positions it came from would
     * describe a deformation the geometry on screen does not have. The error is
     * invisible at 60Hz and the honest value is also the cheaper one.
     *
     * @param alpha `accumulator / TICK`, in `[0, 1]`. 0 draws the previous
     *   tick's state, 1 draws the current one.
     * @param out interleaved `[x, y, compression]` per particle. Must hold at
     *   least `state.particleCount * FLOATS_PER_VERTEX` floats.
     * @return the number of floats written.
     */
    fun fill(state: SimState, alpha: Float, out: FloatArray): Int {
        val particles = state.particleCount

        // Loop bounds come from particleCount, never from array.size: the core
        // allocates every array to capacity once, so the tail beyond
        // particleCount holds stale or zeroed particles that would be drawn as
        // a degenerate blob at the origin.
        val currentX = state.positionX
        val currentY = state.positionY
        val previousX = state.prevPositionX
        val previousY = state.prevPositionY
        val compression = state.particleCompression

        var cursor = 0
        for (i in 0 until particles) {
            out[cursor++] = previousX[i] + (currentX[i] - previousX[i]) * alpha
            out[cursor++] = previousY[i] + (currentY[i] - previousY[i]) * alpha
            out[cursor++] = compression[i]
        }
        return cursor
    }
}
