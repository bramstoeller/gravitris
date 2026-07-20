package gravitris.app.haptics

import android.annotation.TargetApi
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import gravitris.game.ImpactList

/**
 * Turns simulation impact events into vibration.
 *
 * The brief ranks this above any visual effect for conveying *weight*: a short
 * sharp pulse scaled to mass and fall speed does more for perceived heaviness
 * than shading does, and it costs a fraction as much. It is the highest
 * return-per-line component in the shell.
 *
 * ## Why this class is shaped the way it is
 *
 * Milestone 1 came back from the client's Fairphone 6 reading `haptics:fixed`
 * on both runs, and nobody could say why. The readout had exactly two states,
 * so "this device has no amplitude control", "the vibrator never resolved at
 * all", and "we asked before the system was ready" all printed the same word.
 * That is the defect this rewrite is mostly about: a fallback that cannot say
 * why it fired is a fallback nobody can act on.
 *
 * Three things follow from that, and each is load-bearing:
 *
 * 1. **[Mode] distinguishes the reasons.** [Mode.NO_VIBRATOR] and
 *    [Mode.NO_AMPLITUDE_CONTROL] are different findings with different
 *    consequences and they no longer share a label.
 *
 * 2. **The capability is not sampled once in `onCreate`.** AOSP's
 *    `SystemVibrator.getInfo()` returns `VibratorInfo.EMPTY_VIBRATOR_INFO` —
 *    which reports no capabilities, so `hasAmplitudeControl()` is `false` —
 *    when the vibrator manager service is not ready yet, logging
 *    *"Vibrator manager service not ready; Info not yet available for
 *    vibrator"*. `onCreate` is the earliest and worst moment in the process to
 *    ask. The previous version asked exactly there, cached the answer in a
 *    `val`, and could never recover. We now re-ask until the answer resolves or
 *    [RESOLVE_ATTEMPTS] frames have passed, and report [Mode.PENDING] meanwhile.
 *
 * 3. **Losing the vibrator no longer loses the fallback too.** The previous
 *    `flush()` returned early when the `Vibrator` was null, *before* reaching
 *    the `performHapticFeedback` branch — so the one case where we most needed
 *    a degraded cue was the one case that played nothing at all, while the
 *    readout claimed a fixed pulse was running.
 *
 * ## Why a permission is required, and what it is not
 *
 * `Vibrator.vibrate` requires `android.permission.VIBRATE`. It is a normal,
 * install-time permission with no runtime prompt and no data access — it
 * cannot read anything, and it does not weaken the no-network guarantee the
 * client asked to have enforced. See the note in `CheckMergedManifest` for why
 * the merged-manifest check moved from "zero permissions" to an explicit
 * allowlist rather than being switched off.
 */
class ImpactHaptics(private val context: Context, private val view: View) {

    /**
     * What the haptic channel is actually doing, and — when it is not scaling —
     * why not.
     *
     * The [readout] strings go on screen verbatim. They are deliberately
     * self-explanatory rather than terse: the person reading them is the client,
     * holding the phone, and the whole reason this enum exists is that
     * `haptics:fixed` told them nothing.
     */
    enum class Mode(val readout: String) {
        /** Capability not established yet. A fixed click plays meanwhile. */
        PENDING("haptics:pending (asking)"),

        /** Amplitude control present — the pulse is scaled to impact energy. */
        SCALED("haptics:scaled"),

        /** Vibrator present, but it cannot vary amplitude. Fixed click. */
        NO_AMPLITUDE_CONTROL("haptics:fixed (no amp control)"),

        /** No usable vibrator at all. Only the platform click remains. */
        NO_VIBRATOR("haptics:fixed (no vibrator)"),

        /** `vibrate()` threw. Shown rather than swallowed — see [flush]. */
        VIBRATE_FAILED("haptics:fixed (vibrate threw)"),
    }

    private var vibrator: Vibrator? = null
    private var resolveAttempts = 0

    /**
     * Impacts handed to [accumulate] since launch, counted *before* the energy
     * floor, and pulses actually requested from the platform.
     *
     * These are on screen because the client reported feeling nothing at all
     * while the readout claimed a fixed pulse was running, and no one could say
     * where the chain broke. Two counters and a last-energy value split that
     * one symptom into three distinguishable faults:
     *
     * - `imp:0` — no impact events are crossing the `:core-sim` contract. The
     *   fault is upstream of this class entirely.
     * - `imp:N puls:0` — impacts arrive but every one is below
     *   [gravitris.app.Tunables.HAPTIC_ENERGY_FLOOR]. The fault is the energy
     *   scale or the floor.
     * - `imp:N puls:M` with nothing felt — we asked the platform and the
     *   platform did not deliver. The fault is the device, its haptic settings,
     *   or the OEM.
     *
     * Cheap: two int increments per frame, printed at the readout's ~4Hz.
     */
    var impactsSeen: Int = 0
        private set
    var pulsesRequested: Int = 0
        private set
    var lastEnergy: Float = 0f
        private set

