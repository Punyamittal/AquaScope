package com.aquascope.halo

import com.aquascope.smriti.model.EvidenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmritiLightMapperTest {

    @Test
    fun `unknown evidence maps to UNKNOWN not danger red`() {
        val s = SmritiLightMapper.fromAsk(EvidenceState.UNKNOWN, 0)
        assertEquals(SmritiLightState.UNKNOWN, s)
        val render = HaloPalette.render(s, 0.65f)
        assertEquals(HaloPalette.NEUTRAL_WHITE, render.colorArgb)
    }

    @Test
    fun `home normal stays cyan`() {
        val s = SmritiLightMapper.fromHomeStatus("NORMAL", 0)
        assertEquals(SmritiLightState.NORMAL, s)
    }

    @Test
    fun `effect json for scanning is non-empty marquee`() {
        val json = HaloEffectJson.forState(SmritiLightState.SCANNING, 60)
        assertTrue(json.contains("marquee"))
        assertTrue(json.contains("smriti_aqua"))
        assertTrue(json.contains("\"subType\":0"))
    }

    @Test
    fun `normal uses slowPulse marquee not breathing type`() {
        val json = HaloEffectJson.forState(SmritiLightState.NORMAL, 80)
        assertTrue(json.contains("marquee"))
        assertTrue(json.contains("\"subType\":2"))
        assertTrue(json.contains("\"brightness\":100"))
        assertTrue(json.contains("\"periodTime\":800"))
        assertTrue(!json.contains("\"type\":\"breathing\""))
        val render = HaloPalette.render(SmritiLightState.NORMAL, 0.65f)
        assertTrue(render.brightness >= 0.82f)
        assertEquals(900, render.periodMs)
    }

    @Test
    fun `test solid is strobe lasting`() {
        val json = HaloEffectJson.testSolid()
        assertTrue(json.contains("strobe"))
        assertTrue(json.contains("60000"))
    }
}
