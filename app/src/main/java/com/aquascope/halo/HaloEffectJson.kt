package com.aquascope.halo

/**
 * Builds vivo_light_service JSON using effect shapes proven by OriginOS / monster_halo:
 * - solid color  → type=strobe, period≈60s, count=1
 * - breathe/pulse → type=marquee, subType=2 (slowPulse)
 * - sweep/chase   → type=marquee, subType=0 (chase)
 *
 * Avoid unsupported custom "breathing" payloads that silently no-op on some builds.
 */
object HaloEffectJson {

    private const val SUB_CHASE = 0
    private const val SUB_SLOW_PULSE = 2

    fun forState(state: SmritiLightState, brightnessPct: Int): String {
        val b = brightnessPct.coerceIn(20, 100)
        return when (state) {
            SmritiLightState.OFF -> empty()
            SmritiLightState.NORMAL -> marquee(
                colors = listOf(
                    HaloPalette.CYAN_BRIGHT,
                    HaloPalette.CYAN_BLOOM,
                    HaloPalette.CYAN,
                    HaloPalette.CYAN_BRIGHT
                ),
                brightness = 100,
                periodMs = 800,
                subType = SUB_SLOW_PULSE,
                count = 160,
                repeat = -1
            )
            SmritiLightState.SCANNING -> chase(
                colors = listOf(HaloPalette.BLUE, HaloPalette.CYAN, HaloPalette.PURPLE, HaloPalette.CYAN),
                brightness = b.coerceAtLeast(55),
                periodMs = 900,
                count = 80,
                repeat = -1
            )
            SmritiLightState.PROCESSING -> slowPulse(
                color = HaloPalette.PURPLE,
                brightness = b.coerceAtLeast(55),
                periodMs = 900,
                count = 12,
                repeat = 0
            )
            SmritiLightState.NEW_MEMORY -> solid(
                color = HaloPalette.WARM_WHITE,
                brightness = b.coerceAtLeast(70),
                holdMs = 1200
            )
            SmritiLightState.ANOMALY -> slowPulse(
                color = HaloPalette.AMBER,
                brightness = b.coerceAtLeast(55),
                periodMs = 2200,
                count = 60,
                repeat = -1
            )
            SmritiLightState.PERSISTENT_ANOMALY -> slowPulse(
                color = HaloPalette.AMBER_DEEP,
                brightness = b.coerceAtLeast(60),
                periodMs = 3200,
                count = 60,
                repeat = -1
            )
            SmritiLightState.CONFIRMED -> solid(
                color = HaloPalette.RED_RESTRAINED,
                brightness = b.coerceAtLeast(50),
                holdMs = 2500
            )
            SmritiLightState.MEMORY_RECALL -> chase(
                colors = listOf(HaloPalette.TEAL, HaloPalette.CYAN, HaloPalette.TEAL, HaloPalette.CYAN),
                brightness = b.coerceAtLeast(55),
                periodMs = 1100,
                count = 24,
                repeat = 0
            )
            SmritiLightState.UNKNOWN -> solid(
                color = HaloPalette.NEUTRAL_WHITE,
                brightness = b.coerceIn(25, 45),
                holdMs = 2500
            )
        }
    }

    /** Known-good solid used by System → Test light. */
    fun testSolid(colorArgb: Int = HaloPalette.CYAN, brightness: Int = 100): String =
        solid(colorArgb, brightness.coerceIn(40, 100), holdMs = 60_000)

    private fun empty(): String =
        """{"sceneId":0,"keepGoingOnScreenOff":false,"repeat":0,"combineName":"smriti_off","effects":[]}"""

    private fun solid(color: Int, brightness: Int, holdMs: Int): String {
        val hex = HaloPalette.rgbHex(color)
        val hold = holdMs.coerceAtLeast(800)
        val seg =
            """{"type":"strobe","lightName":"smriti","brightness":$brightness,"periodTime":$hold,"count":1,"totalTime":$hold,"isCountPriority":true,"firstLightColor":"$hex","secondLightColor":"$hex","thirdLightColor":"$hex","fourthLightColor":"$hex"}"""
        return wrap(seg, repeat = 1)
    }

    private fun slowPulse(
        color: Int,
        brightness: Int,
        periodMs: Int,
        count: Int,
        repeat: Int
    ): String = marquee(
        colors = listOf(color, color, color, color),
        brightness = brightness,
        periodMs = periodMs,
        subType = SUB_SLOW_PULSE,
        count = count,
        repeat = repeat
    )

    private fun chase(
        colors: List<Int>,
        brightness: Int,
        periodMs: Int,
        count: Int,
        repeat: Int
    ): String = marquee(
        colors = colors,
        brightness = brightness,
        periodMs = periodMs,
        subType = SUB_CHASE,
        count = count,
        repeat = repeat
    )

    private fun marquee(
        colors: List<Int>,
        brightness: Int,
        periodMs: Int,
        subType: Int,
        count: Int,
        repeat: Int
    ): String {
        fun hex(i: Int) = HaloPalette.rgbHex(colors[i % colors.size])
        val total = count * periodMs
        val seg =
            """{"type":"marquee","subType":$subType,"lightName":"smriti","brightness":$brightness,"periodTime":$periodMs,"count":$count,"totalTime":$total,"isCountPriority":true,"firstLightColor":"${hex(0)}","secondLightColor":"${hex(1)}","thirdLightColor":"${hex(2)}","fourthLightColor":"${hex(3)}"}"""
        return wrap(seg, repeat)
    }

    private fun wrap(segment: String, repeat: Int): String =
        """{"sceneId":0,"keepGoingOnScreenOff":true,"repeat":$repeat,"combineName":"smriti_aqua","effects":[$segment]}"""
}
