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
 * - **"NEW BEST" and the best-comparison row** are omitted entirely: there is
 *   no local persistence yet (D8), so every run is `game-over.md`'s "first-ever
 *   run" state, which by its own definition shows the score plainly with **no
 *   badge and no best row**. Building a hidden badge now would be speculative —
 *   D8 adds the `motion-celebrate` "NEW BEST" reveal and the best row together,
 *   when there is finally a best to beat. `Motion.CELEBRATE_MS`/`backOut` are in
 *   place for it (`visual-direction.md` §8).
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

    private companion object {
        val COLOR_TEXT = Color.argb(255, 0xF2, 0xF1, 0xEC) // color-text #F2F1EC
        val COLOR_TEXT_MUTED = Color.argb((0.65f * 255).toInt(), 0xF2, 0xF1, 0xEC) // @65%
        val COLOR_GLOW = Color.argb(255, 0xFF, 0xB3, 0x47) // color-glow #FFB347
        val COLOR_ON_GLOW = Color.argb(255, 0x0B, 0x0B, 0x0B) // dark text on the amber pill
    }
}
