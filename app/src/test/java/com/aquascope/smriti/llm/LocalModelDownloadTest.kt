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
}