    /**
     * The two user settings that can silently drop a pulse we successfully
     * requested, read straight from `Settings.System` and put on screen.
     *
     * `vibrate()` returns `void`. There is no callback, no return code and no
     * exception when the platform decides not to play a vibration, so "we asked
     * and the system dropped it" is *invisible* from inside the app. That is
     * the exact ambiguity that cost a round trip: the readout could not tell it
     * apart from "no impact event ever arrived".
     *
     * These two values close it from the other end. If the counters show pulses
     * were requested and these show vibration is enabled, the fault is below
     * us in the platform or the hardware. If they show it disabled, we have our
     * answer without asking the client to go digging through Settings — which
     * is how we learned it the first time, one message at a time.
     *
     * `null` means the setting could not be read.
     */
    var masterVibrateOn: Boolean? = null
        private set
    var touchFeedbackOn: Boolean? = null
        private set

    /**
     * The current state of the haptic channel. Read by the frame-time readout
     * so a photograph of the client's screen is enough to diagnose this.
     */
    var mode: Mode = Mode.PENDING
        private set

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
        impactsSeen += impacts.count
        for (i in 0 until impacts.count) {
            val strength = impacts.strength[i]
            if (strength > pendingEnergy) pendingEnergy = strength
        }
    }

    /**
     * Play the strongest accumulated impact, if any cleared the floor.
     *
     * **Called on the GL thread**, from `GameRenderer.onDrawFrame`. That is the
     * single most important fact about this method and it is why the delivery
     * path below looks the way it does — see [clickFallback].
     */
    fun flush() {
        val strongest = pendingEnergy
        pendingEnergy = 0f

        resolveCapability()

        if (HapticCurve.isSuppressed(strongest)) return

        lastEnergy = strongest
        pulsesRequested++

        val vibrator = this.vibrator
        if (vibrator == null) {
            clickFallback()
            return
        }

        // A Vibrator call is a binder call and is safe from the GL thread, so
        // whenever we hold one it is used for *both* the scaled and the fixed
        // pulse. The fixed case asks for DEFAULT_AMPLITUDE rather than a
        // computed one, which is exactly what "no amplitude control" means.
        val amplitude =
            if (mode == Mode.SCALED) HapticCurve.amplitude(strongest)
            else VibrationEffect.DEFAULT_AMPLITUDE
        val effect = VibrationEffect.createOneShot(HapticCurve.durationMs(strongest), amplitude)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrateAsMedia(vibrator, effect)
            } else {
                // vibrate(effect, VibrationAttributes) is API 33. Below it the
                // pulse goes out unattributed, which the platform treats as
                // USAGE_UNKNOWN — not ideal, but it is not classified as touch
                // feedback either, so it is not caught by the setting that
                // silenced Milestone 1 on the client's phone.
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect)
            }
        } catch (e: RuntimeException) {
            // Not swallowed: the mode latches to VIBRATE_FAILED, which is on
            // screen and distinct from every other reason. A vibrate() that
            // throws mid-demo should degrade to the click rather than crash the
            // client's phone, but it must not do so invisibly — that is
            // precisely how we got here.
            Log.w(TAG, "vibrate() failed; falling back to the platform click", e)
            mode = Mode.VIBRATE_FAILED
            clickFallback()
        }
    }

    /** Cached so the attributes are built once rather than per pulse. */
    private var mediaAttributes: VibrationAttributes? = null

    /**
     * Emit the pulse classified as **`USAGE_MEDIA`**, and the reason is what the
     * category means.
     *
     * AOSP documents `USAGE_MEDIA` as "media vibrations, such as music, movie,
     * soundtrack, animations, *games*, or any interactive media *that isn't for
     * touch feedback specifically*", against `USAGE_TOUCH` for "tap, long
     * press, drag and scroll". A block landing under gravity is a physical
     * event the game is reporting, not an acknowledgement of the player's
     * finger — the player may not even be touching the screen when it lands. On
     * the documented meanings this is not a close call.
     *
     * It also matters practically, and it is worth being precise about which
     * way the causation runs. The client felt nothing on Milestone 1 and told
     * us their phone vibrates only for notifications — touch feedback is
     * switched off. AOSP's `VibrationSettings` forces `USAGE_TOUCH` to
     * `VIBRATION_INTENSITY_OFF` whenever `Settings.System.HAPTIC_FEEDBACK_ENABLED`
     * is 0, silently and with no error, while `USAGE_MEDIA` is gated by a
     * separate `MEDIA_VIBRATION_INTENSITY` and is untouched by it. The old
     * build's touch-classified fallback was therefore guaranteed to be dropped
     * on this device. `USAGE_MEDIA` is the correct classification on its own
     * terms; surviving this user's settings is a consequence of classifying
     * honestly, not the reason for choosing it.
     *
     * This is **not** a route around the user's intent. The master `VIBRATE_ON`
     * toggle suppresses every usage except accessibility, and
     * `MEDIA_VIBRATION_INTENSITY` can independently silence us. Someone who
     * turns vibration off still gets no vibration.
     */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun vibrateAsMedia(vibrator: Vibrator, effect: VibrationEffect) {
        val attributes = mediaAttributes
            ?: VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_MEDIA)
                .build()
                .also { mediaAttributes = it }
        vibrator.vibrate(effect, attributes)
    }

    /**
     * The platform click, for when there is no usable [Vibrator] at all.
     *
     * **Posted to the UI thread.** `View.performHapticFeedback` reaches into
     * the view's `AttachInfo` and on to `ViewRootImpl`; calling it from the GL
     * thread is a cross-thread View access, and in practice it does nothing at
     * all rather than failing loudly.
     *
     * The shipped Milestone 1 build called it directly from
     * `GameRenderer.onDrawFrame`. Together with the old `flush()` returning
     * early whenever the `Vibrator` was null — before ever reaching this call —
     * that gives two independent paths in that build which produce exactly the
     * symptom the client reported: a readout confidently saying `haptics:fixed`
     * and a phone that never moved.
     */
    private fun clickFallback() {
        view.post { view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK) }
    }

    /**
     * Establish [mode], re-asking while the answer may still be provisional.
     *
     * A negative answer is only trusted after [RESOLVE_ATTEMPTS] tries, because
     * an early negative is indistinguishable from "the vibrator service has not
     * finished starting". A positive answer is trusted immediately — the system
     * does not invent amplitude control it does not have. Once latched this is
     * a single comparison per frame.
     */
    private fun resolveCapability() {
        if (mode != Mode.PENDING) return

        resolveAttempts++
        val resolved = resolveVibrator(context)
        vibrator = resolved

        val observed = when {
            resolved == null -> Mode.NO_VIBRATOR
            resolved.hasAmplitudeControl() -> Mode.SCALED
            else -> Mode.NO_AMPLITUDE_CONTROL
        }

        if (observed == Mode.SCALED || resolveAttempts >= RESOLVE_ATTEMPTS) {
            mode = observed
            readSystemVibrationSettings()
            Log.i(
                TAG,
                "haptics resolved after $resolveAttempts attempts: ${mode.readout}; " +
                    "vibrate_on=$masterVibrateOn touch_feedback=$touchFeedbackOn",
            )
        }
    }

    /**
     * Read the two settings that can suppress us. Read once, at resolve time —
     * this is a `ContentResolver` query and has no business running per frame.
     *
     * `HAPTIC_FEEDBACK_ENABLED` is public API and the haptics documentation
     * explicitly tells apps to check it. `vibrate_on` is the master toggle;
     * its constant is not public API, so it is queried by its literal key,
     * which is stable and read-only. A missing or unreadable value stays
     * `null` rather than being guessed at.
     */
    private fun readSystemVibrationSettings() {
        val resolver = context.contentResolver
        masterVibrateOn = readSetting(resolver, MASTER_VIBRATE_KEY)
        touchFeedbackOn = readSetting(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED)
    }

    /**
     * Stops any pulse still in flight and drops pending impacts. Called when
     * the game pauses so a vibration cannot outlive the frame that caused it,
     * and so a resume does not fire a pulse for an impact from before.
     */
    fun cancel() {
        pendingEnergy = 0f
        try {
            vibrator?.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "vibrator cancel() failed", e)
        }
    }

    private companion object {
        const val TAG = "GravitrisHaptics"

        /**
         * Frames to keep re-asking before trusting a negative capability answer
         * — two seconds at 60Hz. Long enough to outlast a cold start of the
         * vibrator service, short enough that the readout settles well before
         * the client has finished looking at the first screen.
         */
        const val RESOLVE_ATTEMPTS = 120

        /** `Settings.System.VIBRATE_ON` — the master toggle. Not public API, so
         *  it is read by its stable literal key. */
        const val MASTER_VIBRATE_KEY = "vibrate_on"

        fun readSetting(resolver: ContentResolver, key: String): Boolean? =
            try {
                when (Settings.System.getInt(resolver, key, -1)) {
                    -1 -> null
                    0 -> false
                    else -> true
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "could not read setting $key", e)
                null
            }

        fun resolveVibrator(context: Context): Vibrator? {
            // getSystemService(Vibrator) is deprecated from API 31 in favour of
            // VibratorManager. minSdk is 29 (ADR 0010), so both paths are live.
            //
            // These two branches resolve to the same object: AOSP's
            // SystemVibratorManager.getDefaultVibrator() is literally
            // `mContext.getSystemService(Vibrator.class)`. The split is kept
            // only because the deprecation warning is otherwise unsuppressable
            // on API 31+, not because the branches differ in behaviour.
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Vibrator::class.java)
            }
            // A device may report a Vibrator that does not exist — a tablet, an
            // emulator, or a phone with the motor disabled. It may also hand
            // back a NullVibrator because the vibrator service was not ready,
            // which is why the caller retries rather than trusting this once.
            return vibrator?.takeIf { it.hasVibrator() }
        }
    }
}
