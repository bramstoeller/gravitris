package gravitris.app.input

import gravitris.app.Tunables
import gravitris.game.InputFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the gesture state machine in `docs/ux/gestures.md`.
 *
 * The document's stated purpose is protecting the boundary between a tap and a
 * drag, "with real numbers rather than vibes" — so these tests are mostly
 * about the ambiguous cases either side of that boundary, not the happy path.
 * A tap misread as a micro-drag is the kind of bug a human has to *feel* to
 * notice, which makes it exactly the kind worth pinning down here.
 *
 * Coordinates are in pixels. Density is 1 px/dp throughout so that pixel
 * numbers in the tests read directly as the dp figures in the spec.
 */
class GestureRecognizerTest {

    private val intent = PlayerIntent()
    private val frame = InputFrame()

    /** 1 px/dp, and 1 world unit per dp, so spec numbers pass through intact. */
    private val recognizer = GestureRecognizer(
        GestureConfig(pxPerDp = 1f, worldPerDp = 1f, touchSlopDp = 8f),
        intent,
    )

    private fun drain(): InputFrame {
        intent.drainInto(frame)
        return frame
    }

    private fun ms(value: Long) = value * 1_000_000L

    // --- tap / rotate --------------------------------------------------------

    @Test
    fun `stationary press and release rotates`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerUp(0, 100f, 500f, ms(50))

