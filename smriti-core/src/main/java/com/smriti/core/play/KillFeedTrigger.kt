package com.smriti.core.play

import kotlin.math.abs

/**
 * Minimal normalized rectangle (all values in 0f..1f, relative to frame width/height).
 *
 * Pure-Kotlin stand-in for android.graphics.RectF so this trigger stays free of Android
 * imports and unit-testable on the JVM. Callers holding an android.graphics.RectF convert
 * via `RectF(a.left, a.top, a.right, a.bottom)`.
 */
data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Kill-feed pixel-change detector for SMRITI Play (SPEC §2.2).
 *
 * Fed ~10 Hz luma (Y-plane) frames covering a normalized screen [regionNormalized]
 * (default: the top-right strip where FPS kill-feeds appear). Detection:
 *
 *  1. The region is sampled on a strided grid ([stride] = 4 px in both axes).
 *  2. A per-sample EMA baseline ([alpha] = 0.2) tracks the slowly-changing background.
 *  3. A frame fires when the mean absolute difference of the samples vs the baseline
 *     exceeds [diffThreshold] (default 18/255 luma), OR when the region mean brightness
 *     jumps by more than [flashJump] (default 40/255) within [flashWindowMs]
 *     (default 150 ms) — a muzzle-flash / kill-confirm flash spike.
 *  4. After every positive, a [cooldownMs] (default 3 s) refractory period suppresses
 *     re-triggers; the baseline is also frozen while an event fires so a persistent
 *     kill-feed banner cannot be adapted away, and adapted back in once frames are quiet.
 *
 * Pure math, no Android imports, fully unit-testable. [ts] is any monotonic millisecond
 * clock (caller uses SystemClock.elapsedRealtime()).
 */
class KillFeedTrigger(
    private val regionNormalized: RectF,
    private val diffThreshold: Float = 18f,
    private val flashJump: Float = 40f,
    private val flashWindowMs: Long = 150L,
    private val cooldownMs: Long = 3_000L,
    private val alpha: Float = 0.2f,
    private val stride: Int = 4
) {
    init {
        require(stride >= 1) { "stride must be >= 1" }
        require(alpha in 0f..1f) { "alpha must be in [0,1]" }
    }

    // Per-sample EMA baseline over the strided grid; null until first frame (or geometry change).
    private var baseline: FloatArray? = null
    private var gridW = 0
    private var gridH = 0
    private var prevMean = -1f
    private var prevTs = -1L
    private var lastTriggerTs = Long.MIN_VALUE

    /**
     * @param luma full-frame 8-bit luma plane, tightly packed row-major, size >= w*h.
     * @param ts monotonic timestamp in milliseconds.
     * @return true exactly once per detected kill-feed event (subject to cooldown).
     */
    @Synchronized
    fun onLumaFrame(luma: ByteArray, w: Int, h: Int, ts: Long): Boolean {
        if (w <= 0 || h <= 0 || luma.size < w * h) return false

        // Clamp the normalized region into pixel bounds (at least 1x1).
        val x0 = (regionNormalized.left.coerceIn(0f, 1f) * w).toInt().coerceIn(0, w - 1)
        val y0 = (regionNormalized.top.coerceIn(0f, 1f) * h).toInt().coerceIn(0, h - 1)
        val x1 = (regionNormalized.right.coerceIn(0f, 1f) * w).toInt().coerceIn(x0 + 1, w)
        val y1 = (regionNormalized.bottom.coerceIn(0f, 1f) * h).toInt().coerceIn(y0 + 1, h)

        val gw = (x1 - x0 + stride - 1) / stride
        val gh = (y1 - y0 + stride - 1) / stride
        val n = gw * gh

        // Strided sub-sample of the region.
        val samples = IntArray(n)
        var sum = 0L
        var i = 0
        var y = y0
        while (y < y1) {
            val row = y * w
            var x = x0
            while (x < x1) {
                val v = luma[row + x].toInt() and 0xFF
                samples[i++] = v
                sum += v
                x += stride
            }
            y += stride
        }
        val mean = sum.toFloat() / n

        // Flash spike: sharp mean brightness rise between two consecutive frames.
        val flash = prevMean >= 0f && (ts - prevTs) in 1L until flashWindowMs &&
            (mean - prevMean) > flashJump

        var triggered = false
        val base = baseline
        if (base == null || base.size != n || gridW != gw || gridH != gh) {
            // First frame or geometry change: adopt current frame as the baseline.
            baseline = FloatArray(n) { samples[it].toFloat() }
            gridW = gw
            gridH = gh
        } else {
            var diffSum = 0f
            for (k in 0 until n) diffSum += abs(samples[k] - base[k])
            val mad = diffSum / n
            if (ts - lastTriggerTs >= cooldownMs && (mad > diffThreshold || flash)) {
                triggered = true
                lastTriggerTs = ts
                // Baseline deliberately NOT updated on a firing frame.
            } else {
                for (k in 0 until n) base[k] = base[k] + alpha * (samples[k] - base[k])
            }
        }

        prevMean = mean
        prevTs = ts
        return triggered
    }

    /** Resets baseline and cooldown (used when recording restarts). */
    @Synchronized
    fun reset() {
        baseline = null
        prevMean = -1f
        prevTs = -1L
        lastTriggerTs = Long.MIN_VALUE
    }
}
