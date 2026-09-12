package com.smriti.aqua.sensors

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic feedback for probe outcomes.
 *
 * - [buzzAnomaly]: single 180 ms pulse whose amplitude scales with the anomaly
 *   score (60 + 195 * score, clamped to 1..255) — stronger anomaly, stronger buzz.
 * - [buzzConfirm]: short double-tap to acknowledge a user confirmation.
 *
 * Uses VibratorManager on API 31+ and the deprecated Vibrator path below that
 * (minSdk 26 always has amplitude control via [VibrationEffect]).
 *
 * Requires the VIBRATE permission (declared in the manifest; normal protection
 * level, no runtime request needed).
 */
class HapticFeedback(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager =
                context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /**
     * One-shot 180 ms buzz; amplitude = 60 + 195 * [score] clamped to 1..255.
     * [score] is expected in [0,1] but is clamped defensively.
     */
    fun buzzAnomaly(score: Float) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val clamped = score.coerceIn(0f, 1f)
        val amplitude = (60 + (195 * clamped).toInt()).coerceIn(1, 255)
        v.vibrate(
            VibrationEffect.createOneShot(ANOMALY_DURATION_MS, amplitude)
        )
    }

    /** Short double-tap confirmation pattern (on-off-on). */
    fun buzzConfirm() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(
            VibrationEffect.createWaveform(CONFIRM_TIMINGS, CONFIRM_AMPLITUDES, NO_REPEAT)
        )
    }

    /** Stops any ongoing vibration. */
    fun cancel() {
        vibrator?.cancel()
    }

    private companion object {
        const val ANOMALY_DURATION_MS = 180L
        const val NO_REPEAT = -1

        /** Double-tap: wait 0, buzz 60 ms, pause 80 ms, buzz 60 ms. */
        val CONFIRM_TIMINGS = longArrayOf(0L, 60L, 80L, 60L)
        val CONFIRM_AMPLITUDES = intArrayOf(
            0,
            VibrationEffect.DEFAULT_AMPLITUDE,
            0,
            VibrationEffect.DEFAULT_AMPLITUDE
        )
    }
}
