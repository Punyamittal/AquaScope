package com.aquascope.dsp

import kotlin.math.*

/**
 * Radix-2 Cooley-Tukey FFT implementation in pure Kotlin.
 * Chosen over external dependencies for zero-dependency portability and hackathon simplicity.
 * Signal sizes are small enough (~48k-96k samples) that pure-JVM performance is adequate.
 */
object FFT {

    fun nextPowerOf2(n: Int): Int {
        var v = n - 1
        v = v or (v shr 1)
        v = v or (v shr 2)
        v = v or (v shr 4)
        v = v or (v shr 8)
        v = v or (v shr 16)
        return v + 1
    }

    /**
     * In-place radix-2 FFT.
     * @param re real parts (length must be power of 2)
     * @param im imaginary parts (same length)
     * @param inverse true for IFFT
     */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) {
            "Length must be a positive power of 2, got $n"
        }

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = (if (inverse) 2.0 else -2.0) * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)

            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until halfLen) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val tRe = curRe * re[i + k + halfLen] - curIm * im[i + k + halfLen]
                    val tIm = curRe * im[i + k + halfLen] + curIm * re[i + k + halfLen]
                    re[i + k] = uRe + tRe
                    im[i + k] = uIm + tIm
                    re[i + k + halfLen] = uRe - tRe
                    im[i + k + halfLen] = uIm - tIm
                    val newCurRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = newCurRe
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            for (i in 0 until n) {
                re[i] = re[i] / n
                im[i] = im[i] / n
            }
        }
    }

    /** Compute magnitude spectrum from complex FFT output. */
    fun magnitude(re: DoubleArray, im: DoubleArray): DoubleArray {
        return DoubleArray(re.size) { sqrt(re[it] * re[it] + im[it] * im[it]) }
    }

    /** Zero-pad signal to next power of 2 (or specified size). */
    fun zeroPad(signal: DoubleArray, targetSize: Int = nextPowerOf2(signal.size)): DoubleArray {
        if (signal.size == targetSize) return signal.copyOf()
        return DoubleArray(targetSize).also { signal.copyInto(it) }
    }
}
