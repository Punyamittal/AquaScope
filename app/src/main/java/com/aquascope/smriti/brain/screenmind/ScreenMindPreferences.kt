package com.aquascope.smriti.brain.screenmind

import android.content.Context

/**
 * ScreenMind prefs for Neural Core.
 * On-device analysis uses Ollama Gemma vision (same stack as System OCR).
 * Optional PC endpoint talks to a running ScreenMind desktop server.
 * @see <a href="https://github.com/ayushh0110/ScreenMind">ScreenMind</a>
 */
class ScreenMindPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When on, Capture / OCR use ScreenMind structured analysis. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        }

    /** Query a ScreenMind desktop instance on the LAN (port 7777). */
    var pcEnabled: Boolean
        get() = prefs.getBoolean(KEY_PC_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PC_ENABLED, value).commit()
        }

    var pcBaseUrl: String
        get() = prefs.getString(KEY_PC_URL, DEFAULT_PC_URL).orEmpty().ifBlank { DEFAULT_PC_URL }
        set(value) {
            prefs.edit().putString(KEY_PC_URL, normalizeBaseUrl(value)).commit()
        }

    /**
     * Server-side Hindi/Hinglish voice (indic-parler-tts) via the same PC backend.
     * Independent of [pcEnabled] so users can keep PC search/chat but skip the
     * slower TTS path — both must be on for network TTS to be attempted.
     */
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TTS_ENABLED, value).commit()
        }

    companion object {
        private const val PREFS = "smriti_screenmind"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PC_ENABLED = "pc_enabled"
        private const val KEY_PC_URL = "pc_base_url"
        private const val KEY_TTS_ENABLED = "tts_enabled"

        const val DEFAULT_PC_URL = "http://192.168.1.10:7777"

        fun normalizeBaseUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.isBlank()) return DEFAULT_PC_URL
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                s = "http://$s"
            }
            return s.trimEnd('/')
        }
    }
}
