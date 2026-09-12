package com.smriti.core.guardian

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * FallDetector — pure-Kotlin accelerometer fall state machine (SPEC §2.4).
 *
 * No Android imports; feed samples from any source. Unit-testable on the JVM.
 *
 * Pipeline (all thresholds tunable constants below):
 *  1. FREE_FALL  : |a| < [FREE_FALL_MAX_G] (4.0 m/s^2) sustained for >= [FREE_FALL_MIN_MS] (200 ms).
 *  2. IMPACT     : |a| > [IMPACT_MIN_G] (28.0 m/s^2) observed within [IMPACT_WINDOW_MS] (1000 ms)
 *                  after free-fall was confirmed.
 *  3. STILLNESS  : ||a| - 9.81| < [STILLNESS_TOL] (1.5 m/s^2) sustained for [STILLNESS_MIN_MS]
 *                  (2000 ms) after impact, and within [STILLNESS_WINDOW_MS] (10 s) of the impact.
 * Returns `true` exactly once when the full sequence completes, then resets to IDLE.
 *
 * Robustness:
 *  - Timestamp going backwards (sensor clock reset / source restart) resets the machine to IDLE.
 *  - Every stage has a timeout: free-fall must be *sustained* (any excursion above the
 *    threshold restarts the 200 ms accumulation); impact must occur within 1 s of confirmed
 *    free-fall; stillness must complete within 10 s of impact.
 *
 * @param ts must be a MONOTONIC millisecond clock (e.g. SensorEvent.timestamp / 1_000_000),
 *           consistent across calls — never wall-clock mixed with sensor clock.
 */
class FallDetector {

    companion object {
        /** Below this magnitude (m/s^2) the device is considered in free-fall. */
        const val FREE_FALL_MAX_G = 4.0f
        /** Free-fall must persist at least this long to count. */
        const val FREE_FALL_MIN_MS = 200L
        /** Impact spike threshold (m/s^2). */
        const val IMPACT_MIN_G = 28.0f
        /** Impact must happen within this window after free-fall is confirmed. */
        const val IMPACT_WINDOW_MS = 1000L
        /** Gravity reference for stillness (m/s^2). */
        const val GRAVITY = 9.81f
        /** Tolerance around gravity for "device is lying still". */
        const val STILLNESS_TOL = 1.5f
        /** Stillness must persist this long after an impact to confirm a fall. */
        const val STILLNESS_MIN_MS = 2000L
        /** Give up waiting for stillness this long after impact (person got up / false impact). */
        const val STILLNESS_WINDOW_MS = 10_000L
    }

    private enum class State { IDLE, FREE_FALL, AWAIT_IMPACT, AWAIT_STILLNESS }

    private var state = State.IDLE
    /** Last accepted timestamp; used to detect clock resets (ts going backwards). */
    private var lastTs = Long.MIN_VALUE
    /** When the current free-fall excursion started. */
    private var freeFallStartTs = 0L
    /** Timestamp anchoring the impact window / stillness deadline. */
    private var impactAnchorTs = 0L
    /** When the current stillness stretch started; -1 = not currently still. */
    private var stillStartTs = -1L

    /**
     * Feed one accelerometer sample. Returns true once when the full
     * free-fall -> impact -> stillness sequence completes (machine then resets).
     */
    fun onAccel(x: Float, y: Float, z: Float, ts: Long): Boolean {
        // Clock reset / source restart: start over rather than compare across clocks.
        if (ts < lastTs) reset()
        lastTs = ts

        val a = sqrt(x * x + y * y + z * z)

        when (state) {
            State.IDLE -> {
                if (a < FREE_FALL_MAX_G) {
                    state = State.FREE_FALL
                    freeFallStartTs = ts
                }
            }

            State.FREE_FALL -> {
                if (a >= FREE_FALL_MAX_G) {
                    // Free-fall broken before 200 ms: not a fall (toss, pickup, pocket drop).
                    state = State.IDLE
                } else if (ts - freeFallStartTs >= FREE_FALL_MIN_MS) {
                    // Sustained free-fall confirmed; arm the 1 s impact window.
                    state = State.AWAIT_IMPACT
                    impactAnchorTs = ts // window anchor = end of confirmed free-fall
                }
            }

            State.AWAIT_IMPACT -> {
                if (a > IMPACT_MIN_G) {
                    state = State.AWAIT_STILLNESS
                    impactAnchorTs = ts
                    stillStartTs = -1L // not yet still
                } else if (ts - impactAnchorTs >= IMPACT_WINDOW_MS) {
                    // Timeout: free-fall with no impact (device caught, soft landing).
                    reset()
                }
            }

            State.AWAIT_STILLNESS -> {
                val still = abs(a - GRAVITY) < STILLNESS_TOL
                if (still) {
                    if (stillStartTs < 0L) stillStartTs = ts
                    else if (ts - stillStartTs >= STILLNESS_MIN_MS) {
                        // Full sequence confirmed. Fire once, then reset.
                        reset()
                        return true
                    }
                } else {
                    stillStartTs = -1L // movement restarts the 2 s stillness accumulation
                    if (ts - impactAnchorTs >= STILLNESS_WINDOW_MS) {
                        // Timeout: person active again after impact -> probably fine.
                        reset()
                    }
                }
            }
        }
        return false
    }

    /** Back to IDLE, clearing all stage anchors. */
    fun reset() {
        state = State.IDLE
        freeFallStartTs = 0L
        impactAnchorTs = 0L
        stillStartTs = -1L
    }
}
