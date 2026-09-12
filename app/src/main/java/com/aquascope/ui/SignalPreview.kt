package com.aquascope.ui

import com.aquascope.dsp.FFT
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Display-only downsampling of real AquaScope captures.
 * Never used for anomaly scoring.
 */
object SignalPreview {

    fun waveform(samples: DoubleArray, bins: Int = 96): FloatArray {
        if (samples.isEmpty() || bins <= 0) return FloatArray(0)
        val out = FloatArray(bins)
        val window = max(1, samples.size / bins)
        var peak = 1e-6
        for (i in 0 until bins) {
            val start = i * window
            val end = min(samples.size, start + window)
            var p = 0.0
            for (j in start until end) {
                val a = kotlin.math.abs(samples[j])
                if (a > p) p = a
            }
            out[i] = if (i % 2 == 0) p.toFloat() else -p.toFloat()
            if (p > peak) peak = p
        }
        val inv = (1.0 / peak).toFloat()
        for (i in out.indices) out[i] *= inv
        return out
    }

    fun spectrum(samples: DoubleArray, bands: Int = 40): FloatArray {
        if (samples.isEmpty() || bands <= 0) return FloatArray(0)
        val take = min(samples.size, 2048)
        val slice = DoubleArray(take) { samples[it] }
        val n = FFT.nextPowerOf2(take)
        val re = FFT.zeroPad(slice, n)
        val im = DoubleArray(n)
        FFT.fft(re, im)
        val mag = FFT.magnitude(re, im)
        val half = n / 2
        val out = FloatArray(bands)
        val startBin = max(1, half / 40)
        for (b in 0 until bands) {
            val t0 = b / bands.toDouble()
            val t1 = (b + 1) / bands.toDouble()
            val i0 = (startBin + t0 * (half - startBin)).toInt().coerceIn(0, half - 1)
            val i1 = (startBin + t1 * (half - startBin)).toInt().coerceIn(i0 + 1, half)
            var sum = 0.0
            for (i in i0 until i1) sum += mag[i]
            out[b] = ln(1.0 + sum / (i1 - i0)).toFloat()
        }
        var maxV = 1e-6f
        for (v in out) if (v > maxV) maxV = v
        for (i in out.indices) out[i] /= maxV
        return out
    }

    fun rms(samples: ShortArray, offset: Int, length: Int): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        val end = min(samples.size, offset + length)
        for (i in offset until end) {
            val s = samples[i] / 32768.0
            sum += s * s
        }
        return sqrt(sum / (end - offset).coerceAtLeast(1)).toFloat()
    }
}
