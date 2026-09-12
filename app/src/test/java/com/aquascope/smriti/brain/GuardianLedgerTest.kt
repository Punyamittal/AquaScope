package com.aquascope.smriti.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianLedgerTest {

    @Test
    fun `kitchen alert does not fire IR until it repeats above baseline`() {
        var t = 1_000L
        val ledger = GuardianLedger { t }
        repeat(12) {
            t += 1_000
            ledger.ingest(quiet(t))
        }
        t += 1_000
        ledger.ingest(kitchen(t, rms = 0.16f))
        val first = ledger.shouldFireIr("kitchen_alert", irArmed = true)
        assertFalse(first.fire)

        t += 20_000
        ledger.ingest(kitchen(t, rms = 0.18f))
        val second = ledger.shouldFireIr("kitchen_alert", irArmed = true)
        assertTrue(second.reason, second.fire)
    }

    @Test
    fun `IR stays off when disarmed even for smoke`() {
        val ledger = GuardianLedger { 5_000L }
        ledger.ingest(kitchen(5_000L, rms = 0.2f).copy(label = "smoke_alarm"))
        val decision = ledger.shouldFireIr("smoke_alarm", irArmed = false)
        assertFalse(decision.fire)
        assertTrue(decision.reason.contains("not armed"))
    }

    @Test
    fun `post-IR follow-up reports quieter when rms drops`() {
        var t = 10_000L
        val ledger = GuardianLedger { t }
        ledger.ingest(kitchen(t, rms = 0.20f))
        ledger.noteIrFired(
            IrTransmitRecord(true, 38_000, 67, "IR emitter present", "kitchen_alert")
        )
        repeat(6) {
            t += 1_000
            ledger.ingest(quiet(t, rms = 0.05f))
        }
        t += 3_000
        val follow = ledger.finishPostIrIfDue()
        assertTrue(follow, follow != null && follow.contains("quieter"))
    }

    private fun quiet(t: Long, rms: Float = 0.02f) = GuardianWindow(
        rms = rms,
        highFrac = 0.2f,
        midFrac = 0.4f,
        lowFrac = 0.4f,
        label = null,
        confidence = 0f,
        accelMag = 9.8f,
        timestampMs = t
    )

    private fun kitchen(t: Long, rms: Float) = GuardianWindow(
        rms = rms,
        highFrac = 0.2f,
        midFrac = 0.6f,
        lowFrac = 0.2f,
        label = "kitchen_alert",
        confidence = 0.6f,
        accelMag = 9.8f,
        timestampMs = t
    )
}
