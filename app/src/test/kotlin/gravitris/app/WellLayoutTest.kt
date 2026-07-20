package gravitris.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the inset-derived well geometry.
 *
 * ADR 0010 calls this "the item most likely to cause rework if it is not
 * designed in from the first screen", because edge-to-edge is enforced and
 * cannot be opted out of: the surface covers the whole display, the playfield
 * must not, and the difference is device-dependent. These tests pin the
 * distinction so a later change cannot quietly let the well drift under the
 * status bar or the gesture handle.
 */
class WellLayoutTest {

    private val layout = WellLayout()
    private val point = FloatArray(2)

    /** Roughly a Fairphone 6 in portrait: 1080x2400 at 2.75 px/dp, with a
     *  status bar, a gesture handle and a punch-hole cutout at the top. */
    private fun typicalPhone() = layout.update(
        surfaceWidthPx = 1080,
        surfaceHeightPx = 2400,
        insetLeftPx = 0,
        insetTopPx = 130,
        insetRightPx = 0,
        insetBottomPx = 60,
        pxPerDp = 2.75f,
    )

    @Test
    fun `the well bottom left corner sits above the bottom inset`() {
        typicalPhone()
        layout.worldToClip(0f, 0f, point)

        // NDC y of the bottom inset edge: the surface spans -1..1 over 2400px,
        // so 60px up from the bottom is -1 + 2*60/2400.
        val expectedY = -1f + 2f * 60f / 2400f
        assertEquals(-1f, point[0], 1e-5f, "no left inset, so the well starts at the edge")
        assertEquals(expectedY, point[1], 1e-5f, "the well floor must clear the gesture bar")
    }

    @Test
    fun `the well top edge sits below the top inset`() {
        typicalPhone()
        layout.worldToClip(0f, layout.heightWorld, point)

        val expectedY = 1f - 2f * 130f / 2400f
        assertEquals(expectedY, point[1], 1e-4f, "the well must clear the cutout and status bar")
    }

    @Test
    fun `the well spans the full safe width`() {
        typicalPhone()
        layout.worldToClip(layout.widthWorld, 0f, point)
        assertEquals(1f, point[0], 1e-5f)
    }

    @Test
    fun `horizontal insets shrink the well from both sides`() {
        // Landscape with a cutout on the left and a nav bar on the right.
        layout.update(
            surfaceWidthPx = 2400,
            surfaceHeightPx = 1080,
            insetLeftPx = 130,
            insetTopPx = 0,
            insetRightPx = 60,
            insetBottomPx = 0,
            pxPerDp = 2.75f,
        )

        layout.worldToClip(0f, 0f, point)
        assertEquals(-1f + 2f * 130f / 2400f, point[0], 1e-5f)

        layout.worldToClip(layout.widthWorld, 0f, point)
        assertEquals(1f - 2f * 60f / 2400f, point[0], 1e-5f)
    }

    @Test
    fun `world units are square so a squash reads the same on any device`() {
        typicalPhone()

        // One world unit horizontally and one vertically must cover the same
        // number of pixels, or deformation would be anisotropic on screen and
        // the same physics would look different per phone.
        layout.worldToClip(0f, 0f, point)
        val originX = point[0]
        val originY = point[1]
        layout.worldToClip(1f, 1f, point)

        val pxPerUnitX = (point[0] - originX) * 1080f / 2f
        val pxPerUnitY = (originY - point[1]) * 2400f / 2f

        assertEquals(pxPerUnitX, kotlin.math.abs(pxPerUnitY), 0.5f)
    }

    @Test
    fun `well height is clamped on an extremely tall safe area`() {
        layout.update(
            surfaceWidthPx = 400,
            surfaceHeightPx = 4000,
            insetLeftPx = 0, insetTopPx = 0, insetRightPx = 0, insetBottomPx = 0,
            pxPerDp = 2f,
        )
        assertEquals(Tunables.WELL_HEIGHT_MAX_WORLD, layout.heightWorld, 1e-4f)
    }

    @Test
    fun `well height is clamped on a squat safe area`() {
        layout.update(
            surfaceWidthPx = 2000,
            surfaceHeightPx = 1000,
            insetLeftPx = 0, insetTopPx = 0, insetRightPx = 0, insetBottomPx = 0,
            pxPerDp = 2f,
        )
        assertEquals(Tunables.WELL_HEIGHT_MIN_WORLD, layout.heightWorld, 1e-4f)
    }

    @Test
    fun `a degenerate surface does not divide by zero`() {
        // A surface can legitimately be reported as 0x0 mid-rotation. Losing
        // one frame is acceptable; taking the renderer down is not.
        layout.update(0, 0, 0, 0, 0, 0, 0f)
        assertTrue(layout.heightWorld.isFinite())
        assertTrue(layout.worldPerDp.isFinite())

        layout.worldToClip(0f, 0f, point)
        assertTrue(point[0].isFinite() && point[1].isFinite())
    }

    @Test
    fun `insets larger than the surface do not invert the well`() {
        layout.update(
            surfaceWidthPx = 100,
            surfaceHeightPx = 100,
            insetLeftPx = 200, insetTopPx = 200, insetRightPx = 200, insetBottomPx = 200,
            pxPerDp = 2f,
        )
        assertTrue(layout.heightWorld > 0f)
        assertTrue(layout.worldPerDp.isFinite())
    }

    @Test
    fun `world per dp makes a one to one drag literally one to one`() {
        typicalPhone()

        // The safe area is 1080px wide at 2.75px/dp = ~392.7dp, mapped onto 10
        // world units. Dragging the full width of the screen must move the
        // piece the full width of the well.
        val safeWidthDp = 1080f / 2.75f
        assertEquals(layout.widthWorld, safeWidthDp * layout.worldPerDp, 1e-3f)
    }
}
