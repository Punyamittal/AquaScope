package com.aquascope.report

import com.aquascope.dsp.AcousticFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportGeneratorTest {

    private fun features(res: Double = 2000.0) = AcousticFeatures(
        resonanceFreqHz = res,
        decayTimeMs = 25.0,
        spectralCentroidHz = 3500.0,
        spectralSpreadHz = 1200.0,
        spectralFlatness = 0.4
    )

    @Test
    fun `status labels match thresholds`() {
        assertEquals("NORMAL", ReportGenerator.statusLabel(10.0))
        assertEquals("ELEVATED", ReportGenerator.statusLabel(50.0))
        assertEquals("ANOMALY", ReportGenerator.statusLabel(80.0))
    }

    @Test
    fun `share text includes location and point scores`() {
        val report = SessionReport(
            locationLabel = "Kitchen wall",
            points = listOf(
                ReportPoint("P1", 12.0, features()),
                ReportPoint("P2", 78.0, features(1600.0))
            )
        )
        val text = ReportGenerator.toShareText(report)
        assertTrue(text.contains("Kitchen wall"))
        assertTrue(text.contains("P1"))
        assertTrue(text.contains("P2"))
        assertTrue(text.contains("78%"))
        assertTrue(text.contains("ANOMALY"))
        assertTrue(text.contains("AQUASCOPE"))
    }

    @Test
    fun `assessment flags anomalous points`() {
        val report = SessionReport(
            locationLabel = "Pipe A",
            points = listOf(ReportPoint("P1", 85.0, features()))
        )
        assertTrue(ReportGenerator.overallAssessment(report).contains("ANOMALY"))
    }
}
