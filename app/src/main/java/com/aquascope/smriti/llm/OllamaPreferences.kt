package com.aquascope.smriti.llm

import android.content.Context

/**
 * Ollama endpoint for:
 * - translating non-English speech (text model, default Qwen)
 * - multimodal OCR via Gemma 4 vision (`gemma4:e4b`)
 *
 * Typical setups: Ollama on a PC (`http://LAN-IP:11434`).
 * Pull OCR model: `ollama pull gemma4:e4b`
 */
class OllamaPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        }

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL }
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, normalizeBaseUrl(value)).commit()
        }

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
        set(value) {
            prefs.edit().putString(KEY_MODEL, value.trim().ifBlank { DEFAULT_MODEL }).commit()
        }

    /** When true, Capture / gallery OCR prefers Gemma 4 vision over ML Kit alone. */
    var ocrEnabled: Boolean
        get() = prefs.getBoolean(KEY_OCR_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OCR_ENABLED, value).commit()
        }

    var ocrModel: String
        get() = prefs.getString(KEY_OCR_MODEL, DEFAULT_OCR_MODEL).orEmpty().ifBlank { DEFAULT_OCR_MODEL }
        set(value) {
            prefs.edit().putString(KEY_OCR_MODEL, value.trim().ifBlank { DEFAULT_OCR_MODEL }).commit()
        }

    companion object {
        private const val PREFS = "smriti_ollama"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_OCR_ENABLED = "ocr_enabled"
        private const val KEY_OCR_MODEL = "ocr_model"

        const val DEFAULT_BASE_URL = "http://127.0.0.1:11434"

        /** Speech translation — same family as on-device Ask Qwen. */
        const val DEFAULT_MODEL = "qwen2.5:1.5b"

        /** Multimodal OCR — Gemma 4 E4B vision. */
        const val DEFAULT_OCR_MODEL = "gemma4:e4b"

        data class SuggestedModel(
            val ollamaTag: String,
            val label: String,
            val matchesOnDeviceAsk: Boolean = false
        )

        val suggestedModels: List<SuggestedModel> = listOf(
            SuggestedModel("qwen2.5:1.5b", "Qwen 2.5 1.5B", matchesOnDeviceAsk = true),
            SuggestedModel("qwen2.5:3b", "Qwen 2.5 3B"),
            SuggestedModel("qwen2.5", "Qwen 2.5 (latest)"),
            SuggestedModel("gemma2:2b", "Gemma 2 2B"),
            SuggestedModel("llama3.2", "Llama 3.2")
        )

        data class SuggestedOcrModel(
            val ollamaTag: String,
            val label: String
        )

        val suggestedOcrModels: List<SuggestedOcrModel> = listOf(
            SuggestedOcrModel("gemma4:e4b", "Gemma 4 E4B"),
            SuggestedOcrModel("gemma4:e2b", "Gemma 4 E2B"),
            SuggestedOcrModel("gemma4:12b", "Gemma 4 12B"),
            SuggestedOcrModel("gemma3:4b", "Gemma 3 4B")
        )

        fun normalizeBaseUrl(raw: String?): String {
            var url = raw?.trim().orEmpty().trimEnd('/')
            if (url.isBlank()) return DEFAULT_BASE_URL
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://$url"
            }
            return url.trimEnd('/')
        }
    }
}
