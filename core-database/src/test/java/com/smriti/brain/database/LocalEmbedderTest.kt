package com.smriti.brain.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEmbedderTest {
    @Test
    fun similarTextHasHigherCosineThanUnrelated() {
        val a = LocalEmbedder.embed("took 5mg medication at breakfast")
        val b = LocalEmbedder.embed("medication 5 mg morning dose")
        val c = LocalEmbedder.embed("call of duty kill feed clip")
        val ab = LocalEmbedder.cosine(a, b)
        val ac = LocalEmbedder.cosine(a, c)
        assertTrue(ab > ac)
    }

    @Test
    fun healthKeywordsRouteToHealth() {
        assertEquals(MemoryTaxonomy.HEALTH, TaxonomyRouter.route("took insulin 8 units"))
    }
}
