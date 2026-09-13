package com.aquascope.smriti.brain.heartrate

import kotlin.math.abs
import kotlin.math.sqrt

object SignalQualityAnalyzer {

    fun classify(
        confidence: Float,
        validBeats: Int,
        coverageScore: Float,
        fingerCoveredRatio: Float,
        ibiStdSec: Double,
        snrApprox: Double
    ): SignalQuality {
        if (!fingerCoveredRatio.isFinite() || fingerCoveredRatio < 0.55f) return SignalQuality.INVALID
        if (validBeats < 3 || coverageScore < 0.4f) return SignalQuality.INVALID
        if (confidence < 0.35f || snrApprox < 0.8) return SignalQuality.INVALID

        return when {
            confidence >= 0.72f && validBeats >= 8 && ibiStdSec < 0.12 && snrApprox >= 2.0 ->
                SignalQuality.HIGH
            confidence >= 0.52f && validBeats >= 5 && ibiStdSec < 0.22 ->
                SignalQuality.MEDIUM
            confidence >= 0.35f && validBeats >= 3 ->
                SignalQuality.LOW
            else -> SignalQuality.INVALID
        }
    }

    /** Rough SNR: peak amplitude vs residual MAD of filtered signal. */
    fun approximateSnr(filtered: DoubleArray, peaks: List<PeakDetector.Peak>): Double {
        if (filtered.isEmpty() || peaks.isEmpty()) return 0.0
        val peakAmps = peaks.map { abs(it.value) }
        val signal = peakAmps.average()
        val mean = filtered.average()
        val absDev = filtered.map { abs(it - mean) }.sorted()
        val mad = absDev[absDev.size / 2].coerceAtLeast(1e-6)
        return signal / (mad * 1.4826)
    }

    fun signalVariance(values: FloatArray): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        return sqrt(values.map { (it - mean) * (it - mean) }.average())
    }
}
