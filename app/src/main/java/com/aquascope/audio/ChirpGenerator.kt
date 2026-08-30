package com.aquascope.audio

import kotlin.math.*

/**
 * Generates a logarithmic sine sweep (chirp) from startFreq to endFreq.
 * The instantaneous frequency increases exponentially, giving equal energy per octave.
 */
object ChirpGenerator {

    // TODO: Tune these defaults against real hardware measurements
    const val DEFAULT_START_FREQ = 20.0
    const val DEFAULT_END_FREQ = 15000.0
    const val DEFAULT_DURATION_SEC = 1.5
    const val DEFAULT_SAMPLE_RATE = 44100

    /**
     * Generate a logarithmic chirp as a DoubleArray of PCM samples in [-1, 1].
     *
     * Logarithmic sweep: φ(t) = 2π * f1 * T / ln(f2/f1) * (exp(t/T * ln(f2/f1)) - 1)
     */
    fun generate(
        startFreq: Double = DEFAULT_START_FREQ,
        endFreq: Double = DEFAULT_END_FREQ,
        durationSec: Double = DEFAULT_DURATION_SEC,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): DoubleArray {
        val numSamples = (durationSec * sampleRate).toInt()
        val samples = DoubleArray(numSamples)

        val logRatio = ln(endFreq / startFreq)
        val T = durationSec

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val phase = 2.0 * PI * startFreq * T / logRatio * (exp(t / T * logRatio) - 1.0)
            // Fade-in/out to reduce transient clicks (raised-cosine taper)
            val envelope = taper(i, numSamples)
            samples[i] = sin(phase) * envelope
        }
        return samples
    }

    /** Convert double samples to 16-bit PCM short array for AudioTrack. */
    fun toShortArray(samples: DoubleArray): ShortArray {
        return ShortArray(samples.size) { i ->
            (samples[i].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Convert double samples to float array. */
    fun toFloatArray(samples: DoubleArray): FloatArray {
        return FloatArray(samples.size) { samples[it].toFloat() }
    }

    /** Raised-cosine taper for the first/last ~5% of the signal. */
    private fun taper(index: Int, total: Int): Double {
        val taperLen = (total * 0.05).toInt().coerceAtLeast(1)
        return when {
            index < taperLen -> 0.5 * (1.0 - cos(PI * index / taperLen))
            index >= total - taperLen -> 0.5 * (1.0 - cos(PI * (total - 1 - index) / taperLen))
            else -> 1.0
        }
    }
}
