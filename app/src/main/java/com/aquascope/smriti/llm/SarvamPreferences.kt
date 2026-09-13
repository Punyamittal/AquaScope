package com.aquascope.smriti.llm

import android.content.Context

/**
 * Sarvam AI chat-completions settings — used to rewrite Qwen's already-grounded
 * answer into natural Hindi/Hinglish. Disabled automatically whenever no API
 * key is set, so this is safe to leave "enabled" by default.
 */
class SarvamPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim().ifBlank { DEFAULT_MODEL }).apply()

    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    fun maskedKey(): String {
        val k = apiKey
        return when {
            k.isBlank() -> ""
            k.length <= 8 -> "••••"
            else -> "${k.take(4)}…${k.takeLast(4)}"
        }
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    companion object {
        private const val PREFS = "smriti_sarvam"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"

        const val DEFAULT_MODEL = "sarvam-105b"
        const val BASE_URL = "https://api.sarvam.ai"

        /** Language codes this composer routes through Sarvam for final generation. */
        val INDIAN_LANGUAGE_TARGETS = setOf("hi", "hinglish")
    }
}
