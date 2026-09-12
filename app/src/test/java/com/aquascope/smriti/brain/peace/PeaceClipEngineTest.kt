package com.aquascope.smriti.brain.peace

import org.junit.Assert.assertEquals
import org.junit.Test

class PeaceClipEngineTest {
    @Test
    fun groupsWithin20SecondsOfAnchor() {
        val hits = listOf(
            PeaceClipEngine.KillHit(0, 1f, "a"),
            PeaceClipEngine.KillHit(5_000, 1f, "b"),
            PeaceClipEngine.KillHit(18_000, 1f, "c"),
            PeaceClipEngine.KillHit(40_000, 1f, "d")
        )
        val groups = PeaceClipEngine.groupKills(hits)
        assertEquals(2, groups.size)
        assertEquals(3, groups[0].killCount)
        assertEquals(1, groups[1].killCount)
        assertEquals(0L, groups[0].clipStartMs)
        assertEquals(23_000L, groups[0].clipEndMs)
    }
}
