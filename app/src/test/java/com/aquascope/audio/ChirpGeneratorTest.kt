package com.aquascope.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ChirpGeneratorTest {

    @Test
    fun `chirp has correct length`() {
        val sr = 44100
        val dur = 1.0
        val chirp = ChirpGenerator.generate(durationSec = dur, sampleRate = sr)
        assertEquals(sr, chirp.size)
    }

    @Test
    fun `chirp samples are in valid range`() {
        val chirp = ChirpGenerator.generate()
        assertTrue(chirp.all { it >= -1.0 && it <= 1.0 })
    }

    @Test
    fun `chirp starts and ends near zero due to taper`() {
        val chirp = ChirpGenerator.generate()
        assertTrue("Start should be near zero", abs(chirp[0]) < 0.01)
        assertTrue("End should be near zero", abs(chirp.last()) < 0.01)
    }

    @Test
    fun `toShortArray preserves signal shape`() {
        val chirp = ChirpGenerator.generate(durationSec = 0.1)
        val shorts = ChirpGenerator.toShortArray(chirp)
        assertEquals(chirp.size, shorts.size)
        // Check a few samples for correct scaling
        for (i in chirp.indices step 100) {
            val expected = (chirp[i] * Short.MAX_VALUE).toInt().toShort()
            assertEquals(expected, shorts[i])
        }
    }
}
