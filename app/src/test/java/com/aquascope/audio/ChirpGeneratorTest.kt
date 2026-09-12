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
    fun `iqoo profile defaults use preferred sample rate and band`() {
        assertEquals(IqooDeviceProfile.PREFERRED_SAMPLE_RATE, ChirpGenerator.DEFAULT_SAMPLE_RATE)
        assertEquals(IqooDeviceProfile.CHIRP_START_HZ, ChirpGenerator.DEFAULT_START_FREQ, 0.0)
        assertEquals(IqooDeviceProfile.CHIRP_END_HZ, ChirpGenerator.DEFAULT_END_FREQ, 0.0)
        val chirp = ChirpGenerator.generate()
        assertEquals(
            (IqooDeviceProfile.CHIRP_DURATION_SEC * IqooDeviceProfile.PREFERRED_SAMPLE_RATE).toInt(),
            chirp.size
        )
    }
}
