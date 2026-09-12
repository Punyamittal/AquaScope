package com.smriti.core.memory

import kotlin.math.sqrt

/**
 * Turns raw text into a fixed-dimension embedding for in-memory cosine re-ranking.
 *
 * Default engine implementation is [HashEmbeddingProvider] (100% offline, zero deps).
 * Drop-in upgrade path: implement this interface over a MediaPipe GenAI / TFLite
 * text embedder (e.g. a .task model in assets) and inject it into
 * [SmritiMemoryEngine] — the engine only depends on this interface. NOTE: swapping
 * providers changes the vector space; stored BLOBs would need re-embedding.
 */
interface EmbeddingProvider {
    val dim: Int
    fun embed(text: String): FloatArray
}

/**
 * Deterministic feature-hashing embedder (SPEC section 2.1).
 *
 * Algorithm:
 *  1. Lowercase, split on non-alphanumerics -> tokens.
 *  2. Each token is hashed twice with two DIFFERENT hash functions
 *     (FNV-1a 32-bit and djb2-xor) giving two signed positions in [dim]:
 *     position = (hash ushr 1) % dim, sign = low bit of hash (+1/-1).
 *     Using separate bits for position vs sign keeps them decorrelated.
 *  3. Accumulate signed contributions, then L2-normalize.
 *
 * Empty / tokenless text yields the zero vector (cosine 0 against everything).
 * Fully deterministic: same input -> same vector, no randomness, no model files.
 */
class HashEmbeddingProvider(override val dim: Int = 128) : EmbeddingProvider {

    private val tokenSplitter = Regex("[^a-z0-9]+")

    override fun embed(text: String): FloatArray {
        val vec = FloatArray(dim)
        val tokens = text.lowercase().split(tokenSplitter).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return vec // zero vector per SPEC
        for (token in tokens) {
            val h1 = fnv1a(token)
            val h2 = djb2xor(token)
            val pos1 = (h1 ushr 1) % dim
            val pos2 = (h2 ushr 1) % dim
            vec[pos1] += if (h1 and 1 == 0) 1f else -1f
            vec[pos2] += if (h2 and 1 == 0) 1f else -1f
        }
        var normSq = 0f
        for (v in vec) normSq += v * v
        val norm = sqrt(normSq)
        if (norm > 0f) {
            for (i in vec.indices) vec[i] /= norm
        }
        return vec
    }

    /** FNV-1a 32-bit hash (deterministic across JVM/Android runs). */
    private fun fnv1a(s: String): Int {
        var h = 0x811C9DC5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h
    }

    /** djb2-xor 32-bit hash — a second, different hash family for position 2. */
    private fun djb2xor(s: String): Int {
        var h = 5381
        for (c in s) {
            h = ((h shl 5) + h) xor c.code
        }
        return h
    }
}
