package gravitris.app.hud

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The designed game-over screen (`docs/ux/screens/game-over.md`,
 * `visual-direction.md`), replacing the bare `TextView` the client saw.
 *
 * ## Composition
 *
 * A sheet over the game, not a replacement for it: the **frozen final stack and
 * the environment stay visible, dimmed** behind `color-overlay-scrim`
 * (`#000000` @ 82%), so the player sees roughly how their stack looked when it
 * ended (`game-over.md`, `ia.md` — a run always ends *here* rather than cutting
 * away from it). The GL surface keeps drawing underneath; this overlay only adds
 * the scrim and the centred content.
 *
 * Centred content, top to bottom: a `SCORE` caption, the run's score
 * (`type-display`), and a prominent **PLAY AGAIN** pill (`radius-pill`,
 * `color-glow`-tinted, one tap, no confirmation) — the one thing that matters
 * for the "beat your own score" loop.
 *
 * ## What is deferred, and why
 *
 * - **Score** is wired to the real value, which is 0 until scoring (**D8**)
 *   lands. `game-over.md`'s "auto-shrink very large scores" rule is not needed
 *   yet and is left for D8 with the rest of scoring.
 * - **"NEW BEST" and the best-comparison row** are omitted: there is no local
 *   persistence yet (D8), so every run is `game-over.md`'s "first-ever run" state
 *   — show the score plainly, omit the best row, no badge. This keeps the
 *   `motion-celebrate` "NEW BEST" reveal meaningful for when it can actually
 *   fire. The hook is here: [showNewBest] is the single place D8 turns it on.
 * - **The "Title" secondary button** is omitted — there is no Title screen built
 *   (menus are deferred), so a Title button would strand the player. Play Again
 *   is the whole screen, which is exactly the "one more try" loop it exists for.
 */
class GameOverView(
    context: Context,
    private val onPlayAgain: () -> Unit,
) {

    private val density = context.resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).toInt()

    private val caption = TextView(context).apply {
        text = "SCORE"
        setTextColor(COLOR_TEXT_MUTED)
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) // type-caption
        letterSpacing = 0.15f
        gravity = Gravity.CENTER
    }

    private val scoreText = TextView(context).apply {
        setTextColor(COLOR_TEXT)
        setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f) // type-display
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    /**
     * The "NEW BEST" badge — built, hidden, and never shown this round (no
     * persistence). `game-over.md`: `color-glow` accent, `motion-celebrate` on
     * reveal — the one screen and the one moment per session that the bigger,
     * reserved motion token is for. [showNewBest] is where D8 wires it live.
     */
    private val newBest = TextView(context).apply {
        text = "NEW BEST"
        setTextColor(COLOR_GLOW)
        setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f) // type-title
        letterSpacing = 0.1f
        gravity = Gravity.CENTER
        visibility = View.GONE
    }

    private val playAgain = TextView(context).apply {
        text = "PLAY AGAIN"
        // Dark text on the amber pill — the highest-contrast, most
        // unmistakably-a-CTA treatment of color-glow on a dark scrim.
        setTextColor(COLOR_ON_GLOW)
        setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) // type-body
        gravity = Gravity.CENTER
        val padH = dp(32f)
        val padV = dp(14f)
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            cornerRadius = dp(999f).toFloat() // radius-pill
            setColor(COLOR_GLOW)
        }
        isClickable = true
        isFocusable = true
        contentDescription = "Play again"
        setOnClickListener { this@GameOverView.onPlayAgain() }
    }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(newBest, spacedBelow(0))
        addView(caption, spacedBelow(0))
        addView(scoreText, spacedBelow(4))
        addView(playAgain, spacedBelow(32))
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        )
    }

    /** The overlay root, added above the GL surface and the HUD. */
    @SuppressLint("ClickableViewAccessibility")
    val view: FrameLayout = FrameLayout(context).apply {
        // color-overlay-scrim #000000 @ 82% — dims the frozen stack and the
        // environment, which keep rendering underneath.
        setBackgroundColor(Color.argb((0.82f * 255).toInt(), 0, 0, 0))
        // Consume touches so a drag does not leak to the (terminal) game beneath;
        // only Play Again is actionable. No-op click keeps the scrim from being a
        // hidden tap-to-restart, which game-over.md's one-deliberate-tap intent
        // rules out.
        isClickable = true
        isFocusable = false
        visibility = View.GONE
        addView(column)
    }

    private fun spacedBelow(marginTopDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = dp(marginTopDp.toFloat())
            gravity = Gravity.CENTER_HORIZONTAL
        }

    /** Keep the centred column clear of the bars/cutout on very tall content. */
    fun applyInsets(left: Int, top: Int, right: Int, bottom: Int) {
        view.setPadding(left, top, right, bottom)
    }

    /**
     * Raise the screen for a finished run with [scoreValue].
     *
     * Fades the sheet in (`motion-base`); the score and button arrive with it.
     * Under Reduced Motion the scale/translate is dropped and it simply
     * cross-fades — a fade is not the flashing category `accessibility.md`
     * removes. Idempotent: a repeated call while shown just refreshes the score.
     */
    fun show(scoreValue: Int) {
        scoreText.text = String.format("%,d", scoreValue)
        // No persistence yet, so never a new best — always the first-ever-run
        // presentation (no badge, no best row).
        newBest.visibility = View.GONE

        if (view.visibility == View.VISIBLE) return
        view.visibility = View.VISIBLE
        playAgain.requestFocus()

        val reduce = Motion.reduceMotion(view.context)
        view.alpha = 0f
        view.animate().cancel()
        view.animate().alpha(1f)
            .setInterpolator(Motion.easeOut)
            .setDuration(Motion.BASE_MS)
            .start()

        if (!reduce) {
            column.translationY = dp(16f).toFloat()
            column.animate().cancel()
            column.animate().translationY(0f)
                .setInterpolator(Motion.easeOut)
                .setDuration(Motion.BASE_MS)
                .start()
        } else {
            column.translationY = 0f
        }
    }

    /** Dismiss the screen. */
    fun hide() {
        view.visibility = View.GONE
    }

    /**
     * The single hook D8 flips to celebrate a beaten best: reveal the badge with
     * `motion-celebrate` (or a plain cross-fade under Reduced Motion). Unused
     * this round — there is no best to beat without persistence — but named and
     * placed so scoring wires the one reserved celebratory moment in one spot.
     */
    @Suppress("unused")
    fun showNewBest() {
        newBest.visibility = View.VISIBLE
        if (Motion.reduceMotion(view.context)) return
        newBest.scaleX = 0.85f
        newBest.scaleY = 0.85f
        newBest.animate()
            .scaleX(1.15f).scaleY(1.15f)
            .setInterpolator(Motion.backOut)
            .setDuration(Motion.CELEBRATE_MS)
            .withEndAction {
                newBest.animate().scaleX(1f).scaleY(1f)
                    .setInterpolator(Motion.easeOut)
                    .setDuration(Motion.FAST_MS)
                    .start()
            }
            .start()
    }

    private companion object {
        val COLOR_TEXT = Color.argb(255, 0xF2, 0xF1, 0xEC) // color-text #F2F1EC
        val COLOR_TEXT_MUTED = Color.argb((0.65f * 255).toInt(), 0xF2, 0xF1, 0xEC) // @65%
        val COLOR_GLOW = Color.argb(255, 0xFF, 0xB3, 0x47) // color-glow #FFB347
        val COLOR_ON_GLOW = Color.argb(255, 0x0B, 0x0B, 0x0B) // dark text on the amber pill
    }
}
