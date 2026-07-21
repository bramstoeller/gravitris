package gravitris.app.hud

import android.content.Context
import android.provider.Settings
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * The motion tokens from `docs/ux/tokens.md` §Motion and
 * `docs/ux/visual-direction.md` §8, in one place so the HUD and the game-over
 * screen animate to the same curves and durations.
 *
 * Two vocabularies, kept visually distinct:
 *
 * - the **UI-chrome** tokens (`motion-fast`/`base`/`slow`) — ease-out /
 *   ease-in-out, nothing overshoots;
 * - the **celebratory** tokens (`motion-pop`/`motion-celebrate`) — a back-out
 *   curve that overshoots ~12% and settles, reserved for small arrivals
 *   (`motion-pop`) and the single biggest per-session moment (`motion-celebrate`,
 *   the "NEW BEST" reveal — used once per run, at most).
 *
 * ## Reduced motion
 *
 * Under Reduced Motion (`accessibility.md`), the overshoot is decorative bounce,
 * not core feedback, so `motion-pop`/`motion-celebrate` collapse to a plain
 * `motion-fast` cross-fade — no scale, no overshoot — exactly the category of
 * change already specified for screen shake and jiggle. The content still
 * *appears*; only the celebratory motion is removed. [reduceMotion] reads the
 * platform's own animator-duration-scale, which the accessibility settings and
 * battery saver both drive to zero, so this tracks the system setting rather
 * than inventing a second one.
 */
object Motion {

    // Durations, milliseconds (tokens.md §Motion).
    const val FAST_MS = 100L
    const val BASE_MS = 200L
    const val SLOW_MS = 400L
    const val POP_MS = 160L
    const val CELEBRATE_MS = 280L

    /** `cubic-bezier(0.2,0,0,1)` — ease-out, the UI-chrome micro/base curve. */
    val easeOut: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** `cubic-bezier(0.4,0,0.2,1)` — ease-in-out, screen transitions. */
    val easeInOut: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /**
     * `cubic-bezier(0.34,1.56,0.64,1)` — back-out, overshoots ~12% past the
     * target then settles. Shared by `motion-pop` and `motion-celebrate`; they
     * differ only in duration and the scale delta the caller drives through it.
     */
    val backOut: Interpolator = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)

    /**
     * Whether celebratory motion should collapse to a plain cross-fade.
     *
     * Reads `Settings.Global.ANIMATOR_DURATION_SCALE`: the accessibility
     * "Remove animations" toggle and battery saver both set it to 0. Any read
     * failure is treated as "motion allowed" — the safe default for a decorative
     * effect, and it never blocks content from appearing either way.
     */
    fun reduceMotion(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    } catch (e: Exception) {
        false
    }
}
