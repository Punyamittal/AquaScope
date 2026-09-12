package com.aquascope.smriti.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmritiMemoryEngineTest {

    @Test
    fun `hashing embeddings are unit length and cosine is reflexive`() {
        val v = HashingEmbedder.embed("paracetamol 500 mg after dinner")
        val n = v.fold(0.0) { acc, x -> acc + x * x }
        assertTrue(kotlin.math.abs(n - 1.0) < 1e-4)
        assertTrue(HashingEmbedder.cosine(v, v) > 0.99f)
    }

    @Test
    fun `taxonomy routes health and money deterministically`() {
        assertEquals(TaxonomyParser.Kind.HEALTH, TaxonomyParser.parse("Take 500 mg tablet at 21:00").kind)
        assertEquals(TaxonomyParser.Kind.MONEY, TaxonomyParser.parse("Invoice due ₹1200").kind)
        assertEquals(TaxonomyParser.Kind.GAME, TaxonomyParser.parse("BGMI clutch kill feed").kind)
    }

    @Test
    fun `blob round trip preserves embedding`() {
        val v = HashingEmbedder.embed("parking level B2 mall")
        val back = HashingEmbedder.fromBlob(HashingEmbedder.toBlob(v))
        assertEquals(v.size, back.size)
        assertTrue(HashingEmbedder.cosine(v, back) > 0.99f)
    }

    @Test
    fun `no-record contract string is explicit`() {
        assertEquals("No record found", SmritiMemoryEngine.NO_RECORD)
        assertFalse(SmritiMemoryEngine.NO_RECORD.contains("maybe", ignoreCase = true))
    }
}
