package com.aquascope.smriti.brain

import kotlin.math.sqrt

/**
 * Deterministic 256-d hashing-trick embeddings. No network, no model file.
 * Used for in-RAM cosine search over episodic text.
 */
object HashingEmbedder {
    const val DIM = 256

    fun embed(text: String): FloatArray {
        val v = FloatArray(DIM)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return v
        tokens.forEachIndexed { i, token ->
            add(v, token, 1f)
            if (i + 1 < tokens.size) add(v, token + "_" + tokens[i + 1], 0.7f)
        }
        l2Normalize(v)
        return v
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var dot = 0.0
        for (i in 0 until n) dot += a[i] * b[i]
        return dot.toFloat()
    }

    fun toBlob(v: FloatArray): ByteArray {
        val out = ByteArray(v.size * 4)
        var p = 0
        for (f in v) {
            val bits = f.toBits()
            out[p++] = (bits and 0xFF).toByte()
            out[p++] = (bits ushr 8 and 0xFF).toByte()
            out[p++] = (bits ushr 16 and 0xFF).toByte()
            out[p++] = (bits ushr 24 and 0xFF).toByte()
        }
        return out
    }

    fun fromBlob(blob: ByteArray): FloatArray {
        val n = blob.size / 4
        val v = FloatArray(n)
        var p = 0
        for (i in 0 until n) {
            val bits = (blob[p].toInt() and 0xFF) or
                ((blob[p + 1].toInt() and 0xFF) shl 8) or
                ((blob[p + 2].toInt() and 0xFF) shl 16) or
                ((blob[p + 3].toInt() and 0xFF) shl 24)
            v[i] = Float.fromBits(bits)
            p += 4
        }
        return v
    }

    fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9.+#@/\\- ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

    private fun add(v: FloatArray, token: String, weight: Float) {
        val h = token.hashCode()
        val idx = (h ushr 1).mod(DIM)
        val sign = if (h and 1 == 0) 1f else -1f
        v[idx] += sign * weight
    }

    private fun l2Normalize(v: FloatArray) {
        var sum = 0.0
        for (x in v) sum += x * x
        val n = sqrt(sum).toFloat()
        if (n < 1e-8f) return
        for (i in v.indices) v[i] /= n
    }
}
