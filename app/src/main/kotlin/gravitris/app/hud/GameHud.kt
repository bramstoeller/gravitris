package gravitris.app.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The in-game HUD (`docs/ux/screens/playing.md`, `visual-direction.md` §6):
 * the real chrome that replaces the debug frame-time readout as the primary
 * on-screen text.
 *
 * ## It lives in the Android View layer, and that is the whole point
 *
 * Score, level and pause are ordinary `Canvas`-drawn Views composited by the
 * platform's own (hardware-composited, essentially free) View pipeline over the
 * GL surface — **zero fragment-shader cost**. `visual-direction.md` §6 makes
 * this the first thing to build precisely because it costs nothing on the GPU:
 * *gameplay* feedback lives on the material (squash, glow, shake), but *status*
 * chrome — a number, a label, a small shape — was never what that principle
 * protected, and drawing it in GL would spend fragment budget a `TextView` gives
 * for free.
 *
 * ## What is here, and what is deliberately deferred
 *
 * - **Score** (top-left, `type-title`) and **Level chip** (top-centre) are
 *   wired to the real `SimState.score`/`level`. Those are placeholders today —
 *   scoring is backlog **D8** and `SimState.score` is hardwired to 0, `level` to
 *   1 — so the HUD presents the frame those values will live in and shows what
 *   exists, without faking a number. When D8 lands, the real values flow through
 *   unchanged, and the score-pop "+N" (`visual-direction.md` §7.3) is added then.
 * - **Best score** is omitted — there is no persistence yet (D8), and
 *   `playing.md` already specifies omitting the best row when no best exists.
 * - **Next-piece preview** is omitted this round: `SimState` does not publish
 *   the upcoming piece (`PieceSequence.peek()` is internal to `:core-sim`), so
 *   there is no contract data to wire. Its place in the layout is the top-right
 *   column beneath the pause icon (`playing.md`); it slots in there as a new
 *   child once an Architect contract change publishes the upcoming piece, per
 *   the UX Designer's scoping. Nothing speculative is built for it now.
 * - **Pause** (top-right, 48×48dp) toggles pause via [onPause].
 *
 * The whole HUD is padded inside the safe-area insets ([applyInsets]) so nothing
 * sits under the status bar or a camera cutout.
 */
class GameHud(context: Context, private val onPause: () -> Unit) {

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float): Int = (value * density).toInt()
    private fun sp(value: Float): Float = value // TextView.setTextSize takes sp directly

    /** The container added to the activity's root, above the GL surface. */
    val view: FrameLayout = FrameLayout(context)

    private val score: TextView = TextView(context).apply {
        setTextColor(COLOR_TEXT)
        // type-title: 24sp / 600. The geometric sans is whatever the toolchain
        // provides (tokens.md); bold-500..600 maps to Typeface.BOLD here.
        setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(24f))
        includeFontPadding = false
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        )
    }

    private val levelChip: TextView = TextView(context).apply {
        setTextColor(COLOR_TEXT)
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(13f)) // type-caption
        includeFontPadding = false
        // color-surface chip, radius-sm (4dp), a lightweight elevation-1 read.
        background = GradientDrawable().apply {
            cornerRadius = dp(4f).toFloat()
            setColor(COLOR_SURFACE)
        }
        val padH = dp(10f)
        val padV = dp(5f)
        setPadding(padH, padV, padH, padV)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )
    }

    private val pause: PauseButton = PauseButton(context).apply {
        val size = dp(48f)
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
        contentDescription = "Pause"
        isClickable = true
        isFocusable = true
        setOnClickListener { this@GameHud.onPause() }
    }

    private var lastScore = Int.MIN_VALUE
    private var lastLevel = Int.MIN_VALUE

    init {
        view.addView(score)
        view.addView(levelChip)
        view.addView(pause)
        // The HUD must not intercept the drag-anywhere gestures — only the pause
        // icon is interactive. The container passes touches through; the pause
        // button is the sole clickable child.
        view.isClickable = false
        view.isFocusable = false
    }

    /**
     * Keep the HUD inside the safe area (`playing.md`: it "stays fixed in its
     * corner regardless of stack height, never obscured by gameplay"). Applied
     * as padding on the container so every element shifts together.
     */
    fun applyInsets(left: Int, top: Int, right: Int) {
        val gap = dp(12f)
        view.setPadding(left + gap, top + gap, right + gap, 0)
    }

    /**
     * Refresh the HUD from the latest simulation values. Called on the UI thread
     * at the renderer's ~4Hz publish rate — a number updating a few times a
     * second is imperceptibly different from every frame and keeps the HUD off
     * the render thread's back.
     *
     * A changed value arrives with `motion-pop` — the small celebratory overshoot
     * `visual-direction.md` §8 reserves for arrivals — unless Reduced Motion is
     * on, in which case it simply updates (the pop collapses to no motion). Today
     * the values do not change (score 0, level 1), so this is wired and ready
     * rather than visibly firing; D8 is what makes it move.
     */
    fun update(scoreValue: Int, levelValue: Int) {
        if (scoreValue != lastScore) {
            score.text = formatScore(scoreValue)
            if (lastScore != Int.MIN_VALUE) pop(score)
            lastScore = scoreValue
        }
        if (levelValue != lastLevel) {
            levelChip.text = "LV $levelValue"
            if (lastLevel != Int.MIN_VALUE) pop(levelChip)
            lastLevel = levelValue
        }
    }

    /** `motion-pop`: scale in with a back-out overshoot, collapsing to nothing
     *  under Reduced Motion. */
    private fun pop(target: View) {
        if (Motion.reduceMotion(target.context)) return
        target.animate().cancel()
        target.scaleX = 0.8f
        target.scaleY = 0.8f
        target.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(Motion.backOut)
            .setDuration(Motion.POP_MS)
            .start()
    }

    private fun formatScore(value: Int): String =
        // Grouped thousands, matching the wireframe's "4,210". Locale-grouped so
        // the separator matches the player's locale rather than being hardcoded.
        String.format("%,d", value)

    /**
     * The pause glyph, drawn rather than shipped as a vector asset — two rounded
     * bars in `color-text`, centred in a 48dp target. No UI framework and no
     * drawable resource for one two-rectangle icon (ADR 0010 keeps the shell
     * framework-free).
     */
    @SuppressLint("ViewConstructor")
    private inner class PauseButton(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TEXT }
        private val bar = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val barW = dp(4f).toFloat()
            val barH = dp(16f).toFloat()
            val gap = dp(4f).toFloat()
            val r = dp(2f).toFloat()
            val cx = w / 2f
            val cy = h / 2f
            // Left bar.
            bar.set(cx - gap / 2f - barW, cy - barH / 2f, cx - gap / 2f, cy + barH / 2f)
            canvas.drawRoundRect(bar, r, r, paint)
            // Right bar.
            bar.set(cx + gap / 2f, cy - barH / 2f, cx + gap / 2f + barW, cy + barH / 2f)
            canvas.drawRoundRect(bar, r, r, paint)
        }
    }

    private companion object {
        // Tokens (docs/ux/tokens.md §Colour).
        val COLOR_TEXT = Color.argb(255, 0xF2, 0xF1, 0xEC) // color-text #F2F1EC
        val COLOR_SURFACE = Color.argb(255, 0x1B, 0x1E, 0x29) // color-surface #1B1E29
    }
}
