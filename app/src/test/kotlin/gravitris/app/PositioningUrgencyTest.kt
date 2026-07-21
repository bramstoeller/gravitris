package gravitris.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The positioning countdown's arithmetic, checkable without a GPU — the one
 * part of the urgency cue that is not GL. It guards the two edges that would
 * misdraw the bar: the off-window zero, and the divide-by-zero a momentarily
 * bad window would otherwise be.
 */
class PositioningUrgencyTest {

    @Test
    fun `a full window reads as one`() {
        assertEquals(1f, PositioningUrgency.fraction(50, 50))
    }

    @Test
    fun `half the window remaining reads as one half`() {
        assertEquals(0.5f, PositioningUrgency.fraction(25, 50))
    }

    @Test
    fun `a nearly-spent window reads low`() {
        assertEquals(0.1f, PositioningUrgency.fraction(5, 50), 1e-6f)
    }

    /** The contract makes remaining 0 whenever the piece is not positioning; the
     *  bar must then not draw. */
    @Test
    fun `no ticks remaining reads as zero`() {
        assertEquals(0f, PositioningUrgency.fraction(0, 50))
    }

    @Test
    fun `a negative remaining count is clamped to zero`() {
        assertEquals(0f, PositioningUrgency.fraction(-3, 50))
    }

    /** A dev panel could momentarily drive positioningTicks to 0; the cue treats
     *  that as "no window" rather than dividing by it. */
    @Test
    fun `a non-positive window is treated as no window`() {
        assertEquals(0f, PositioningUrgency.fraction(50, 0))
        assertEquals(0f, PositioningUrgency.fraction(50, -10))
    }

    /** Remaining should never exceed the window, but the fraction is capped at 1
     *  so a stray reading cannot stretch the bar past the well. */
    @Test
    fun `remaining beyond the window is capped at one`() {
        assertEquals(1f, PositioningUrgency.fraction(60, 50))
    }
}
