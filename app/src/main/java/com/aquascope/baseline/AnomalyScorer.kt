package com.aquascope.baseline

import com.aquascope.dsp.AcousticFeatures
import kotlin.math.*

/**
 * Scores how anomalous a new scan is compared to a stored baseline.
 */
object AnomalyScorer {

    // TODO: Calibrate these weights with real dry/wet measurements.
    // Higher weight = that feature contributes more to the anomaly score.
    // Initial guess: resonance shift and decay change are the strongest moisture indicators.
    private val FEATURE_WEIGHTS = doubleArrayOf(
        2.0,  // resonanceFreqHz — wet material lowers resonance
        2.0,  // decayTimeMs — wet material increases damping (shorter decay)
        1.0,  // spectralCentroidHz
        0.5,  // spectralSpreadHz
        0.5   // spectralFlatness
    )

    // TODO: Calibrate normalization scales from real measurement ranges
    // These represent expected "full-scale" deviations for each feature
    private val FEATURE_SCALES = doubleArrayOf(
        500.0,  // Hz shift in resonance
        50.0,   // ms change in decay
        1000.0, // Hz shift in centroid
        500.0,  // Hz change in spread
        0.3     // change in flatness (0-1 range)
    )

    /**
     * Compute anomaly score (0-100) between a new scan and baseline.
     * Uses weighted normalized Euclidean distance.
     */
    fun score(scan: AcousticFeatures, baseline: AcousticFeatures): Double {
        val scanArr = scan.toDoubleArray()
        val baseArr = baseline.toDoubleArray()

        var sumSq = 0.0
        for (i in scanArr.indices) {
            val normalized = (scanArr[i] - baseArr[i]) / FEATURE_SCALES[i]
            sumSq += FEATURE_WEIGHTS[i] * normalized * normalized
        }

        val distance = sqrt(sumSq)
        return distanceToPercent(distance)
    }

    /**
     * Score against a baseline that has multiple calibration samples (averaged).
     */
    fun score(scan: AcousticFeatures, baselineSamples: List<AcousticFeatures>): Double {
        if (baselineSamples.isEmpty()) return 0.0
        val avg = averageFeatures(baselineSamples)
        return score(scan, avg)
    }

    fun averageFeatures(samples: List<AcousticFeatures>): AcousticFeatures {
        if (samples.isEmpty()) throw IllegalArgumentException("Empty sample list")
        val sums = DoubleArray(AcousticFeatures.NUM_FEATURES)
        for (s in samples) {
            val arr = s.toDoubleArray()
            for (i in sums.indices) sums[i] += arr[i]
        }
        for (i in sums.indices) sums[i] = sums[i] / samples.size
        return AcousticFeatures.fromDoubleArray(sums)
    }

    /**
     * Map raw distance to 0-100%.
     * Uses 100*(1-exp(-k*d)) so identical scans score ~0% (sigmoid had a ~5% floor).
     * TODO: Tune k against real calibration data.
     */
    private fun distanceToPercent(distance: Double): Double {
        val k = 0.8 // TODO: steepness — tune with real data
        val raw = 100.0 * (1.0 - exp(-k * distance.coerceAtLeast(0.0)))
        return raw.coerceIn(0.0, 100.0)
    }
}

// TODO: Threshold constants for UI color coding — tune with real data
object AnomalyThresholds {
    const val GREEN_MAX = 30.0
    const val YELLOW_MAX = 70.0
    // Above YELLOW_MAX = red
}
