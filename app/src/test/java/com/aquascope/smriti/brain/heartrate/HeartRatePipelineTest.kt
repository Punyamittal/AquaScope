package com.aquascope.smriti.brain.heartrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class HeartRatePipelineTest {

    @Test
    fun peakDetectorFindsPeriodicPulses() {
        val fs = 30.0
        val bpm = 72.0
        val hz = bpm / 60.0
        val n = 300
        val values = DoubleArray(n)
        val ts = LongArray(n)
        for (i in 0 until n) {
            val t = i / fs
            values[i] = sin(2 * PI * hz * t)
            ts[i] = (t * 1e9).toLong()
        }
        val peaks = PeakDetector.detect(values, ts)
        assertTrue(peaks.size >= 6)
        val estimate = HeartRateEstimator.estimate(peaks, values, fs)
        assertNotNull(estimate)
        assertTrue(estimate!!.bpm in 60.0..85.0)
        assertTrue(estimate.validBeats >= 4)
    }

    @Test
    fun fingerDetectorAcceptsTorchFingerProfile() {
        val (covered, score) = FingerDetector.analyze(
            redMean = 180f,
            greenMean = 90f,
            blueMean = 70f,
            brightness = 140f
        )
        assertTrue(covered)
        assertTrue(score > 0.45f)
    }

    @Test
    fun fingerDetectorRejectsDarkUncovered() {
        val (covered, _) = FingerDetector.analyze(
            redMean = 20f,
            greenMean = 18f,
            blueMean = 16f,
            brightness = 18f
        )
        assertEquals(false, covered)
    }

    @Test
    fun bandPassPassesPulseBand() {
        val filter = BandPassFilter(sampleRateHz = 30.0)
        var energy = 0.0
        for (i in 0 until 200) {
            val t = i / 30.0
            val y = filter.filter(sin(2 * PI * 1.2 * t)) // ~72 BPM
            if (i > 60) energy += y * y
        }
        assertTrue(energy > 1.0)
    }

    @Test
    fun qualityClassifierMarksStrongSignalHigh() {
        val q = SignalQualityAnalyzer.classify(
            confidence = 0.8f,
            validBeats = 10,
            coverageScore = 0.8f,
            fingerCoveredRatio = 0.9f,
            ibiStdSec = 0.05,
            snrApprox = 3.0
        )
        assertEquals(SignalQuality.HIGH, q)
    }
}
