package com.aquascope.dsp

import com.aquascope.audio.ChirpGenerator
import com.aquascope.baseline.AnomalyScorer
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * End-to-end integration test: chirp generation → simulated surface → deconvolution
 * → feature extraction → anomaly scoring. Validates the full pipeline with synthetic data.
 */
class PipelineIntegrationTest {

    private val sampleRate = 44100

    @Test
    fun `full pipeline distinguishes dry from wet simulated surface`() {
        val chirp = ChirpGenerator.generate(durationSec = 0.5, sampleRate = sampleRate)

        // Dry surface IR: resonance at 2500 Hz, moderate decay
        val dryIR = makeDampedResonator(2500.0, 80.0, 4096)
        val dryRecording = convolve(chirp, dryIR)
        val dryRecoveredIR = Deconvolution.deconvolve(dryRecording, chirp)
        val dryFeatures = FeatureExtractor.extract(dryRecoveredIR, sampleRate)

        // Wet surface IR: lower resonance at 1800 Hz, faster decay
        val wetIR = makeDampedResonator(1800.0, 200.0, 4096)
        val wetRecording = convolve(chirp, wetIR)
        val wetRecoveredIR = Deconvolution.deconvolve(wetRecording, chirp)
        val wetFeatures = FeatureExtractor.extract(wetRecoveredIR, sampleRate)

        // Baseline = dry surface
        val score = AnomalyScorer.score(wetFeatures, dryFeatures)

        println("Dry features: $dryFeatures")
        println("Wet features: $wetFeatures")
        println("Anomaly score: $score")

        assertTrue(
            "Wet surface should score as anomalous vs dry baseline, got $score",
            score > 20.0
        )

        // Dry-vs-dry should be low
        val dryVsDry = AnomalyScorer.score(dryFeatures, dryFeatures)
        assertTrue("Dry vs dry should be low, got $dryVsDry", dryVsDry < 10.0)
    }

    private fun makeDampedResonator(freq: Double, decay: Double, length: Int): DoubleArray {
        return DoubleArray(length) { i ->
            val t = i.toDouble() / sampleRate
            exp(-decay * t) * sin(2.0 * PI * freq * t)
        }
    }

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
