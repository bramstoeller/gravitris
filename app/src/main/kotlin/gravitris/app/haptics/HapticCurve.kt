package gravitris.app.haptics

import gravitris.app.Tunables

/**
 * The energy → (duration, amplitude) mapping from
 * `docs/ux/feel-feedback.md` §"Impact haptics".
 *
 * Separated from the Android [ImpactHaptics] driver so the curve — the part
 * with the actual design decision in it — is unit-testable on the JVM without
 * a device or a `Vibrator`.
 *
 * | Energy | Duration | Amplitude (1..255) |
 * | ------ | -------- | ------------------ |
 * | < 0.15 | suppressed | — |
 * | 0.15   | 10ms     | 60  |
 * | 1.0    | 40ms     | 255 |
 *
 * Linear between the two endpoints. feel-feedback.md says why linear is right
 * here specifically, and it is worth not re-litigating later: unlike the glow
 * curve there is no teaching job for this ramp, just a felt-weight gradient.
 */
object HapticCurve {

    /** True when this impact should produce no haptic at all. */
    fun isSuppressed(energy: Float): Boolean = energy < Tunables.HAPTIC_ENERGY_FLOOR

    /**
     * Duration in milliseconds for an impact of [energy] (0..1). Undefined
     * below the floor — call [isSuppressed] first.
     */
    fun durationMs(energy: Float): Long {
        val t = normalise(energy)
        val span = Tunables.HAPTIC_MAX_DURATION_MS - Tunables.HAPTIC_MIN_DURATION_MS
        return Tunables.HAPTIC_MIN_DURATION_MS + (t * span).toLong()
    }

    /**
     * Amplitude on Android's 1..255 `VibrationEffect` scale for an impact of
     * [energy] (0..1). Undefined below the floor — call [isSuppressed] first.
     */
    fun amplitude(energy: Float): Int {
        val t = normalise(energy)
        val span = Tunables.HAPTIC_MAX_AMPLITUDE - Tunables.HAPTIC_MIN_AMPLITUDE
        return (Tunables.HAPTIC_MIN_AMPLITUDE + t * span)
            .toInt()
            .coerceIn(Tunables.HAPTIC_MIN_AMPLITUDE, Tunables.HAPTIC_MAX_AMPLITUDE)
    }

    /**
     * Position within the ramp, 0 at the energy floor and 1 at full energy.
     *
     * Energy above 1.0 is clamped rather than extrapolated. The core computes
     * `energy` against the run's *current* min/max mass and velocity range,
     * which rises over a run — so a transient value slightly over 1 is a
     * plausible consequence of that range moving, not a bug, and it must not
     * be allowed to ask the vibrator for an amplitude it cannot produce.
     */
    private fun normalise(energy: Float): Float {
        val floor = Tunables.HAPTIC_ENERGY_FLOOR
        return ((energy - floor) / (1f - floor)).coerceIn(0f, 1f)
    }
}
