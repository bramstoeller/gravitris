package gravitris.app.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import gravitris.app.sim.ImpactList

/**
 * Turns simulation impact events into vibration.
 *
 * The brief ranks this above any visual effect for conveying *weight*: a short
 * sharp pulse scaled to mass and fall speed does more for perceived heaviness
 * than shading does, and it costs a fraction as much. It is the highest
 * return-per-line component in the shell.
 *
 * ## Amplitude control is not optional to get right
 *
 * The whole design is that the pulse is *scaled*. On a device with amplitude
 * control we use [VibrationEffect.createOneShot] with a real amplitude. On one
 * without, feel-feedback.md specifies the fallback explicitly: the platform's
 * default click effect, gated by the same energy floor. That fallback loses
 * the gradient — every impact feels the same — which is a real degradation and
 * is why the visual squash always carries the same information independently.
 * feel-feedback.md: "Haptics are never the sole channel for anything."
 *
 * ## Why a permission is required, and what it is not
 *
 * `Vibrator.vibrate` requires `android.permission.VIBRATE`. It is a normal,
 * install-time permission with no runtime prompt and no data access — it
 * cannot read anything, and it does not weaken the no-network guarantee the
 * client asked to have enforced. See the handoff and the note in
 * `CheckMergedManifest` for why the merged-manifest check moved from "zero
 * permissions" to an explicit allowlist rather than being switched off.
 */
class ImpactHaptics(context: Context, private val view: View) {

    private val vibrator: Vibrator? = resolveVibrator(context)

    private val hasAmplitudeControl: Boolean =
        vibrator?.hasAmplitudeControl() == true

    /**
     * True when the device can scale the pulse. Surfaced so the frame-time
     * readout can say so: if the client reports "the haptics all feel the
     * same", we need to know whether that is our curve or their hardware
     * before we spend a day retuning the curve.
     */
    val isScaled: Boolean get() = vibrator != null && hasAmplitudeControl

    /** Strongest impact energy seen since the last [flush]. */
    private var pendingEnergy = 0f

    /**
     * Note every impact in [impacts], keeping the strongest.
     *
     * Called after each simulation tick. A rendered frame can contain up to
     * four ticks (ADR 0006 clamps catch-up there), and a single tick can itself
     * produce several contacts — a piece landing across two blocks below it is
     * two. Firing a pulse for each would smear one landing into a buzz, which
     * is the exact failure the energy floor exists to prevent, arriving by a
     * different route. So impacts accumulate and [flush] plays at most one
     * pulse per frame.
     */
    fun accumulate(impacts: ImpactList) {
        for (i in 0 until impacts.count) {
            val strength = impacts.strength[i]
            if (strength > pendingEnergy) pendingEnergy = strength
        }
    }

    /** Play the strongest accumulated impact, if any cleared the floor. */
    fun flush() {
        val strongest = pendingEnergy
        pendingEnergy = 0f

        val vibrator = this.vibrator ?: return
        if (HapticCurve.isSuppressed(strongest)) return

        val durationMs = HapticCurve.durationMs(strongest)

        if (hasAmplitudeControl) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, HapticCurve.amplitude(strongest))
            )
        } else {
            // No amplitude control: the platform click, gated by the same
            // floor. Routed through the View so it also respects the user's
            // system-level haptic feedback setting, which a raw Vibrator call
            // would bypass.
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }

    /** Stops any pulse still in flight and drops pending impacts. Called when
     *  the game pauses so a vibration cannot outlive the frame that caused it,
     *  and so a resume does not fire a pulse for an impact from before. */
    fun cancel() {
        pendingEnergy = 0f
        vibrator?.cancel()
    }

    private companion object {
        fun resolveVibrator(context: Context): Vibrator? {
            // getSystemService(Vibrator) is deprecated from API 31 in favour of
            // VibratorManager. minSdk is 29 (ADR 0010), so both paths are live.
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            // A device may report a Vibrator that does not exist — a tablet, an
            // emulator, or a phone with the motor disabled. hasVibrator() is
            // the only reliable test, and treating "absent" as null here means
            // every call site is a no-op rather than a guarded branch.
            return vibrator?.takeIf { it.hasVibrator() }
        }
    }
}
