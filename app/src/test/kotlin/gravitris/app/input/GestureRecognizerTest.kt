package gravitris.app.input

import gravitris.app.Tunables
import gravitris.game.InputFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the slide → release → rotate gesture state machine
 * (docs/ux/gestures.md, control redesign 2026-07-21).
 *
 * The recogniser is phase-agnostic on purpose (see [GestureRecognizer]): every
 * release emits **drop**, and a release that never left the slop *also* emits
 * **rotate**, and it is `:core-sim` that keeps only the one its phase allows.
 * So most of these assert the intent stream this class actually emits — both
 * signals on a tap — not the phase-resolved outcome, which is the core's test.
 *
 * The document's stated purpose is protecting the boundary between a tap and a
 * drag "with real numbers rather than vibes", so the ambiguous cases either
 * side of that boundary carry the most weight: a tap misread as a micro-drag is
 * the kind of bug a human has to *feel* to notice.
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

    // --- tap: rotate AND drop ------------------------------------------------

    @Test
    fun `stationary press and release emits both rotate and drop`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerUp(0, 100f, 500f, ms(50))

        val result = drain()
        // Phase-agnostic: a stationary tap emits rotate (for FALLING) and drop
        // (for POSITIONING). The core keeps exactly one.
        assertTrue(result.rotate, "a stationary tap must offer a rotate")
        assertTrue(result.drop, "every release is also a drop")
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

    // --- drag: drop on release, never rotate ---------------------------------

    @Test
    fun `movement past slop drops but does not rotate`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 120f, 500f, ms(30))
        recognizer.onPointerUp(0, 120f, 500f, ms(60))

        val result = drain()
        assertFalse(result.rotate, "20dp of travel is a drag, not a tap")
        assertTrue(result.drop, "releasing an aimed slide drops the piece")
    }

    @Test
    fun `a gesture that wanders out and returns does not rotate`() {
        // The classic ambiguous case. Displacement is zero at release, but the
        // finger clearly moved, so this is not a tap.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 140f, 500f, ms(40))
        recognizer.onPointerMove(0, 100f, 500f, ms(80))
        recognizer.onPointerUp(0, 100f, 500f, ms(120))

        val result = drain()
        assertFalse(result.rotate, "peak displacement, not final, decides tap")
        assertTrue(result.drop, "it is still a release, so still a drop")
    }

    // --- drop on release -----------------------------------------------------

    @Test
    fun `a drop is only emitted on release, not mid-gesture`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 130f, 500f, ms(20))
        // No up yet: the piece is still being aimed.
        assertFalse(drain().drop, "a held slide has not been released, so no drop")

        recognizer.onPointerUp(0, 130f, 500f, ms(40))
        assertTrue(drain().drop, "the release commits the drop")
    }

    // --- tap debounce --------------------------------------------------------

    @Test
    fun `a second touch within the debounce window is ignored`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerUp(0, 100f, 500f, ms(10))
        assertTrue(drain().rotate)

        // Touch-controller bounce: a second down/up 20ms later.
        recognizer.onPointerDown(0, 100f, 500f, ms(30))
        recognizer.onPointerUp(0, 100f, 500f, ms(35))

        val result = drain()
        assertFalse(result.rotate, "bounced tap inside rotateDebounce must not rotate")
        assertFalse(result.drop, "the whole bounced gesture is dropped, drop included")
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

    @Test
    fun `a drag release does not arm the tap debounce`() {
        // The common flow is aim (drag) → release (drop) → tap (rotate) the
        // instant the piece starts falling. Only a *tap* arms the debounce, so
        // a rotate immediately after a drag-release must not be swallowed.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 140f, 500f, ms(20)) // a slide, past slop
        recognizer.onPointerUp(0, 140f, 500f, ms(40))   // release → drop
        assertTrue(drain().drop)

        // 5ms later — well inside the 60ms window — a decisive tap to rotate.
        recognizer.onPointerDown(0, 140f, 500f, ms(45))
        recognizer.onPointerUp(0, 140f, 500f, ms(50))

        assertTrue(drain().rotate, "a drag-release must not debounce the next tap")
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

        recognizer.onPointerDown(0, 900f, 500f, ms(80))
        recognizer.onPointerMove(0, 920f, 500f, ms(100))
        recognizer.onPointerMove(0, 940f, 500f, ms(120))
        recognizer.onPointerUp(0, 940f, 500f, ms(140))
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
    fun `vertical movement alone does not drag but still drops on release`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 100f, 540f, ms(200)) // straight down, 40dp
        recognizer.onPointerMove(0, 100f, 580f, ms(400))
        recognizer.onPointerUp(0, 100f, 580f, ms(500))

        val result = drain()
        assertEquals(0f, result.dragX, 0.001f, "no horizontal travel, no drag")
        assertFalse(result.rotate, "80dp of travel is not a tap")
        assertTrue(result.drop, "release drops regardless of the path taken to it")
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
    fun `releasing a second pointer does not end the gesture or drop`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 120f, 500f, ms(20))
        recognizer.onPointerDown(1, 600f, 200f, ms(30))
        recognizer.onPointerUp(1, 600f, 200f, ms(40))

        recognizer.onPointerMove(0, 150f, 500f, ms(50))

        val result = drain()
        // (120-108) + (150-120).
        assertEquals(42f, result.dragX, 0.001f)
        assertFalse(result.drop, "the ignored second pointer's release is not a drop")
    }

    // --- cancellation --------------------------------------------------------

    @Test
    fun `a cancelled gesture emits neither rotate nor drop`() {
        // A phone call, a system gesture, the window losing focus. An
        // interrupted gesture expressed no intent — and crucially the trailing
        // up that follows a cancel must not be read as a fresh release-drop.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onCancel()
        recognizer.onPointerUp(0, 100f, 500f, ms(20))

        val result = drain()
        assertFalse(result.rotate)
        assertFalse(result.drop, "a cancel must not turn into a drop on the trailing up")
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
