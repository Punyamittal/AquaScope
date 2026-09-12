package com.aquascope.smriti.brain.screenmind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMindAnalyzerTest {

    @Test
    fun `parses screenmind json`() {
        val raw = """
            Here you go:
            {"app_name":"WhatsApp","activity_category":"communication","activity_summary":"Reading a chat","detailed_context":"Chat with Alex","visible_text_snippets":["Hello","Alex"],"mood":"collaborative","confidence":0.9,"scene_description":"Chat list and message"}
        """.trimIndent()
        val rec = ScreenMindAnalyzer.parseJson(raw)
        assertNotNull(rec)
        assertEquals("WhatsApp", rec!!.appName)
        assertEquals("communication", rec.category)
        assertTrue(rec.memoryText().contains("ScreenMind"))
        assertTrue(rec.askContext().contains("SCREENMIND"))
    }

    @Test
    fun `ocr-only heuristics detect gaming`() {
        val rec = ScreenMindAnalyzer.fromOcrOnly("K/D 2.1 · MATCH FOUND · BGMI", "clip")
        assertEquals("gaming", rec.category)
    }

    @Test
    fun `ocr-only heuristics detect bgmi app name`() {
        val rec = ScreenMindAnalyzer.fromOcrOnly("BGMI · MATCH FOUND · K/D 2.1", "clip")
        assertEquals("BGMI", rec.appName)
        assertEquals("gaming", rec.category)
        assertTrue(rec.askContext().contains("Game/App opened: BGMI"))
    }

    @Test
    fun `foreground hint upgrades Phone UI`() {
        val base = ScreenMindAnalyzer.fromOcrOnly("", "clip")
        val upgraded = ScreenMindAnalyzer.withForegroundHint(base, "BGMI")
        assertEquals("BGMI", upgraded.appName)
        assertEquals("gaming", upgraded.category)
    }

    @Test
    fun `normalizes pc base url`() {
        assertEquals(
            "http://192.168.1.5:7777",
            ScreenMindPreferences.normalizeBaseUrl("192.168.1.5:7777")
        )
    }
}
