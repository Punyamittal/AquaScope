package com.aquascope.halo

/**
 * Semantic Monster Halo states shared by screen UI and physical light.
 * Do not invent ad-hoc RGB — map only through this enum.
 */
enum class SmritiLightState {
    /** Soft cyan — home remembered normally */
    NORMAL,
    /** Blue→cyan→purple sweep — AquaScope sensing */
    SCANNING,
    /** Purple pulse — processing observation */
    PROCESSING,
    /** Soft white flash — episodic memory written */
    NEW_MEMORY,
    /** Amber slow pulse — something changed */
    ANOMALY,
    /** Deeper amber, slower — change persists */
    PERSISTENT_ANOMALY,
    /** Restrained warm red — user-confirmed issue */
    CONFIRMED,
    /** Teal/green sweep — retrieving memory */
    MEMORY_RECALL,
    /** Neutral white, low — insufficient evidence (not danger) */
    UNKNOWN,
    /** Lights off / night / privacy */
    OFF
}

data class HaloRender(
    val state: SmritiLightState,
    val colorArgb: Int,
    val secondaryArgb: Int,
    val brightness: Float,
    /** 0 = solid, 1 = breathe, 2 = sweep/chase */
    val motion: Int,
    val periodMs: Int,
    val label: String
)

object HaloPalette {
    // Semantic ARGB (alpha opaque for hardware RGB extraction)
    const val CYAN = 0xFF4AAFC2.toInt()
    const val CYAN_BRIGHT = 0xFF8EF2FF.toInt()
    const val CYAN_BLOOM = 0xFFC8FCFF.toInt()
    const val BLUE = 0xFF2E6FA8.toInt()
    const val PURPLE = 0xFF7B5EA7.toInt()
    const val WARM_WHITE = 0xFFF5F5F0.toInt()
    const val AMBER = 0xFFD99A3A.toInt()
    const val AMBER_DEEP = 0xFFC47A28.toInt()
    const val RED_RESTRAINED = 0xFFB84A3A.toInt()
    const val TEAL = 0xFF3AAFA0.toInt()
    const val NEUTRAL_WHITE = 0xFFD0D0D0.toInt()
    const val OFF = 0xFF000000.toInt()

    const val MOTION_SOLID = 0
    const val MOTION_BREATHE = 1
    const val MOTION_SWEEP = 2

    fun render(state: SmritiLightState, brightnessScale: Float): HaloRender {
        val b = brightnessScale.coerceIn(0.15f, 1f)
        return when (state) {
            SmritiLightState.NORMAL -> HaloRender(
                state, CYAN_BLOOM, CYAN_BRIGHT, (b * 1.2f).coerceIn(0.82f, 1f), MOTION_BREATHE, 900, "Normal"
            )
            SmritiLightState.SCANNING -> HaloRender(
                state, BLUE, CYAN, 0.55f * b, MOTION_SWEEP, 1600, "Scanning"
            )
            SmritiLightState.PROCESSING -> HaloRender(
                state, PURPLE, PURPLE, 0.5f * b, MOTION_BREATHE, 900, "Processing"
            )
            SmritiLightState.NEW_MEMORY -> HaloRender(
                state, WARM_WHITE, WARM_WHITE, 0.7f * b, MOTION_SOLID, 600, "Memory"
            )
            SmritiLightState.ANOMALY -> HaloRender(
                state, AMBER, AMBER, 0.5f * b, MOTION_BREATHE, 2800, "Anomaly"
            )
            SmritiLightState.PERSISTENT_ANOMALY -> HaloRender(
                state, AMBER_DEEP, AMBER, 0.55f * b, MOTION_BREATHE, 4200, "Persistent"
            )
            SmritiLightState.CONFIRMED -> HaloRender(
                state, RED_RESTRAINED, AMBER_DEEP, 0.45f * b, MOTION_BREATHE, 2200, "Confirmed"
            )
            SmritiLightState.MEMORY_RECALL -> HaloRender(
                state, TEAL, CYAN, 0.5f * b, MOTION_SWEEP, 1800, "Recall"
            )
            SmritiLightState.UNKNOWN -> HaloRender(
                state, NEUTRAL_WHITE, NEUTRAL_WHITE, 0.22f * b, MOTION_SOLID, 2000, "Unknown"
            )
            SmritiLightState.OFF -> HaloRender(
                state, OFF, OFF, 0f, MOTION_SOLID, 1000, "Off"
            )
        }
    }

    fun rgbHex(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        return String.format("%06X", rgb)
    }
}
