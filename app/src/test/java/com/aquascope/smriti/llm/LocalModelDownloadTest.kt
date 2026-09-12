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
        LocalModelCatalog.downloadableLlm.forEach { entry ->
            assertTrue(
                "${entry.fileName} missing from preferred",
                names.contains(entry.fileName) ||
                    entry.runtime == LocalModelCatalog.RuntimeKind.LITERT_LM
            )
        }
        assertTrue(names.contains(LocalModelCatalog.gemma4_e4b.fileName))
        assertTrue(names.contains(LocalModelCatalog.gemma4_e4b_full.fileName))
        assertEquals(3_659_530_240L, LocalModelCatalog.gemma4_e4b_full.sizeBytes)
        assertTrue(LocalModelCatalog.downloadable.contains(LocalModelCatalog.gemma4_e4b_full))
    }

    @Test
    fun `normalizes hugging face tokens`() {
        assertNull(LocalModelDownloadPolicy.normalizeToken("  "))
        assertEquals("hf_abc", LocalModelDownloadPolicy.normalizeToken("  hf_abc  "))
        assertEquals("hf_abc", LocalModelDownloadPolicy.normalizeToken("Bearer hf_abc"))
        assertEquals(
            "hf_abcdefghijklmnopqrstuv",
            LocalModelDownloadPolicy.extractHfToken("please use hf_abcdefghijklmnopqrstuv thanks")
        )
        assertEquals("hf_••••wxyz", LocalModelDownloadPolicy.maskToken("hf_abcdefghijklmnopqrstuvwxyz"))
    }

    @Test
    fun `sends hugging face token only to hub host`() {
        assertTrue(LocalModelDownloadPolicy.shouldAttachHfToken("huggingface.co"))
        assertTrue(LocalModelDownloadPolicy.shouldAttachHfToken("www.huggingface.co"))
        assertFalse(LocalModelDownloadPolicy.shouldAttachHfToken("cdn-lfs.huggingface.co"))
        assertFalse(LocalModelDownloadPolicy.shouldAttachHfToken("cas-bridge.xethub.hf.co"))
        assertFalse(LocalModelDownloadPolicy.shouldAttachHfToken("cdn-lfs-us-1.hf.co"))
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
        assertTrue(LocalModelStore.isModelFile("gemma-4-E4B-it.litertlm"))
        assertFalse(LocalModelStore.isLoadableMediaPipe("Gemma3-1B-IT.litertlm"))
        assertTrue(LocalModelStore.isLoadableMediaPipe("gemma3-1b-it-int4.task"))
        assertTrue(LocalModelStore.isLoadableLiteRt("gemma-4-E4B-it.litertlm"))
        assertFalse(LocalModelStore.isLoadableLiteRt("random-gallery.litertlm"))
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

    @Test
    fun `whisper is speech-only and not a mediapipe llm`() {
        val w = LocalModelCatalog.whisperTiny
        assertTrue(w.isSpeech)
        assertTrue(w.canDownload)
        assertTrue(LocalModelCatalog.downloadableSpeech.contains(w))
        assertFalse(LocalModelStore.isLoadableMediaPipe(w.fileName))
        assertTrue(LocalModelStore.isSpeechAsset(w.fileName))
        assertTrue(LocalModelStore.isModelFile(w.fileName))
    }

    @Test
    fun `speech language heuristics`() {
        assertTrue(SpeechLanguage.isLikelyEnglish("What did I scan in the kitchen?"))
        assertFalse(SpeechLanguage.isLikelyEnglish("क्या मैंने किचन में स्कैन किया?"))
        assertFalse(SpeechLanguage.isLikelyEnglish("厨房里我扫了什么"))
    }

    @Test
    fun `ollama base url normalization`() {
        assertEquals("http://127.0.0.1:11434", OllamaPreferences.normalizeBaseUrl(""))
        assertEquals("http://192.168.1.5:11434", OllamaPreferences.normalizeBaseUrl("192.168.1.5:11434"))
        assertEquals("http://192.168.1.5:11434", OllamaPreferences.normalizeBaseUrl("http://192.168.1.5:11434/"))
    }

    @Test
    fun `whisper language ids cover en hi`() {
        assertEquals(50259, WhisperTokenizer.languageId("en-US"))
        assertEquals(50276, WhisperTokenizer.languageId("hi"))
        assertEquals(50259, WhisperTokenizer.languageId("zz"))
    }

    @Test
    fun `whisper mel output shape is 1x80x3000`() {
        val pcm = ShortArray(WhisperMelFrontend.SAMPLE_RATE) // 1s
        val mel = WhisperMelFrontend.fromPcm16(pcm)
        assertEquals(1, mel.size)
        assertEquals(80, mel[0].size)
        assertEquals(3_000, mel[0][0].size)
    }
}
