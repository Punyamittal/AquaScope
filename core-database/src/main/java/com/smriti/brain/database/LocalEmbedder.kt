package com.smriti.brain.database

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic 384-d character n-gram random projection.
 * Runs fully offline; cosine search is real, not a stub list.
 */
object LocalEmbedder {
    const val DIM = 384
    private val proj: Array<FloatArray> = Array(DIM) { d ->
        val rng = Random(2026L + d * 9973L)
        FloatArray(256) { (rng.nextFloat() * 2f) - 1f }
    }

    fun embed(text: String): FloatArray {
        val v = FloatArray(DIM)
        val ngrams = ngrams(text.lowercase())
        if (ngrams.isEmpty()) return v
        for (g in ngrams) {
            val h = (g.hashCode() and 0x7fffffff)
            for (d in 0 until DIM) {
                v[d] += proj[d][h % 256]
            }
        }
        var n = 0.0
        for (x in v) n += x * x
        val inv = if (n > 1e-9) (1.0 / sqrt(n)).toFloat() else 1f
        for (i in v.indices) v[i] *= inv
        return v
    }

    fun toBlob(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun fromBlob(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buf.getFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0f
        for (i in 0 until n) dot += a[i] * b[i]
        return dot
    }

    private fun ngrams(text: String): List<String> {
        val cleaned = text.filter { it.isLetterOrDigit() || it.isWhitespace() }
        if (cleaned.length < 3) return listOf(cleaned)
        return (0..cleaned.length - 3).map { cleaned.substring(it, it + 3) }
    }
}
