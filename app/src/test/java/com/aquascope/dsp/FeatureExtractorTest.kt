package com.aquascope.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FeatureExtractorTest {

    @Test
    fun `spectral centroid of pure tone equals that tone frequency`() {
        val sampleRate = 8192
        val freq = 1000.0
        val n = FFT.nextPowerOf2(sampleRate)
        val signal = DoubleArray(n) { sin(2.0 * PI * freq * it / sampleRate) }
        val re = signal.copyOf()
        val im = DoubleArray(n)
        FFT.fft(re, im)
        val mag = FFT.magnitude(re, im)

        val centroid = FeatureExtractor.spectralCentroid(mag, n / 2, sampleRate)
        assertEquals(freq, centroid, 50.0)
    }

    @Test
    fun `hilbert envelope of sine wave is approximately constant`() {
        val n = 4096
        val signal = DoubleArray(n) { sin(2.0 * PI * 100 * it / n) }
        val envelope = FeatureExtractor.hilbertEnvelope(signal)

        // Skip edges (taper effects), check middle is near 1.0
        val middle = envelope.slice(n / 4 until 3 * n / 4)
        val meanEnv = middle.average()
        assertEquals("Envelope of unit sine should be ~1.0", 1.0, meanEnv, 0.1)
    }

    @Test
    fun `decay time increases for longer exponential decay`() {
        val sampleRate = 44100
        // Fast decay
        val fastIR = DoubleArray(4096) { i ->
            exp(-500.0 * i.toDouble() / sampleRate) * sin(2.0 * PI * 1000 * i.toDouble() / sampleRate)
        }
        // Slow decay
        val slowIR = DoubleArray(4096) { i ->
            exp(-50.0 * i.toDouble() / sampleRate) * sin(2.0 * PI * 1000 * i.toDouble() / sampleRate)
        }

        val fastDecay = FeatureExtractor.estimateDecayTime(fastIR, sampleRate)
        val slowDecay = FeatureExtractor.estimateDecayTime(slowIR, sampleRate)

        assertTrue(
            "Slow decay ($slowDecay ms) should be longer than fast decay ($fastDecay ms)",
            slowDecay > fastDecay
        )
    }

    @Test
    fun `decay does not collapse to zero when envelope never reaches -20dB`() {
        val sampleRate = 44100
        // Very slow decay within a short window — may not reach -20dB
        val ir = DoubleArray(1024) { i ->
            val t = i.toDouble() / sampleRate
            exp(-5.0 * t) * sin(2.0 * PI * 1000 * t)
        }
        val decay = FeatureExtractor.estimateDecayTime(ir, sampleRate)
        assertTrue("Decay should be > 0 when threshold never crossed, got $decay", decay > 0.0)
    }

    @Test
    fun `feature extraction returns reasonable values for synthetic IR`() {
        val sampleRate = 44100
        val ir = DoubleArray(4096) { i ->
            val t = i.toDouble() / sampleRate
            exp(-100.0 * t) * sin(2.0 * PI * 2000 * t)
        }

        val features = FeatureExtractor.extract(ir, sampleRate)

        assertTrue("Resonance should be positive", features.resonanceFreqHz > 0)
        assertTrue("Decay should be positive", features.decayTimeMs > 0)
        assertTrue("Centroid should be positive", features.spectralCentroidHz > 0)
        assertTrue("Spread should be positive", features.spectralSpreadHz > 0)
        assertTrue("Flatness should be in [0,1]", features.spectralFlatness in 0.0..1.0)
    }

    /**
     * Key test: a "wet" surface (simulated with lower resonance + higher damping)
     * should produce different features than a "dry" surface.
     */
    @Test
    fun `wet vs dry simulated surfaces produce distinguishable features`() {
        val sampleRate = 44100

        // "Dry" surface: higher resonance, longer decay
        val dryIR = DoubleArray(8192) { i ->
            val t = i.toDouble() / sampleRate
            exp(-80.0 * t) * sin(2.0 * PI * 2500 * t)
        }

        // "Wet" surface: lower resonance (water adds mass), shorter decay (higher damping)
        val wetIR = DoubleArray(8192) { i ->
            val t = i.toDouble() / sampleRate
            exp(-200.0 * t) * sin(2.0 * PI * 1800 * t)
        }

        val dryFeatures = FeatureExtractor.extract(dryIR, sampleRate)
        val wetFeatures = FeatureExtractor.extract(wetIR, sampleRate)

        assertTrue(
            "Wet resonance (${wetFeatures.resonanceFreqHz}) should be lower than dry (${dryFeatures.resonanceFreqHz})",
            wetFeatures.resonanceFreqHz < dryFeatures.resonanceFreqHz
        )
        assertTrue(
            "Wet decay (${wetFeatures.decayTimeMs}) should be shorter than dry (${dryFeatures.decayTimeMs})",
            wetFeatures.decayTimeMs < dryFeatures.decayTimeMs
        )
    }
}
