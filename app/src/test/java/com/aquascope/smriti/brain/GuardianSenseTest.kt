package com.aquascope.smriti.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianSenseTest {

    @Test
    fun `label meanings stay human readable`() {
        assertTrue(GuardianSense.labelMeaning("kitchen_alert").contains("kitchen"))
        assertTrue(GuardianSense.labelMeaning("smoke_alarm").contains("alarm"))
    }

    @Test
    fun `guardian questions are detected`() {
        assertTrue(GuardianSense.isGuardianIrQuestion("what did Guardian hear?"))
        assertTrue(GuardianSense.isGuardianIrQuestion("is IR armed?"))
        assertTrue(GuardianSense.isGuardianIrQuestion("did the IR pulse fire?"))
        assertFalse(GuardianSense.isGuardianIrQuestion("when was the first leak?"))
    }

    @Test
    fun `spoken snapshot mentions listening and IR state`() {
        val snap = GuardianSnapshot(
            windows = 10,
            baselineRms = 0.02f,
            meanRms = 0.04f,
            peakRms = 0.09f,
            meanHighFrac = 0.3f,
            labeledCounts = mapOf("kitchen_alert" to 2),
            irTransmits = 1,
            lastIrReason = "kitchen_alert ×2",
            lastIrFollowUp = "IR follow-up: rms 0.200 → 0.050 (quieter after blast)",
            summary = "raw"
        )
        val line = GuardianSense.spokenSnapshot(snap, guardianOn = true, irArmed = true)
        assertTrue(line.contains("listening", ignoreCase = true))
        assertTrue(line.contains("kitchen", ignoreCase = true))
        assertTrue(line.contains("IR armed"))
        assertTrue(line.contains("quieter", ignoreCase = true))
    }
}
