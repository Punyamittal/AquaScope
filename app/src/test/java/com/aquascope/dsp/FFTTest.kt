package com.aquascope.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FFTTest {

    @Test
    fun `nextPowerOf2 returns correct values`() {
        assertEquals(1, FFT.nextPowerOf2(1))
        assertEquals(2, FFT.nextPowerOf2(2))
        assertEquals(4, FFT.nextPowerOf2(3))
        assertEquals(1024, FFT.nextPowerOf2(513))
    }

    @Test
    fun `FFT of single frequency recovers that frequency`() {
        val n = 1024
        val sampleRate = 1024.0
        val freq = 100.0
        val re = DoubleArray(n) { sin(2.0 * PI * freq * it / sampleRate) }
        val im = DoubleArray(n)

        FFT.fft(re, im)
        val mag = FFT.magnitude(re, im)

        // Peak should be at bin 100 (freq * n / sampleRate)
        val peakBin = mag.indices.drop(1).take(n / 2 - 1).maxByOrNull { mag[it] }!!
        assertEquals(100, peakBin)
    }

    @Test
    fun `IFFT of FFT recovers original signal`() {
        val n = 256
        val original = DoubleArray(n) { sin(2.0 * PI * 10 * it / n) + 0.5 * cos(2.0 * PI * 30 * it / n) }
        val re = original.copyOf()
        val im = DoubleArray(n)

        FFT.fft(re, im)
        FFT.fft(re, im, inverse = true)

        for (i in 0 until n) {
            assertEquals(original[i], re[i], 1e-10)
        }
    }

    @Test
    fun `Parseval theorem - energy in time equals energy in frequency`() {
        val n = 512
        val signal = DoubleArray(n) { sin(2.0 * PI * 50 * it / n) }
        val timeEnergy = signal.sumOf { it * it }

        val re = signal.copyOf()
        val im = DoubleArray(n)
        FFT.fft(re, im)
        val freqEnergy = (0 until n).sumOf { re[it] * re[it] + im[it] * im[it] } / n

        assertEquals(timeEnergy, freqEnergy, 1e-8)
    }
}
