package gravitris.app.perf

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale

/**
 * The on-screen frame-time readout.
 *
 * `docs/ux/screens/playing.md` places it bottom-left, `type-debug-mono`, low
 * opacity "so it reads as instrumentation, not part of the game's own visual
 * language". `docs/ux/tokens.md` specifies `type-debug-mono` as 12sp, weight
 * 400, monospace with **tabular figures so the numbers don't jitter in width
 * frame to frame** — which is why this is monospace rather than the body face
 * at a smaller size.
 *
 * It exists from the first build because the client's phone is the only
 * instrument this project has: there is no GPU in the build container and no
 * usable emulator, so every real performance number comes from the client
 * installing the APK, playing, and reading these figures off the screen. It is
 * not a debug extra; it is how the largest open risk gets closed.
 *
 * ## Honest by construction
 *
 * The numbers shown are `cur / p95 / max / jank`, not a single smoothed
 * average. An average hides exactly the failure this product cannot afford — a
 * periodic hitch in a game whose entire proposition is how it feels — so the
 * readout shows the distribution and the worst case alongside the headline.
 * See [FrameStats] for what each figure means and for the important caveat on
 * the cpu/total split.
 */
class FrameTimeReadout(context: Context) {

    val view: TextView = TextView(context).apply {
        setTypeface(Typeface.MONOSPACE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
        // color-text #F2F1EC at the ~55% opacity playing.md recommends, so the
        // readout never competes with gameplay for attention.
        setTextColor(Color.argb(140, 0xF2, 0xF1, 0xEC))
        setLineSpacing(0f, LINE_HEIGHT_MULTIPLIER)
        text = PLACEHOLDER

        // Instrumentation, not a control. Excluded from touch so it cannot
        // swallow a drag — gestures work over the whole canvas, including the
        // corner this sits in.
        isClickable = false
        isFocusable = false

        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
    }

    /**
     * Keep the readout inside the safe area. playing.md: it "stays fixed in
     * its corner regardless of stack height (never obscured by gameplay, never
     * moves to make room)" — but it must also not sit under the gesture bar,
     * which would make it unreadable on exactly the device we need readings
     * from.
     */
    fun applyInsets(leftPx: Int, bottomPx: Int, pxPerDp: Float) {
        val padding = (SPACING_DP * pxPerDp).toInt()
        view.setPadding(leftPx + padding, padding, padding, bottomPx + padding)
    }

    /**
     * Refresh the text. Called on the UI thread at the renderer's publish rate
     * (~4Hz), never per frame — updating a `TextView` at 60Hz would put
     * measurable UI-thread work inside the frame we are trying to measure.
     */
    fun update(snapshot: FrameSnapshot, context: RenderContext) {
        view.text = String.format(
            Locale.US,
            "%5.1fms cur  %5.1fms cpu%n" +
                "%5.1fms p95  %5.1fms max%n" +
                "%5.1f fps   %4d jank/s%n" +
                "%5d tri   %4d bodies%n" +
                "%5.1f KB/frame  %s",
            snapshot.currentMs, snapshot.cpuMs,
            snapshot.p95Ms, snapshot.maxMs,
            snapshot.fps, snapshot.jankPerSecond,
            context.triangles, context.bodies,
            context.dynamicBytesPerFrame / 1024f,
            if (context.hapticsScaled) "haptics:scaled" else "haptics:fixed",
        )
    }

    /**
     * The non-timing figures shown alongside, so the client's numbers can be
     * interpreted rather than merely reported. A frame time means nothing
     * without knowing how much geometry produced it.
     *
     * `haptics:scaled` vs `haptics:fixed` is here for a specific reason: if
     * the client reports that every impact feels the same, we need to know
     * whether that is our energy curve or a device without amplitude control
     * before spending a day retuning the curve.
     */
    class RenderContext {
        var triangles = 0
        var bodies = 0
        var dynamicBytesPerFrame = 0
        var hapticsScaled = false
    }

    private companion object {
        /** `type-debug-mono` — docs/ux/tokens.md. */
        const val TEXT_SIZE_SP = 12f
        const val LINE_HEIGHT_MULTIPLIER = 1.2f

        /** 8dp grid — docs/ux/tokens.md §Spacing. */
        const val SPACING_DP = 8f

        const val PLACEHOLDER = "measuring…"
    }
}
