package com.aquascope.dsp

import com.aquascope.audio.ChirpGenerator
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class DeconvolutionTest {

    /**
     * Simulate a surface with a known impulse response (single resonance at ~1000 Hz),
     * convolve with the chirp, then verify deconvolution recovers the resonance.
     */
    @Test
    fun `deconvolution recovers resonance from simulated surface response`() {
        val sampleRate = 44100
        val chirp = ChirpGenerator.generate(durationSec = 0.5, sampleRate = sampleRate)

        // Simulate a damped resonator impulse response at ~1000 Hz
        val irLength = 2048
        val resonanceFreq = 1000.0
        val decayRate = 200.0 // exponential decay constant
        val simulatedIR = DoubleArray(irLength) { i ->
            val t = i.toDouble() / sampleRate
            exp(-decayRate * t) * sin(2.0 * PI * resonanceFreq * t)
        }

        // Convolve chirp with IR to simulate what the mic would record
        val recorded = convolve(chirp, simulatedIR)

        // Deconvolve
        val recoveredIR = Deconvolution.deconvolve(recorded, chirp)

        // Extract features and check the resonance peak is near 1000 Hz
        val features = FeatureExtractor.extract(recoveredIR, sampleRate)
        val tolerance = 100.0 // Hz — allow some spectral leakage
        assertEquals(
            "Recovered resonance should be near $resonanceFreq Hz",
            resonanceFreq, features.resonanceFreqHz, tolerance
        )
    }

    @Test
    fun `matched filter peaks at correct lag for delayed signal`() {
        val n = 1024
        val signal = DoubleArray(n) { if (it in 100..110) 1.0 else 0.0 }
        val reference = DoubleArray(n) { if (it in 0..10) 1.0 else 0.0 }

        val corr = Deconvolution.matchedFilter(signal, reference)
        val peakIdx = corr.indices.maxByOrNull { corr[it] } ?: -1
        assertEquals("Peak correlation should be at lag ~100", 100, peakIdx, 2)
    }

    private fun assertEquals(msg: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$msg: expected ~$expected, got $actual", abs(expected - actual) <= tolerance)
    }

    /** Simple time-domain convolution for test fixtures. */
    private fun convolve(a: DoubleArray, b: DoubleArray): DoubleArray {
        val result = DoubleArray(a.size + b.size - 1)
        for (i in a.indices) {
            for (j in b.indices) {
                result[i + j] += a[i] * b[j]
            }
        }
        return result
    }
}
