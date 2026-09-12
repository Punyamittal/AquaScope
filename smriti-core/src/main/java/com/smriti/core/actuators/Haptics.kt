package com.smriti.core.actuators

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Haptic events (SPEC §2.3). */
enum class HapticEvent { OCR_TICK, KILL_SNAP, RECALL_CONFIRM, FALL_ALARM }

/**
 * Dual X-axis linear haptics driver.
 *
 * Uses VibratorManager.defaultVibrator on API 31+ and the deprecated Vibrator
 * service below. When [Vibrator.hasAmplitudeControl] is false, falls back to
 * the same timings with default amplitude. Never throws — actuator path must
 * degrade silently.
 *
 * Waveforms (SPEC §2.3):
 *  - [HapticEvent.OCR_TICK]: oneShot(12ms, 180)
 *  - [HapticEvent.KILL_SNAP]: oneShot(40ms, 255)
 *  - [HapticEvent.RECALL_CONFIRM]: waveform([0,40,90,40]ms, [0,200,0,200])
 *  - [HapticEvent.FALL_ALARM]: waveform([0,120,100,120,100,120]ms, [0,255,0,255,0,255])
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    fun haptic(event: HapticEvent) {
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            if (v.hasAmplitudeControl()) {
                when (event) {
                    HapticEvent.OCR_TICK ->
                        v.vibrate(VibrationEffect.createOneShot(12L, 180))
                    HapticEvent.KILL_SNAP ->
                        v.vibrate(VibrationEffect.createOneShot(40L, 255))
                    HapticEvent.RECALL_CONFIRM ->
                        v.vibrate(
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 40, 90, 40),
                                intArrayOf(0, 200, 0, 200),
                                -1,
                            )
                        )
                    HapticEvent.FALL_ALARM ->
                        v.vibrate(
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 120, 100, 120, 100, 120),
                                intArrayOf(0, 255, 0, 255, 0, 255),
                                -1,
                            )
                        )
                }
            } else {
                // Timings-only fallback: same envelopes, system default amplitude.
                when (event) {
                    HapticEvent.OCR_TICK ->
                        v.vibrate(VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE))
                    HapticEvent.KILL_SNAP ->
                        v.vibrate(VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE))
                    HapticEvent.RECALL_CONFIRM ->
                        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 90, 40), -1))
                    HapticEvent.FALL_ALARM ->
                        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 100, 120, 100, 120), -1))
                }
            }
        } catch (e: Exception) {
            // Vibrator killed mid-call / permission revoked — ignore.
        }
    }

    /** Immediately stops any in-flight vibration. */
    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            // Ignore — cancel must never crash.
        }
    }
}
