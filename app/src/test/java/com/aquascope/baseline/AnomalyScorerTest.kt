package com.aquascope.baseline

import com.aquascope.dsp.AcousticFeatures
import org.junit.Assert.*
import org.junit.Test

class AnomalyScorerTest {

    @Test
    fun `identical features yield near zero anomaly score`() {
        val features = AcousticFeatures(1000.0, 20.0, 3000.0, 1500.0, 0.5)
        val score = AnomalyScorer.score(features, features)
        assertEquals("Identical features should score ~0, got $score", 0.0, score, 0.01)
    }

    @Test
    fun `very different features yield high anomaly score`() {
        val baseline = AcousticFeatures(2500.0, 30.0, 4000.0, 2000.0, 0.6)
        val anomalous = AcousticFeatures(1500.0, 10.0, 2000.0, 800.0, 0.2)
        val score = AnomalyScorer.score(anomalous, baseline)
        assertTrue("Very different features should score high, got $score", score > 50.0)
    }

    @Test
    fun `score is between 0 and 100`() {
        val a = AcousticFeatures(500.0, 5.0, 1000.0, 500.0, 0.1)
        val b = AcousticFeatures(5000.0, 100.0, 10000.0, 5000.0, 0.9)
        val score = AnomalyScorer.score(a, b)
        assertTrue("Score should be in [0,100], got $score", score in 0.0..100.0)
    }

    @Test
    fun `averaging features works correctly`() {
        val samples = listOf(
            AcousticFeatures(1000.0, 20.0, 3000.0, 1500.0, 0.5),
            AcousticFeatures(1100.0, 22.0, 3100.0, 1600.0, 0.6)
        )
        val avg = AnomalyScorer.averageFeatures(samples)
        assertEquals(1050.0, avg.resonanceFreqHz, 0.01)
        assertEquals(21.0, avg.decayTimeMs, 0.01)
    }
}