        assertTrue(drain().rotate, "a stationary tap must rotate")
    }

    @Test
    fun `slow hesitant tap still rotates`() {
        // gestures.md rejects a duration gate outright: "a hesitant,
        // slow-but-still-small press must still register as a rotate, not
        // silently do nothing because it took too long."
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 102f, 501f, ms(400))
        recognizer.onPointerMove(0, 101f, 500f, ms(900))
        recognizer.onPointerUp(0, 101f, 500f, ms(1500))

        assertTrue(drain().rotate, "a 1.5s stationary press is still a tap")
    }

    @Test
    fun `movement just inside slop still rotates`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 107f, 500f, ms(30))
        recognizer.onPointerUp(0, 107f, 500f, ms(60))

        assertTrue(drain().rotate, "7dp is inside the 8dp slop")
    }

    @Test
    fun `movement past slop does not rotate`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 120f, 500f, ms(30))
        recognizer.onPointerUp(0, 120f, 500f, ms(60))

        assertFalse(drain().rotate, "20dp of travel is a drag, not a tap")
    }

    @Test
    fun `a gesture that wanders out and returns does not rotate`() {
        // The classic ambiguous case. Displacement is zero at release, but the
        // finger clearly moved, so this is not a tap.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 140f, 500f, ms(40))
        recognizer.onPointerMove(0, 100f, 500f, ms(80))
        recognizer.onPointerUp(0, 100f, 500f, ms(120))

        assertFalse(drain().rotate, "peak displacement, not final, decides tap")
    }

    @Test
    fun `a second touch within the debounce window is ignored`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerUp(0, 100f, 500f, ms(10))
        assertTrue(drain().rotate)

        // Touch-controller bounce: a second down/up 20ms later.
        recognizer.onPointerDown(0, 100f, 500f, ms(30))
        recognizer.onPointerUp(0, 100f, 500f, ms(35))

        assertFalse(drain().rotate, "bounced tap inside rotateDebounce must not rotate")
    }

    @Test
    fun `a second touch after the debounce window rotates again`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerUp(0, 100f, 500f, ms(10))
        assertTrue(drain().rotate)

        val afterDebounce = ms(10) + Tunables.ROTATE_DEBOUNCE_NANOS + 1
        recognizer.onPointerDown(0, 100f, 500f, afterDebounce)
        recognizer.onPointerUp(0, 100f, 500f, afterDebounce + ms(5))

        assertTrue(drain().rotate, "a deliberate second tap must still rotate")
    }

    // --- drag ----------------------------------------------------------------

    @Test
    fun `drag accumulates delta since the last sample`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 120f, 500f, ms(20)) // crosses slop at 108
        recognizer.onPointerMove(0, 130f, 500f, ms(40))
        recognizer.onPointerMove(0, 145f, 500f, ms(60))

        // (120-108) + (130-120) + (145-130). The first 8dp is the slop and is
        // deliberately not spent — see the note in GestureRecognizer.
        assertEquals(37f, drain().dragX, 0.001f)
    }

    @Test
    fun `crossing slop does not jump the piece by the slop distance`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 109f, 500f, ms(20)) // barely crosses 8dp

        assertEquals(
            1f, drain().dragX, 0.001f,
            "only the 1dp past slop should move the piece, not the whole 9dp",
        )
    }

    @Test
    fun `a fast flick past slop keeps everything beyond the slop`() {
        // A single sample can cross far more than slop. Resetting to that
        // sample's position would swallow the whole movement.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 300f, 500f, ms(16))

        assertEquals(192f, drain().dragX, 0.001f, "200dp of travel minus 8dp of slop")
    }

    @Test
    fun `drag is measured from the last sample not from touch down`() {
        // gestures.md: "the thumb's absolute position on screen never matters
        // — a drag started anywhere behaves the same." Two gestures with the
        // same travel from different origins must produce the same dragX.
        recognizer.onPointerDown(0, 50f, 500f, ms(0))
        recognizer.onPointerMove(0, 70f, 500f, ms(20))
        recognizer.onPointerMove(0, 90f, 500f, ms(40))
        recognizer.onPointerUp(0, 90f, 500f, ms(60))
        val fromLeft = drain().dragX

        val later = ms(60) + Tunables.ROTATE_DEBOUNCE_NANOS + 1
        recognizer.onPointerDown(0, 900f, 500f, later)
        recognizer.onPointerMove(0, 920f, 500f, later + ms(20))
        recognizer.onPointerMove(0, 940f, 500f, later + ms(40))
        recognizer.onPointerUp(0, 940f, 500f, later + ms(60))
        val fromRight = drain().dragX

        assertEquals(fromLeft, fromRight, 0.001f)
    }

    @Test
    fun `drag converts dp to world units`() {
        val scaled = GestureRecognizer(
            GestureConfig(pxPerDp = 2f, worldPerDp = 0.05f, touchSlopDp = 8f),
            intent,
        )
        scaled.onPointerDown(0, 0f, 500f, ms(0))
        scaled.onPointerMove(0, 40f, 500f, ms(20)) // 20dp — crosses slop
        drain() // discard the slop-crossing sample; this test is about scaling

        // 60px at 2px/dp = 30dp of travel; 30dp x 0.05 world/dp = 1.5 world.
        scaled.onPointerMove(0, 100f, 500f, ms(40))

        assertEquals(1.5f, drain().dragX, 0.001f)
    }

    @Test
    fun `vertical movement alone does not drag`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 100f, 540f, ms(200)) // slow, straight down
        recognizer.onPointerMove(0, 100f, 580f, ms(400))

        val result = drain()
        assertEquals(0f, result.dragX, 0.001f, "no horizontal travel, no drag")
        assertFalse(result.hardDrop, "too slow to be a hard drop")
    }

    // --- hard drop -----------------------------------------------------------

    @Test
    fun `fast downward flick hard drops`() {
        recognizer.onPointerDown(0, 100f, 100f, ms(0))
        // 1500dp/s straight down: 30dp per 20ms sample.
        recognizer.onPointerMove(0, 100f, 130f, ms(20))
        recognizer.onPointerMove(0, 100f, 160f, ms(40))

        val result = drain()
        assertTrue(result.hardDrop, "1500dp/s within the cone must hard drop")
        assertTrue(result.hardDropVelocity > 0f, "velocity must be carried through")
    }

    @Test
    fun `hard drop fires before release`() {
        recognizer.onPointerDown(0, 100f, 100f, ms(0))
        recognizer.onPointerMove(0, 100f, 130f, ms(20))
        recognizer.onPointerMove(0, 100f, 160f, ms(40))

        // Drained without ever sending an UP — gestures.md: "Don't wait for
        // release; this is the one gesture that should feel instant."
        assertTrue(drain().hardDrop)
    }

    @Test
    fun `slow downward drag does not hard drop`() {
        recognizer.onPointerDown(0, 100f, 100f, ms(0))
        // 30dp per 200ms = 150dp/s, far below the 1000dp/s threshold.
        recognizer.onPointerMove(0, 100f, 130f, ms(200))
        recognizer.onPointerMove(0, 100f, 160f, ms(400))

        assertFalse(drain().hardDrop, "150dp/s is nowhere near the threshold")
    }

    @Test
    fun `fast diagonal flick outside the cone does not hard drop`() {
        recognizer.onPointerDown(0, 100f, 100f, ms(0))
        // 45 degrees — fast, but well outside the +/-25 degree cone.
        recognizer.onPointerMove(0, 130f, 130f, ms(20))
        recognizer.onPointerMove(0, 160f, 160f, ms(40))

        assertFalse(drain().hardDrop, "45 degrees is outside the cone; this is a drag")
    }

    @Test
    fun `fast flick shorter than the minimum displacement does not hard drop`() {
        recognizer.onPointerDown(0, 100f, 100f, ms(0))
        // 10dp of travel: fast enough, but under hardDropMinDisplacement (16dp).
        // This is the thumb-settling jitter the gate exists to reject.
        recognizer.onPointerMove(0, 100f, 105f, ms(3))
        recognizer.onPointerMove(0, 100f, 110f, ms(6))

        assertFalse(drain().hardDrop, "under 16dp of travel must not fire")
    }

    @Test
    fun `hard drop fires once per gesture`() {
        recognizer.onPointerDown(0, 100f, 100f, ms(0))
        recognizer.onPointerMove(0, 100f, 130f, ms(20))
        recognizer.onPointerMove(0, 100f, 160f, ms(40))
        assertTrue(drain().hardDrop)

        // Continuing the same flick must not queue a second drop.
        recognizer.onPointerMove(0, 100f, 200f, ms(60))
        recognizer.onPointerMove(0, 100f, 240f, ms(80))

        val result = drain()
        assertFalse(result.hardDrop, "one drop per gesture")
        assertEquals(0f, result.dragX, 0.001f, "a spent gesture must not drag either")
    }

    // --- multi-touch ---------------------------------------------------------

    @Test
    fun `a second pointer cannot hijack an in-progress drag`() {
        // gestures.md: a second accidental finger — common one-handed, with
        // the phone resting against a palm — must not be able to hijack or
        // cancel a gesture in progress.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 120f, 500f, ms(20))

        recognizer.onPointerDown(1, 600f, 200f, ms(30))
        recognizer.onPointerMove(1, 700f, 200f, ms(40))
        recognizer.onPointerUp(1, 700f, 200f, ms(50))

        recognizer.onPointerMove(0, 140f, 500f, ms(60))

        // (120-108) + (140-120); the second pointer contributes nothing.
        assertEquals(32f, drain().dragX, 0.001f, "only the first pointer moves the piece")
    }

    @Test
    fun `releasing a second pointer does not end the gesture`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 120f, 500f, ms(20))
        recognizer.onPointerDown(1, 600f, 200f, ms(30))
        recognizer.onPointerUp(1, 600f, 200f, ms(40))

        recognizer.onPointerMove(0, 150f, 500f, ms(50))

        // (120-108) + (150-120).
        assertEquals(42f, drain().dragX, 0.001f)
    }

    // --- cancellation --------------------------------------------------------

    @Test
    fun `a cancelled tap does not rotate`() {
        // A phone call, a system gesture, the window losing focus. An
        // interrupted gesture expressed no intent.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onCancel()
        recognizer.onPointerUp(0, 100f, 500f, ms(20))

        assertFalse(drain().rotate)
    }

    @Test
    fun `movement after a cancel is ignored`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 120f, 500f, ms(20))
        recognizer.onCancel()
        recognizer.onPointerMove(0, 300f, 500f, ms(40))

        // 120-108; the post-cancel move to 300 contributes nothing.
        assertEquals(12f, drain().dragX, 0.001f, "only pre-cancel travel counts")
    }
}
