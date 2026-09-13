package com.aquascope.smriti.brain.heartrate

/**
 * Peak detector for filtered PPG. Requires refractory period consistent with max BPM.
 */
object PeakDetector {

    data class Peak(val index: Int, val timestampNs: Long, val value: Double)

    fun detect(
        values: DoubleArray,
        timestampsNs: LongArray,
        minIntervalSec: Double = 0.30,
        prominenceFraction: Double = 0.25
    ): List<Peak> {
        if (values.size < 5 || values.size != timestampsNs.size) return emptyList()

        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (v in values) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val amp = (max - min).coerceAtLeast(1e-6)
        val threshold = min + amp * prominenceFraction

        val peaks = ArrayList<Peak>()
        var lastPeakNs = Long.MIN_VALUE / 4
        val minIntervalNs = (minIntervalSec * 1_000_000_000.0).toLong()

        for (i in 1 until values.lastIndex) {
            val v = values[i]
            if (v < threshold) continue
            if (v >= values[i - 1] && v > values[i + 1]) {
                val t = timestampsNs[i]
                if (t - lastPeakNs >= minIntervalNs) {
                    peaks += Peak(i, t, v)
                    lastPeakNs = t
                } else if (peaks.isNotEmpty() && v > peaks.last().value) {
                    peaks[peaks.lastIndex] = Peak(i, t, v)
                    lastPeakNs = t
                }
            }
        }
        return peaks
    }
}
