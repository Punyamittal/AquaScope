package com.aquascope.dsp

import kotlin.math.*

/**
 * Extracts acoustic features from the impulse response / frequency response
 * that are sensitive to moisture-related changes in wall/pipe material properties.
 */
data class AcousticFeatures(
    val resonanceFreqHz: Double,
    val decayTimeMs: Double,
    val spectralCentroidHz: Double,
    val spectralSpreadHz: Double,
    val spectralFlatness: Double
) {
    fun toDoubleArray() = doubleArrayOf(
        resonanceFreqHz, decayTimeMs, spectralCentroidHz, spectralSpreadHz, spectralFlatness
    )

    companion object {
        fun fromDoubleArray(arr: DoubleArray) = AcousticFeatures(
            resonanceFreqHz = arr[0],
            decayTimeMs = arr[1],
            spectralCentroidHz = arr[2],
            spectralSpreadHz = arr[3],
            spectralFlatness = arr[4]
        )

        const val NUM_FEATURES = 5
    }
}

object FeatureExtractor {

    /**
     * Full feature extraction pipeline.
     * @param impulseResponse time-domain impulse response from deconvolution
     * @param sampleRate sample rate in Hz
     */
    fun extract(impulseResponse: DoubleArray, sampleRate: Int): AcousticFeatures {
        val n = FFT.nextPowerOf2(impulseResponse.size)
        val re = FFT.zeroPad(impulseResponse, n)
        val im = DoubleArray(n)
        FFT.fft(re, im)

        val mag = FFT.magnitude(re, im)
        val halfN = n / 2

        val resonance = findResonancePeak(mag, halfN, sampleRate)
        val decay = estimateDecayTime(impulseResponse, sampleRate)
        val centroid = spectralCentroid(mag, halfN, sampleRate)
        val spread = spectralSpread(mag, halfN, sampleRate, centroid)
        val flatness = spectralFlatness(mag, halfN)

        return AcousticFeatures(resonance, decay, centroid, spread, flatness)
    }

    /** Find the frequency of the dominant peak in the magnitude spectrum. */
    fun findResonancePeak(mag: DoubleArray, halfN: Int, sampleRate: Int): Double {
        // Skip DC and very low bins (below ~50 Hz) which are often noise
        val minBin = (50.0 * halfN * 2 / sampleRate).toInt().coerceAtLeast(1)
        var peakBin = minBin
        var peakVal = mag[minBin]
        for (i in minBin + 1 until halfN) {
            if (mag[i] > peakVal) {
                peakVal = mag[i]
                peakBin = i
            }
        }
        return peakBin.toDouble() * sampleRate / (halfN * 2)
    }

    /**
     * Estimate decay time using the envelope of the impulse response.
     * Uses a simple RMS-windowed approach: find when the envelope drops to -60dB (T60-style)
     * or -20dB and extrapolate.
     */
    fun estimateDecayTime(impulseResponse: DoubleArray, sampleRate: Int): Double {
        val envelope = hilbertEnvelope(impulseResponse)
        if (envelope.isEmpty()) return 0.0

        val peakIdx = envelope.indices.maxByOrNull { envelope[it] } ?: 0
        val peakVal = envelope[peakIdx]
        if (peakVal <= 0.0) return 0.0

        // Find -20dB point and extrapolate to -60dB (T60 = 3 * T20).
        // If the envelope never crosses -20dB, use the last sample as T20 lower bound
        // so decay does not collapse to 0 ms on long / truncated IRs.
        val threshold20dB = peakVal * 0.1 // -20dB
        var t20Idx = -1
        for (i in peakIdx until envelope.size) {
            if (envelope[i] < threshold20dB) {
                t20Idx = i
                break
            }
        }
        if (t20Idx < 0) {
            t20Idx = envelope.lastIndex.coerceAtLeast(peakIdx)
        }

        val t20Samples = (t20Idx - peakIdx).toDouble().coerceAtLeast(1.0)
        val t60Samples = t20Samples * 3.0
        return t60Samples / sampleRate * 1000.0 // ms
    }

    /** Spectral centroid: weighted mean frequency. */
    fun spectralCentroid(mag: DoubleArray, halfN: Int, sampleRate: Int): Double {
        var weightedSum = 0.0
        var totalWeight = 0.0
        for (i in 1 until halfN) {
            val freq = i.toDouble() * sampleRate / (halfN * 2)
            weightedSum += freq * mag[i]
            totalWeight += mag[i]
        }
        return if (totalWeight > 0) weightedSum / totalWeight else 0.0
    }

    /** Spectral spread (standard deviation around centroid). */
    fun spectralSpread(mag: DoubleArray, halfN: Int, sampleRate: Int, centroid: Double): Double {
        var weightedSqSum = 0.0
        var totalWeight = 0.0
        for (i in 1 until halfN) {
            val freq = i.toDouble() * sampleRate / (halfN * 2)
            val diff = freq - centroid
            weightedSqSum += diff * diff * mag[i]
            totalWeight += mag[i]
        }
        return if (totalWeight > 0) sqrt(weightedSqSum / totalWeight) else 0.0
    }

    /** Spectral flatness: geometric mean / arithmetic mean of magnitudes. Measures tonality. */
    fun spectralFlatness(mag: DoubleArray, halfN: Int): Double {
        var logSum = 0.0
        var linSum = 0.0
        var count = 0
        for (i in 1 until halfN) {
            if (mag[i] > 1e-12) {
                logSum += ln(mag[i])
                linSum += mag[i]
                count++
            }
        }
        if (count == 0) return 0.0
        val geometricMean = exp(logSum / count)
        val arithmeticMean = linSum / count
        return if (arithmeticMean > 0) geometricMean / arithmeticMean else 0.0
    }

    /**
     * Approximate Hilbert envelope using the analytic signal approach:
     * envelope = |signal + j * hilbert(signal)|
     * Implemented via FFT: zero out negative frequencies, IFFT, take magnitude.
     */
    fun hilbertEnvelope(signal: DoubleArray): DoubleArray {
        if (signal.isEmpty()) return doubleArrayOf()
        val n = FFT.nextPowerOf2(signal.size)
        val re = FFT.zeroPad(signal, n)
        val im = DoubleArray(n)
        FFT.fft(re, im)

        // Zero negative frequencies, double positive (analytic signal construction)
        // Bin 0 and N/2 stay unchanged
        for (i in 1 until n / 2) {
            re[i] *= 2.0
            im[i] *= 2.0
        }
        for (i in n / 2 + 1 until n) {
            re[i] = 0.0
            im[i] = 0.0
        }

        FFT.fft(re, im, inverse = true)
        return DoubleArray(signal.size) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }
}
