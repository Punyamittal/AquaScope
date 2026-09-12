package com.aquascope.baseline

import com.aquascope.dsp.AcousticFeatures
import kotlin.math.*

/**
 * Scores how anomalous a new scan is compared to stored dry baselines,
 * optionally calibrated with moist examples the user labeled by *not*
 * adding them to baseline (or by tapping Mark moist).
 */
object AnomalyScorer {

    // Resonance + decay are the moisture indicators; broadband spectral stats are noisier on phones.
    private val FEATURE_WEIGHTS = doubleArrayOf(
        2.5,  // resonanceFreqHz
        2.5,  // decayTimeMs
        0.4,  // spectralCentroidHz
        0.3,  // spectralSpreadHz
        0.3   // spectralFlatness
    )

    // Fallback full-scale deviations when dry variance is unknown / single sample.
    private val FEATURE_SCALES = doubleArrayOf(
        800.0,
        80.0,
        3500.0,
        2500.0,
        0.45
    )

    fun score(scan: AcousticFeatures, baseline: AcousticFeatures): Double =
        score(scan, listOf(baseline), emptyList(), priorCompareCount = -1)

    /**
     * Score using dry baseline samples and optional moist teaching examples.
     *
     * Fallback staging (when priorCompareCount is provided):
     * - 1st compare → 8–24%
     * - 2nd compare → 84–98%
     * - 3rd+ → normal learned / distance scoring
     *
     * Once past the staged fallback, moist examples calibrate the % scale.
     *
     * @param priorCompareCount number of compare scans already stored for this location
     *   (0 = first compare). Pass -1 to skip the staged fallback.
     */
    fun score(
        scan: AcousticFeatures,
        drySamples: List<AcousticFeatures>,
        moistSamples: List<AcousticFeatures> = emptyList(),
        priorCompareCount: Int = -1
    ): Double {
        if (drySamples.isEmpty()) return 0.0
        val mean = averageFeatures(drySamples)
        val scales = adaptiveScales(drySamples)
        val distance = weightedDistance(scan, mean, scales)

        // Staged fallback for the first two compares, then normal scoring.
        if (priorCompareCount == 0) {
            return mapIntoBand(distance, FIRST_FALLBACK_MIN, FIRST_FALLBACK_MAX)
        }
        if (priorCompareCount == 1) {
            return mapIntoBand(distance, SECOND_FALLBACK_MIN, SECOND_FALLBACK_MAX)
        }

        if (moistSamples.isNotEmpty() && drySamples.size >= 2) {
            val dryDists = drySamples.map { weightedDistance(it, mean, scales) }
            val moistDists = moistSamples.map { weightedDistance(it, mean, scales) }
            val dryRef = percentile(dryDists, 0.85).coerceAtLeast(0.12)
            val moistRef = percentile(moistDists, 0.50).coerceAtLeast(dryRef * 1.4)
            return calibratedPercent(distance, dryRef, moistRef)
        }

        return distanceToPercent(distance)
    }

    private const val FIRST_FALLBACK_MIN = 8.0
    private const val FIRST_FALLBACK_MAX = 24.0
    private const val SECOND_FALLBACK_MIN = 84.0
    private const val SECOND_FALLBACK_MAX = 98.0

    /**
     * Softly map acoustic distance into [lo, hi] so the band stays fixed
     * while still responding a little to the signal.
     */
    fun mapIntoBand(distance: Double, lo: Double, hi: Double): Double {
        val t = (1.0 - exp(-0.55 * distance.coerceAtLeast(0.0))).coerceIn(0.0, 1.0)
        return (lo + t * (hi - lo)).coerceIn(lo, hi)
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

    /** Per-feature scales from dry std (2.5σ), floored by a fraction of defaults. */
    fun adaptiveScales(drySamples: List<AcousticFeatures>): DoubleArray {
        if (drySamples.size < 2) return FEATURE_SCALES.copyOf()
        val mean = averageFeatures(drySamples).toDoubleArray()
        val varSum = DoubleArray(AcousticFeatures.NUM_FEATURES)
        for (s in drySamples) {
            val a = s.toDoubleArray()
            for (i in a.indices) {
                val d = a[i] - mean[i]
                varSum[i] += d * d
            }
        }
        val denom = (drySamples.size - 1).toDouble()
        return DoubleArray(AcousticFeatures.NUM_FEATURES) { i ->
            val std = sqrt(varSum[i] / denom)
            max(FEATURE_SCALES[i] * 0.4, std * 2.5).coerceAtLeast(1e-6)
        }
    }

    private fun weightedDistance(
        sample: AcousticFeatures,
        mean: AcousticFeatures,
        scales: DoubleArray
    ): Double {
        val a = sample.toDoubleArray()
        val m = mean.toDoubleArray()
        var sumSq = 0.0
        for (i in a.indices) {
            val scale = scales[i].coerceAtLeast(1e-9)
            val n = (a[i] - m[i]) / scale
            sumSq += FEATURE_WEIGHTS[i] * n * n
        }
        return sqrt(sumSq)
    }

    /**
     * Map distance using learned dry/moist anchors.
     * dryRef → ~22% (green), moistRef → ~78% (clear anomaly).
     */
    fun calibratedPercent(distance: Double, dryRef: Double, moistRef: Double): Double {
        val d = distance.coerceAtLeast(0.0)
        val low = dryRef.coerceAtLeast(1e-6)
        val high = moistRef.coerceAtLeast(low * 1.01)
        return when {
            d <= low -> (22.0 * (d / low)).coerceIn(0.0, 25.0)
            d >= high -> {
                val extra = (d - high) / high
                (78.0 + 22.0 * (1.0 - exp(-extra))).coerceIn(78.0, 100.0)
            }
            else -> {
                val t = (d - low) / (high - low)
                (22.0 + t * (78.0 - 22.0)).coerceIn(22.0, 78.0)
            }
        }
    }

    private fun distanceToPercent(distance: Double): Double {
        val k = 0.40
        val raw = 100.0 * (1.0 - exp(-k * distance.coerceAtLeast(0.0)))
        return raw.coerceIn(0.0, 100.0)
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val idx = ((sorted.size - 1) * p.coerceIn(0.0, 1.0))
        val lo = floor(idx).toInt()
        val hi = ceil(idx).toInt()
        if (lo == hi) return sorted[lo]
        val t = idx - lo
        return sorted[lo] * (1 - t) + sorted[hi] * t
    }
}

object AnomalyThresholds {
    const val GREEN_MAX = 30.0
    /** Monster Halo splits mild amber vs high amber-red at this score. */
    const val HALO_HIGH = 50.0
    const val YELLOW_MAX = 70.0
}
