package com.aquascope.smriti.screenmind

import androidx.annotation.ColorRes
import com.aquascope.R

/**
 * All ScreenMind episode categories with display metadata.
 * Maps raw DB category strings → emoji, human label, and color.
 */
enum class ScreenMindCategory(
    val key: String,
    val emoji: String,
    val label: String,
    @ColorRes val colorRes: Int,
    @ColorRes val bgColorRes: Int
) {
    WATER_DIAGNOSTICS(
        key = "WATER_DIAGNOSTICS",
        emoji = "🌊",
        label = "Water Diagnostics",
        colorRes = R.color.cyan_bright,
        bgColorRes = R.color.status_ok_bg
    ),
    ACOUSTIC_SCAN(
        key = "ACOUSTIC_SCAN",
        emoji = "🔊",
        label = "Acoustic Scan",
        colorRes = R.color.cyan,
        bgColorRes = R.color.status_ok_bg
    ),
    PHYSICAL_SENSOR(
        key = "PHYSICAL_SENSOR",
        emoji = "📡",
        label = "Sensor Data",
        colorRes = R.color.cyan,
        bgColorRes = R.color.status_ok_bg
    ),
    BROWSER(
        key = "BROWSER",
        emoji = "🌐",
        label = "Browser",
        colorRes = R.color.warm_muted,
        bgColorRes = R.color.glass_panel
    ),
    COMMUNICATION(
        key = "COMMUNICATION",
        emoji = "💬",
        label = "Messages",
        colorRes = R.color.warm_white,
        bgColorRes = R.color.glass_panel
    ),
    RESEARCH(
        key = "RESEARCH",
        emoji = "📄",
        label = "Research",
        colorRes = R.color.warm_muted,
        bgColorRes = R.color.glass_panel
    ),
    HARDWARE_SETUP(
        key = "HARDWARE_SETUP",
        emoji = "⚙️",
        label = "Hardware",
        colorRes = R.color.amber,
        bgColorRes = R.color.amber_soft
    ),
    SYSTEM_UI(
        key = "SYSTEM_UI",
        emoji = "📱",
        label = "App Screen",
        colorRes = R.color.warm_faint,
        bgColorRes = R.color.glass_panel
    ),
    ON_DEVICE_SYNTHESIS(
        key = "ON_DEVICE_SYNTHESIS",
        emoji = "🧠",
        label = "AI Answer",
        colorRes = R.color.cyan_bright,
        bgColorRes = R.color.status_ok_bg
    ),
    MAPS_LOCATION(
        key = "MAPS_LOCATION",
        emoji = "📍",
        label = "Location",
        colorRes = R.color.warm_muted,
        bgColorRes = R.color.glass_panel
    ),
    MEDIA(
        key = "MEDIA",
        emoji = "🎵",
        label = "Media",
        colorRes = R.color.warm_muted,
        bgColorRes = R.color.glass_panel
    ),
    UNKNOWN(
        key = "UNKNOWN",
        emoji = "📋",
        label = "Captured",
        colorRes = R.color.warm_faint,
        bgColorRes = R.color.glass_panel
    );

    companion object {
        /**
         * Resolve a raw DB category string to a [ScreenMindCategory].
         * Case-insensitive; falls back to [UNKNOWN].
         */
        fun from(raw: String?): ScreenMindCategory {
            if (raw.isNullOrBlank()) return UNKNOWN
            val upper = raw.uppercase().trim()
            return values().firstOrNull { it.key == upper }
                ?: when {
                    upper.contains("WATER") || upper.contains("LEAK") -> WATER_DIAGNOSTICS
                    upper.contains("ACOUSTIC") -> ACOUSTIC_SCAN
                    upper.contains("SENSOR") || upper.contains("PHYSICAL") -> PHYSICAL_SENSOR
                    upper.contains("BROWSER") || upper.contains("WEB") || upper.contains("CHROME") -> BROWSER
                    upper.contains("CHAT") || upper.contains("MESSAGE") || upper.contains("COMM") -> COMMUNICATION
                    upper.contains("RESEARCH") || upper.contains("DOCUMENT") -> RESEARCH
                    upper.contains("HARDWARE") || upper.contains("SETUP") || upper.contains("CALIBR") -> HARDWARE_SETUP
                    upper.contains("SYNTHESIS") || upper.contains("AI") -> ON_DEVICE_SYNTHESIS
                    upper.contains("MAP") || upper.contains("LOCATION") || upper.contains("GPS") -> MAPS_LOCATION
                    upper.contains("MEDIA") || upper.contains("MUSIC") || upper.contains("VIDEO") -> MEDIA
                    else -> UNKNOWN
                }
        }

        /**
         * Classify OCR text into the best-matching category.
         * Weighted keyword scoring: higher weight = stronger signal.
         */
        fun classifyOcr(ocrText: String, appHint: String = ""): ScreenMindCategory {
            val combined = "$appHint $ocrText".lowercase()
            val scores = mutableMapOf<ScreenMindCategory, Int>()

            fun score(cat: ScreenMindCategory, vararg keywords: Pair<String, Int>) {
                var total = 0
                for ((kw, weight) in keywords) {
                    if (combined.contains(kw)) total += weight
                }
                if (total > 0) scores[cat] = (scores[cat] ?: 0) + total
            }

            score(WATER_DIAGNOSTICS,
                "leak" to 10, "pipe" to 8, "pressure" to 7, "flow velocity" to 9,
                "acoustic probe" to 10, "anomaly" to 7, "aquascope" to 9,
                "cavitation" to 9, "hiss" to 6, "water supply" to 8, "valve" to 6,
                "litre" to 5, "hydrophone" to 9, "bar" to 4
            )
            score(ACOUSTIC_SCAN,
                "acoustic" to 9, "frequency" to 7, "khz" to 8, "fft" to 8,
                "waveform" to 7, "amplitude" to 6, "sampling" to 6, "pcm" to 7,
                "dsp" to 7, "signal" to 5
            )
            score(PHYSICAL_SENSOR,
                "sensor" to 8, "calibrat" to 7, "hardware" to 6, "npu" to 7,
                "snapdragon" to 6, "iqoo" to 6, "dsp core" to 8, "adreno" to 6,
                "type-c" to 5
            )
            score(BROWSER,
                "chrome" to 9, "firefox" to 9, "http" to 7, "https" to 7,
                "www." to 7, ".com" to 5, ".org" to 5, "browser" to 8,
                "search" to 4, "url" to 6, "tab" to 4
            )
            score(COMMUNICATION,
                "whatsapp" to 9, "telegram" to 9, "message" to 7, "chat" to 7,
                "sent" to 4, "received" to 4, "reply" to 5, "inbox" to 7,
                "email" to 7, "gmail" to 8, "sms" to 8, "notification" to 5
            )
            score(RESEARCH,
                "report" to 6, "study" to 5, "research" to 7, "findings" to 6,
                "municipal" to 6, "infrastructure" to 6, "section" to 4,
                "document" to 5, "pdf" to 6, "journal" to 6
            )
            score(HARDWARE_SETUP,
                "calibrat" to 8, "setup" to 6, "config" to 6, "armed" to 7,
                "sampling rate" to 8, "channel" to 5, "firmware" to 7,
                "hardware" to 6, "attach" to 5
            )
            score(MAPS_LOCATION,
                "maps" to 9, "location" to 7, "navigate" to 7, "gps" to 8,
                "directions" to 7, "route" to 6, "address" to 5, "km" to 4
            )
            score(MEDIA,
                "spotify" to 9, "youtube" to 9, "music" to 7, "video" to 6,
                "podcast" to 7, "play" to 4, "pause" to 4, "stream" to 6
            )
            score(SYSTEM_UI,
                "settings" to 6, "battery" to 5, "wifi" to 5, "bluetooth" to 5,
                "notification" to 4, "home screen" to 7, "launcher" to 7
            )

            return scores.maxByOrNull { it.value }?.key
                ?.takeIf { (scores[it] ?: 0) >= 5 }
                ?: SYSTEM_UI
        }
    }
}
