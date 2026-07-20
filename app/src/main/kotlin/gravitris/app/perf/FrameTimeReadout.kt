package gravitris.app.perf

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import gravitris.app.GameRenderer
import gravitris.app.haptics.ImpactHaptics
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
     * The benchmark block, once run. Held here rather than redrawn from the
     * renderer because the live figures refresh at ~4Hz and would otherwise
     * wipe it a quarter of a second after it appeared — and this is the number
     * the client is most likely to be asked to photograph.
     */
    private var benchmarkBlock: String = ""

    /**
     * Refresh the text. Called on the UI thread at the renderer's publish rate
     * (~4Hz), never per frame — updating a `TextView` at 60Hz would put
     * measurable UI-thread work inside the frame we are trying to measure.
     */
    /** The live figures, kept so the benchmark block can be composed onto them
     *  without re-deriving them or parsing them back out of the TextView. */
    private var liveBlock: String = PLACEHOLDER

    fun update(snapshot: FrameSnapshot, context: RenderContext) {
        // Every figure is an aggregate over the trailing second. Deliberately
        // no instantaneous frame time: the text refreshes at ~4Hz, so an
        // instantaneous reading would be one arbitrarily-chosen frame out of
        // fifteen — noise presented with the authority of a measurement.
        liveBlock = String.format(
            Locale.US,
            "%s%n" +
                "%5.1fms mean %5.1fms p95%n" +
                "%5.1fms cpu  %5.1fms max%n" +
                "%5.1f fps   %5d jank/s%n" +
                "%5d tri   %5d bodies%n" +
                "%5.1f KB/f  %s%n" +
                "%s%n" +
                "  imp:%d puls:%d e:%.2f%n" +
                "  sys vib:%s touch-fb:%s",
            BASELINE_LABEL,
            snapshot.meanMs, snapshot.p95Ms,
            snapshot.meanCpuMs, snapshot.maxMs,
            snapshot.fps, snapshot.jankPerSecond,
            context.triangles, context.bodies,
            context.dynamicBytesPerFrame / 1024f,
            shadeLabel(context.shadeLevel),
            context.hapticsMode.readout,
            context.impactsSeen, context.pulsesRequested, context.lastImpactEnergy,
            settingLabel(context.masterVibrateOn), settingLabel(context.touchFeedbackOn),
        )
        render()
    }

    private fun render() {
        view.text = liveBlock + benchmarkBlock
    }

    /**
     * Show that the benchmark is running.
     *
     * Posted from the UI thread *before* the work is queued onto the GL thread,
     * because the GL thread then blocks for several seconds and the screen
     * stops updating. Without this the client taps a hidden key and the game
     * appears to freeze for no reason.
     */
    fun showBenchmarkRunning() {
        benchmarkBlock = String.format(Locale.US, "%n%n%s%n  measuring, hold still…", BENCH_LABEL)
        render()
    }

    /**
     * Show a completed benchmark.
     *
     * Every figure the derating ratio depends on is printed alongside it, so a
     * photograph of this screen is self-contained: the ratio means nothing
     * without knowing it is solver CPU only, on which workload, over how many
     * frames, and against which host number.
     */
    fun showBenchmark(result: SolverBenchmark.Result) {
        benchmarkBlock = String.format(
            Locale.US,
            "%n%n%s%n" +
                "%5.2fms p50 %5.2fms p95%n" +
                "%5.2fms mean%5.2fms max%n" +
                "%5.2fx host %5.1f%% of frame%n" +
                "%5d part  %5d frames%n" +
                "  host p50 %.3fms (jvm)",
            BENCH_LABEL,
            result.p50Ms, result.p95Ms,
            result.meanMs, result.maxMs,
            result.deratingVsHost, result.budgetFraction * 100f,
            result.particles, result.frames,
            SolverBenchmark.HOST_P50_MS,
        )
        render()
    }

    /**
     * The non-timing figures shown alongside, so the client's numbers can be
     * interpreted rather than merely reported. A frame time means nothing
     * without knowing how much geometry produced it.
     *
     * The haptics line is here for a specific reason: if the client reports
     * that every impact feels the same, we need to know whether that is our
     * energy curve or a device without amplitude control before spending a day
     * retuning the curve.
     *
     * It gets a whole line to itself, and states the *reason* for any fallback,
     * because the two-state version did not survive contact with the client.
     * Milestone 1 came back reading `haptics:fixed` and that single word was
     * compatible with at least four different causes, so it told us only that
     * something was wrong — not what. [ImpactHaptics.Mode] carries the reason
     * and this line prints it verbatim.
     */
    class RenderContext {
        var triangles = 0
        var bodies = 0
        var dynamicBytesPerFrame = 0

        /** Why the haptic channel is doing what it is doing. */
        var hapticsMode: ImpactHaptics.Mode = ImpactHaptics.Mode.PENDING

        /**
         * Impacts received from the core, pulses actually requested from the
         * platform, and the energy of the most recent one.
         *
         * The client felt no vibration at all while the readout said a fixed
         * pulse was running. `imp:0` means nothing is crossing the `:core-sim`
         * contract; `imp:N puls:0` means everything is under the energy floor;
         * `imp:N puls:M` with nothing felt means we asked and the platform did
         * not deliver. Without these three numbers those are one symptom.
         */
        var impactsSeen = 0
        var pulsesRequested = 0
        var lastImpactEnergy = 0f

        /**
         * The user's own vibration settings, because `vibrate()` gives no
         * indication when the platform silently drops a pulse we asked for.
         * `null` when the setting could not be read.
         */
        var masterVibrateOn: Boolean? = null
        var touchFeedbackOn: Boolean? = null

        /**
         * How much of the art direction is running, 0..4. See
         * `GameRenderer.shadeLevel`.
         *
         * Shown because a frame time is meaningless without it — the whole
         * point of the dial is comparing the five readings, and a screenshot
         * that does not say which one it is cannot be used.
         *
         * Every level below the top is **shouted** (`shade:2/4`, upper case,
         * with the maximum alongside), not stated. Milestone 1 is the reason:
         * the compression toggle defaulted to on and only a deliberate key
         * press turned it off, yet both screenshots came back with it off, so
         * the client spent the demo unable to see the deformation the demo
         * existed to show — and the only clue was three lower-case characters
         * in a corner. Showing the denominator means a screenshot of a reduced
         * tier is self-evidently a reduced tier.
         */
        var shadeLevel = 0
    }

    /**
     * `shade:FULL` at the top of the dial, `shade:2/4` anywhere below it.
     *
     * The band-fill source rides along on the same line because the two are
     * read together or not at all: the glow only runs at the top level, and at
     * Stage 3B what it is glowing *at* is a debug sweep rather than real
     * coverage. A screenshot of a glowing stack that does not say `band:DEBUG`
     * would be read as the mechanic working.
     */
    private fun shadeLabel(level: Int): String = when (level) {
        GameRenderer.SHADE_LEVEL_MAX -> "shade:FULL band:DEBUG"
        else -> "shade:$level/${GameRenderer.SHADE_LEVEL_MAX}"
    }

    /** `?` is a genuinely different answer from `on` or `off` and is shown as
     *  such rather than being collapsed into a default. */
    private fun settingLabel(value: Boolean?): String = when (value) {
        true -> "on"
        false -> "OFF"
        null -> "?"
    }

    private companion object {
        /** `type-debug-mono` — docs/ux/tokens.md. */
        const val TEXT_SIZE_SP = 12f
        const val LINE_HEIGHT_MULTIPLIER = 1.2f

        /** 8dp grid — docs/ux/tokens.md §Spacing. */
        const val SPACING_DP = 8f

        /**
         * States what these numbers are before anyone reads them.
         *
         * "stage1" is no longer accurate — the real solver is now underneath
         * these figures, so they are a Milestone 1 measurement rather than a
         * shell-only one — but **"not a verdict" is still exactly right and
         * must stay**. The fragment shader is still a palette lookup and one
         * compression term, so these figures remain a **floor**: the cost of
         * geometry and overdraw with almost no per-pixel work. Stage 3's
         * procedural gel, subsurface, grain and band glow are the unmeasured
         * part, and they are the reason ADR 0006 protects the 60Hz budget in
         * the first place.
         *
         * A good number here therefore says "nothing is structurally wrong
         * yet", not "we have headroom". Someone will photograph this readout
         * and paste it into a discussion without the surrounding context, so
         * the caveat travels with the numbers rather than living only in a
         * handoff. Do not let a future edit of this string start implying more
         * than the build measures.
         */
        const val BASELINE_LABEL = "milestone1 floor - not a verdict"

        const val PLACEHOLDER = "milestone1 floor - measuring…"

        /**
         * The benchmark block's own header, carrying its own caveat for the
         * same reason: unlike the live figures above it, this one *is* a
         * complete measurement of what it claims — but it claims only solver
         * CPU, and a photograph of it must not read as a frame-rate verdict.
         */
        const val BENCH_LABEL = "solver bench - cpu only, no gpu"
    }
}
