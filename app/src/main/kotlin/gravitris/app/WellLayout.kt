package gravitris.app

/**
 * Maps the well's world coordinate system onto the device's **safe** area.
 *
 * `docs/contracts.md` §5 assigns "well geometry from insets" to the frontend,
 * with `:core-sim` consuming the result via `SimConfig.wellWidth/wellHeight`.
 * ADR 0010 explains why this cannot be a constant: edge-to-edge is enforced
 * and cannot be opted out of at `targetSdk 35+`, so the GL surface fills the
 * display including under the status bar, navigation bar and camera cutout —
 * **but the playfield must not**. The usable area is therefore device-
 * dependent, and ADR 0010 flags this as "the item most likely to cause rework
 * if it is not designed in from the first screen".
 *
 * So: the *surface* is the whole display, and the *well* is a rectangle inside
 * the safe insets. The area between them is background, drawn but never
 * played in.
 *
 * ## Coordinate system
 *
 * World space has its origin at the **bottom-left corner of the well**, x to
 * the right, y upward. That matches the simulation's convention — gravity is
 * negative y in `SimConfig` — so no axis flip is needed anywhere between the
 * solver and the vertex buffer. The flip to Android's downward-y screen space
 * happens exactly once, here, in [worldToClip].
 */
class WellLayout {

    /** Well width in world units. Fixed; the height adapts instead. */
    val widthWorld: Float = Tunables.WELL_WIDTH_WORLD

    /** Well height in world units, derived from the safe area's aspect ratio. */
    var heightWorld: Float = Tunables.WELL_HEIGHT_MIN_WORLD
        private set

    /** How many world units one dp of finger travel is worth. Feeds
     *  `GestureConfig.worldPerDp` so gestures.md's 1:1 mapping is literally 1:1. */
    var worldPerDp: Float = 1f
        private set

    /** Scale and offset taking world space to normalised device coordinates. */
    private var scaleX = 1f
    private var scaleY = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    /**
     * Recompute the layout.
     *
     * @param surfaceWidthPx full GL surface width — the whole display.
     * @param surfaceHeightPx full GL surface height.
     * @param insetLeftPx safe-area insets, already the union of system bars and
     *   any display cutout.
     * @param pxPerDp display density.
     */
    fun update(
        surfaceWidthPx: Int,
        surfaceHeightPx: Int,
        insetLeftPx: Int,
        insetTopPx: Int,
        insetRightPx: Int,
        insetBottomPx: Int,
        pxPerDp: Float,
    ) {
        // Guard against a zero-area or fully-inset surface. A surface can
        // legitimately be reported as 0x0 during rotation, and a divide by
        // zero here would take the whole renderer down on a configuration
        // change rather than skipping one frame.
        val safeWidthPx = (surfaceWidthPx - insetLeftPx - insetRightPx).coerceAtLeast(1)
        val safeHeightPx = (surfaceHeightPx - insetTopPx - insetBottomPx).coerceAtLeast(1)
        val density = if (pxPerDp > 0f) pxPerDp else 1f

        // The well fills the safe area's width and takes its height from the
        // safe area's aspect ratio, so the world stays square-ish per unit —
        // one world unit is the same distance horizontally and vertically.
        // Without this the material would deform anisotropically on screen and
        // a "squash" would read differently depending on the phone.
        heightWorld = (widthWorld * safeHeightPx / safeWidthPx)
            .coerceIn(Tunables.WELL_HEIGHT_MIN_WORLD, Tunables.WELL_HEIGHT_MAX_WORLD)

        val safeWidthDp = safeWidthPx / density
        worldPerDp = widthWorld / safeWidthDp

        // World -> pixels, then pixels -> NDC. Composed into one scale/offset
        // pair so the vertex shader is two multiply-adds rather than a matrix.
        val pxPerWorldX = safeWidthPx.toFloat() / widthWorld
        val pxPerWorldY = safeHeightPx.toFloat() / heightWorld

        val originXPx = insetLeftPx.toFloat()
        // World y is up and the well's origin is its bottom-left, so the world
        // origin sits at the BOTTOM of the safe area in screen terms.
        val originYPx = (surfaceHeightPx - insetBottomPx).toFloat()

        val surfaceWidth = surfaceWidthPx.coerceAtLeast(1).toFloat()
        val surfaceHeight = surfaceHeightPx.coerceAtLeast(1).toFloat()

        scaleX = 2f * pxPerWorldX / surfaceWidth
        offsetX = 2f * originXPx / surfaceWidth - 1f
        // Negative: NDC y is up, screen y is down, and originYPx is measured
        // from the top. This is the single axis flip in the renderer.
        scaleY = 2f * pxPerWorldY / surfaceHeight
        offsetY = 1f - 2f * originYPx / surfaceHeight
    }

    /** Writes `[scaleX, scaleY]` into [out] for the vertex shader uniform. */
    fun clipScale(out: FloatArray) {
        out[0] = scaleX
        out[1] = scaleY
    }

    /** Writes `[offsetX, offsetY]` into [out] for the vertex shader uniform. */
    fun clipOffset(out: FloatArray) {
        out[0] = offsetX
        out[1] = offsetY
    }

    /** Convenience for tests and for the well-frame geometry: world point to
     *  normalised device coordinates. */
    fun worldToClip(xWorld: Float, yWorld: Float, out: FloatArray) {
        out[0] = xWorld * scaleX + offsetX
        out[1] = yWorld * scaleY + offsetY
    }
}
