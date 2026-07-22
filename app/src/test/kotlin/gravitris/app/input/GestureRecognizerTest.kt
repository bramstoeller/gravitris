package gravitris.app.input

import gravitris.app.Tunables
import gravitris.game.InputFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the single-controlled-descent gesture state machine
 * (docs/ux/gestures.md, docs/contracts.md §2, ADR 0017).
 *
 * Under ADR 0017 a pointer-up means exactly one thing (see [GestureRecognizer]):
 * a tap — a gesture that never left the slop — latches **rotate**, and a
 * pointer-up ending a drag commits **nothing** (the steering already happened
 * continuously as `dragX`). There is no release-to-drop and no phase, so unlike
 * ADR 0016's recogniser this one emits a single meaning per gesture.
 *
 * The document's stated purpose is protecting the boundary between a tap and a
 * drag "with real numbers rather than vibes", so the ambiguous cases either
 * side of that boundary carry the most weight: a tap misread as a micro-drag is
 * the kind of bug a human has to *feel* to notice.
 *
 * This is the recogniser's own coverage; a deeper adversarial pass is QA's.
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

    // --- tap: rotate ---------------------------------------------------------

    @Test
    fun `stationary press and release rotates`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerUp(0, 100f, 500f, ms(50))

        val result = drain()
        assertTrue(result.rotate, "a stationary tap rotates the piece")
        assertEquals(0f, result.dragX, 0.001f, "a tap does not steer")
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

    // --- drag: never rotates -------------------------------------------------

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

    // --- a drag release commits nothing (ADR 0017) ---------------------------

    @Test
    fun `a drag release emits nothing`() {
        // ADR 0017's load-bearing assertion: there is no phase where control is
        // taken away. The steering happens continuously while the finger is
        // down; lifting it commits nothing — no release-to-drop, no rotate.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 130f, 500f, ms(20))
        // Drain the steering the move produced, so the next drain sees only
        // whatever the release itself commits.
        val duringDrag = drain()
        assertEquals(22f, duringDrag.dragX, 0.001f, "the drag steered while held (30-8 slop)")

        recognizer.onPointerUp(0, 130f, 500f, ms(40))
        val onRelease = drain()
        assertEquals(0f, onRelease.dragX, 0.001f, "the release adds no steering")
        assertFalse(onRelease.rotate, "the release commits no rotate — there is no release-to-drop")
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

    @Test
    fun `a drag release does not arm the tap debounce`() {
        // The common flow is aim (drag) → release → tap (rotate) the instant
        // the piece needs turning. Only a *tap* arms the debounce, so a rotate
        // immediately after a drag-release must not be swallowed.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 140f, 500f, ms(20)) // a slide, past slop
        recognizer.onPointerUp(0, 140f, 500f, ms(40))   // release → nothing
        drain() // clear the steering the slide produced

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
    fun `vertical movement alone neither steers nor rotates`() {
        // dragX is horizontal only (docs/contracts.md §2): a downward drag does
        // nothing to the fall, and 80dp of travel is not a tap either.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 100f, 540f, ms(200)) // straight down, 40dp
        recognizer.onPointerMove(0, 100f, 580f, ms(400))
        recognizer.onPointerUp(0, 100f, 580f, ms(500))

        val result = drain()
        assertEquals(0f, result.dragX, 0.001f, "no horizontal travel, no drag")
        assertFalse(result.rotate, "80dp of travel is not a tap")
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
    fun `releasing a second pointer does not end the gesture or rotate`() {
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onPointerMove(0, 120f, 500f, ms(20))
        recognizer.onPointerDown(1, 600f, 200f, ms(30))
        recognizer.onPointerUp(1, 600f, 200f, ms(40))

        recognizer.onPointerMove(0, 150f, 500f, ms(50))

        val result = drain()
        // (120-108) + (150-120).
        assertEquals(42f, result.dragX, 0.001f)
        assertFalse(result.rotate, "the ignored second pointer's release is not a tap")
    }

    // --- cancellation --------------------------------------------------------

    @Test
    fun `a cancelled gesture does not rotate`() {
        // A phone call, a system gesture, the window losing focus. An
        // interrupted gesture expressed no intent — and crucially the trailing
        // up that follows a cancel must not be read as a fresh tap.
        recognizer.onPointerDown(0, 100f, 500f, ms(0))
        recognizer.onCancel()
        recognizer.onPointerUp(0, 100f, 500f, ms(20))

        assertFalse(drain().rotate, "a cancel must not turn into a rotate on the trailing up")
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
