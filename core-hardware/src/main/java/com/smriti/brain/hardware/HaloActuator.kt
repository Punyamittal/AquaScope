package com.smriti.brain.hardware

enum class HaloColor(val rgbHex: String) {
    CYAN("00E5FF"),
    CRIMSON("FF1A1A"),
    AMBER("FFB000"),
    EMERALD("00E676"),
    WHITE("FFFFFF")
}

class HaloActuator(packageName: String = "com.aquascope") {
    private val client = VivoHaloClient(packageName)

    fun pulse(color: HaloColor, periodMs: Int = 800, strobe: Boolean = false) {
        val json = if (strobe) {
            HaloJson.strobe(color.rgbHex, periodMs)
        } else {
            HaloJson.solid(color.rgbHex)
        }
        client.play(json)
    }
}
