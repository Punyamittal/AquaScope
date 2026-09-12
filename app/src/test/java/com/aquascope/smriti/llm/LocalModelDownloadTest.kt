package com.aquascope.smriti.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelDownloadTest {

    @Test
    fun `downloadable models use https huggingface urls`() {
        val models = LocalModelCatalog.downloadable
        assertTrue(models.isNotEmpty())
        models.forEach { entry ->
            val url = entry.downloadUrl
            assertTrue("${entry.id} missing url", !url.isNullOrBlank())
            assertTrue("${entry.id} must be https", url!!.startsWith("https://"))
            assertTrue(entry.sizeBytes > LocalModelStore.MIN_BYTES)
        }
    }

    @Test
    fun `preferred names include download filenames`() {
        val names = LocalModelCatalog.preferredFileNames()
        LocalModelCatalog.downloadable.forEach { entry ->
            assertTrue(names.contains(entry.fileName))
        }
    }

    @Test
    fun `normalizes hugging face tokens`() {
        assertNull(LocalModelDownloadPolicy.normalizeToken("  "))
        assertEquals("hf_abc", LocalModelDownloadPolicy.normalizeToken("  hf_abc  "))
        assertEquals("hf_abc", LocalModelDownloadPolicy.normalizeToken("Bearer hf_abc"))
    }

    @Test
    fun `classifies gated http failures`() {
        assertTrue(LocalModelDownloadPolicy.needsAccessToken(401))
        assertTrue(LocalModelDownloadPolicy.needsAccessToken(403))
        assertFalse(LocalModelDownloadPolicy.needsAccessToken(404))
        assertFalse(LocalModelDownloadPolicy.needsAccessToken(200))
    }

    @Test
    fun `detects html stand-in for a model file`() {
        assertTrue(LocalModelDownloadPolicy.isHtmlContentType("text/html; charset=utf-8"))
        assertFalse(LocalModelDownloadPolicy.isHtmlContentType("application/octet-stream"))
        assertTrue(LocalModelDownloadPolicy.looksLikeHtml("<!DOCTYPE html>".toByteArray()))
        assertFalse(LocalModelDownloadPolicy.looksLikeHtml("PK\u0003\u0004".toByteArray()))
    }

    @Test
    fun `formats progress sizes`() {
        assertEquals("512 KB", LocalModelDownloadPolicy.formatBytes(512 * 1024L))
        assertEquals("1.5 MB", LocalModelDownloadPolicy.formatBytes((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `recognizes gallery and mediapipe model names`() {
        assertTrue(LocalModelStore.isModelFile("Gemma3-1B-IT.litertlm"))
        assertTrue(LocalModelStore.isModelFile("gemma3-1b-it-int4.task"))
        assertFalse(LocalModelStore.isLoadableMediaPipe("Gemma3-1B-IT.litertlm"))
        assertTrue(LocalModelStore.isLoadableMediaPipe("gemma3-1b-it-int4.task"))
        assertTrue(LocalModelStore.isPreferredTask("gemma3-1b-it-int4.task"))
        assertFalse(LocalModelStore.isPreferredTask("model.litertlm"))
        assertTrue(LocalModelStore.looksLikeGemmaBundle("Gemma3-1B-IT_q4_ekv1280.task"))
        assertTrue(LocalModelStore.looksLikeGemmaBundle("gemma3-1b-it.litertlm"))
        assertFalse(LocalModelStore.looksLikeGemmaBundle("notes.txt"))
    }

    @Test
    fun `preferred names include gallery-exported task aliases`() {
        val names = LocalModelCatalog.preferredFileNames()
        assertTrue(names.contains("Gemma3-1B-IT.task"))
        assertTrue(names.contains("Gemma3-1B-IT_q4_ekv1280.task"))
    }

    @Test
    fun `complete-enough uses catalog size with eighty percent floor`() {
        val expected = LocalModelCatalog.gemma3_1b.sizeBytes
        assertFalse(LocalModelDownloadPolicy.isCompleteEnough(0L, expected))
        assertFalse(LocalModelDownloadPolicy.isCompleteEnough(LocalModelStore.MIN_BYTES + 1, expected))
        assertFalse(LocalModelDownloadPolicy.isCompleteEnough(expected * 7 / 10, expected))
        assertTrue(LocalModelDownloadPolicy.isCompleteEnough(expected * 8 / 10, expected))
        assertTrue(LocalModelDownloadPolicy.isCompleteEnough(expected, expected))
        assertTrue(LocalModelDownloadPolicy.isCompleteEnough(2_000_000L, 0L))
        assertFalse(LocalModelDownloadPolicy.isCompleteEnough(500_000L, 0L))
    }

    @Test
    fun `partial download suffix is not a loadable model name`() {
        val staging = "${LocalModelCatalog.gemma3_1b.fileName}.part"
        assertEquals("gemma3-1b-it-int4.task.part", staging)
        assertFalse(LocalModelStore.isModelFile(staging))
        assertFalse(LocalModelStore.isLoadableMediaPipe(staging))
        assertTrue(LocalModelStore.isModelFile(LocalModelCatalog.gemma3_1b.fileName))
    }
}
