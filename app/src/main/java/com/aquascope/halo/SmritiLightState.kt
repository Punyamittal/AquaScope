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
    /** Amber slow pulse — deviation below 50% */
    ANOMALY,
    /** Amber→red chase — deviation at or above 50% */
    HIGH_ANOMALY,
    /** Deeper red pulse — change persists or possible leak */
    PERSISTENT_ANOMALY,
    /** Restrained warm red — user-confirmed issue */
    CONFIRMED,
    /** Teal/green sweep — retrieving memory */
    MEMORY_RECALL,
    /** Neutral white, low — insufficient evidence (not danger) */
    UNKNOWN,
    /** Pulsing cyan — OCR / cognitive extraction */
    EXTRACTION,
    /** Vivid crimson — game kill / clutch buffer */
    GAME_KILL,
    /** Amber — health / medication verified */
    HEALTH_OK,
    /** Deep emerald — guardian ambient listening */
    GUARDIAN,
    /** Solid red — screen capture / Play buffer armed */
    SCREEN_RECORDING,
    /** Orange — voice / mic recording active */
    VOICE_RECORDING,
    /** Fast strobe white — fall / glass / alarm */
    EMERGENCY,
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
    /** Hardware LED cyan used by OriginOS / monster_halo presets. */
    const val CYAN_HW = 0xFF00E5FF.toInt()
    const val BLUE = 0xFF2E6FA8.toInt()
    const val PURPLE = 0xFF7B5EA7.toInt()
    const val WARM_WHITE = 0xFFF5F5F0.toInt()
    const val AMBER = 0xFFD99A3A.toInt()
    const val AMBER_DEEP = 0xFFC47A28.toInt()
    const val RED_RESTRAINED = 0xFFB84A3A.toInt()
    const val TEAL = 0xFF3AAFA0.toInt()
    const val NEUTRAL_WHITE = 0xFFD0D0D0.toInt()
    const val PHOSPHOR_CYAN = 0xFF00F0FF.toInt()
    const val CRIMSON = 0xFFFF003C.toInt()
    const val ORANGE = 0xFFFF8A00.toInt()
    const val AMBER_HEALTH = 0xFFFFB800.toInt()
    const val EMERALD = 0xFF00C853.toInt()
    const val STROBE_WHITE = 0xFFFFFFFF.toInt()
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
                state, AMBER, AMBER, 0.5f * b, MOTION_BREATHE, 2800, "Anomaly <50%"
            )
            SmritiLightState.HIGH_ANOMALY -> HaloRender(
                state, RED_RESTRAINED, AMBER_DEEP, 0.85f * b, MOTION_SWEEP, 480, "Anomaly ≥50%"
            )
            SmritiLightState.PERSISTENT_ANOMALY -> HaloRender(
                state, AMBER_DEEP, RED_RESTRAINED, 0.7f * b, MOTION_BREATHE, 1600, "Persistent"
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
            SmritiLightState.EXTRACTION -> HaloRender(
                state, PHOSPHOR_CYAN, CYAN_HW, 0.85f * b, MOTION_BREATHE, 520, "Extract"
            )
            SmritiLightState.GAME_KILL -> HaloRender(
                state, CRIMSON, RED_RESTRAINED, 0.9f * b, MOTION_SOLID, 280, "Kill"
            )
            SmritiLightState.HEALTH_OK -> HaloRender(
                state, AMBER_HEALTH, AMBER, 0.7f * b, MOTION_BREATHE, 1400, "Health"
            )
            SmritiLightState.GUARDIAN -> HaloRender(
                state, EMERALD, TEAL, 0.45f * b, MOTION_BREATHE, 2400, "Guardian"
            )
            SmritiLightState.SCREEN_RECORDING -> HaloRender(
                state, CRIMSON, RED_RESTRAINED, 0.85f * b, MOTION_BREATHE, 1400, "Screen rec"
            )
            SmritiLightState.VOICE_RECORDING -> HaloRender(
                state, ORANGE, AMBER, 0.75f * b, MOTION_BREATHE, 1600, "Voice rec"
            )
            SmritiLightState.EMERGENCY -> HaloRender(
                state, STROBE_WHITE, CRIMSON, 1f, MOTION_SOLID, 120, "Emergency"
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
