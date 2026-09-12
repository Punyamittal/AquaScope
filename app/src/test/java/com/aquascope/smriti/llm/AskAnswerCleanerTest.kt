package com.aquascope.smriti.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAnswerCleanerTest {

    @Test
    fun `strips evidence prompt leak from model output`() {
        val raw = """
            EVIDENCE_STATE: OBSERVED
            CANONICAL_ANSWER:
            kitchen drip
            MEMORY_EVENTS (2):
            - 12 Sep 2026, 3:20 PM | Guardian | UNKNOWN | score=12.0 | Guardian rms=0.02 peak=0.04
            ANSWER:
            I heard kitchen activity this afternoon.
        """.trimIndent()
        val cleaned = AskAnswerCleaner.cleanModelOutput(raw)
        assertTrue(cleaned.contains("kitchen activity"))
        assertFalse(cleaned.contains("EVIDENCE_STATE"))
        assertFalse(cleaned.contains("MEMORY_EVENTS"))
        assertFalse(cleaned.contains("score="))
        assertFalse(cleaned.contains("rms="))
    }

    @Test
    fun `turns guardian rms dump into spoken line`() {
        val spoken = AskAnswerCleaner.userFacingSummary(
            "Guardian rms=0.012 peak=0.04 vs baseline 0.008 (1.5×) · kitchen_alert×3 · last IR: kitchen_alert"
        )
        assertTrue(spoken.contains("Guardian was listening"))
        assertTrue(spoken.contains("kitchen alert"))
        assertFalse(spoken.contains("rms="))
        assertFalse(spoken.contains("peak="))
    }

    @Test
    fun `hides recording file paths`() {
        val spoken = AskAnswerCleaner.cleanForDisplay(
            "Clip stored. Recording saved: /data/user/0/com.aquascope/files/clip.wav"
        )
        assertTrue(spoken.contains("clip is saved on this phone"))
        assertFalse(spoken.contains("/data/"))
    }

    @Test
    fun `flags leftover prompt labels`() {
        assertTrue(AskAnswerCleaner.looksLikePromptLeak("CANONICAL_ANSWER: foo"))
        assertFalse(AskAnswerCleaner.looksLikePromptLeak("I found two kitchen scans today."))
    }
}
