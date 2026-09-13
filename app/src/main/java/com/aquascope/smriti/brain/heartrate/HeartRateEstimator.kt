package com.aquascope.smriti.brain.heartrate

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Estimates BPM from inter-beat intervals with optional FFT corroboration.
 */
object HeartRateEstimator {

    data class Estimate(
        val bpm: Double,
        val confidence: Float,
        val validBeats: Int,
        val ibiStdSec: Double
    )

    fun estimate(
        peaks: List<PeakDetector.Peak>,
        filtered: DoubleArray,
        sampleRateHz: Double
    ): Estimate? {
        if (peaks.size < 3) return null

        val intervals = ArrayList<Double>(peaks.size - 1)
        for (i in 1 until peaks.size) {
            val dt = (peaks[i].timestampNs - peaks[i - 1].timestampNs) / 1e9
            if (dt in 0.30..1.50) intervals += dt
        }
        if (intervals.size < 2) return null

        val medianIbi = median(intervals)
        val bpmFromIbi = 60.0 / medianIbi
        if (bpmFromIbi !in 40.0..200.0) return null

        val mean = intervals.average()
        val variance = intervals.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance)

        val fftBpm = estimateFftBpm(filtered, sampleRateHz)
        val blended = if (fftBpm != null && abs(fftBpm - bpmFromIbi) < 18.0) {
            0.7 * bpmFromIbi + 0.3 * fftBpm
        } else {
            bpmFromIbi
        }

        val consistency = (1.0 - (std / mean).coerceIn(0.0, 1.0)).toFloat()
        val beatScore = (intervals.size / 8.0).coerceIn(0.0, 1.0).toFloat()
        val fftAgree = if (fftBpm != null && abs(fftBpm - bpmFromIbi) < 12.0) 1f else 0.55f
        val confidence = (0.45f * consistency + 0.35f * beatScore + 0.20f * fftAgree)
            .coerceIn(0f, 1f)

        return Estimate(
            bpm = blended.coerceIn(40.0, 200.0),
            confidence = confidence,
            validBeats = intervals.size,
            ibiStdSec = std
        )
    }

    private fun estimateFftBpm(signal: DoubleArray, sampleRateHz: Double): Double? {
        val n = signal.size
        if (n < 64 || sampleRateHz < 8.0) return null

        // Remove mean
        val mean = signal.average()
        val centered = DoubleArray(n) { signal[it] - mean }

        val minBin = ((0.67 / sampleRateHz) * n).toInt().coerceAtLeast(1)
        val maxBin = ((3.33 / sampleRateHz) * n).toInt().coerceAtMost(n / 2 - 1)
        if (maxBin <= minBin) return null

        var bestPower = 0.0
        var bestBin = minBin
        for (k in minBin..maxBin) {
            var re = 0.0
            var im = 0.0
            val w = 2.0 * PI * k / n
            for (i in 0 until n) {
                re += centered[i] * cos(w * i)
                im -= centered[i] * sin(w * i)
            }
            val p = re * re + im * im
            if (p > bestPower) {
                bestPower = p
                bestBin = k
            }
        }
        val hz = bestBin * sampleRateHz / n
        val bpm = hz * 60.0
        return if (bpm in 40.0..200.0) bpm else null
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }
}
