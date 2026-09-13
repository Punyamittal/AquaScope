package com.aquascope.smriti.tts

import com.aquascope.smriti.brain.screenmind.ScreenMindPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Calls the screenmind desktop PC backend's /api/tts/speak endpoint
 * (AI4Bharat indic-parler-tts, server-side — this model cannot run on the phone).
 * Same PC/base-URL as [com.aquascope.smriti.brain.screenmind.ScreenMindPcClient] —
 * just a different route, no separate "PC URL" setting.
 */
class IndicTtsClient(
    private val prefs: ScreenMindPreferences,
    private val http: OkHttpClient = defaultClient()
) {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS) // CPU synthesis can be slow — caller adds its own hard timeout too
                .build()
    }

    /** Returns raw WAV bytes on success. Caller should wrap with a timeout and fall back on failure. */
    suspend fun speak(text: String, language: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            require(prefs.pcEnabled && prefs.ttsEnabled) { "Server TTS is off" }
            require(text.isNotBlank()) { "Empty text" }
            val base = ScreenMindPreferences.normalizeBaseUrl(prefs.pcBaseUrl)
            val body = JSONObject()
                .put("text", text)
                .put("language", language)
                .toString()
                .toRequestBody(JSON)
            val req = Request.Builder()
                .url("$base/api/tts/speak")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("TTS HTTP ${resp.code}")
                resp.body?.bytes() ?: error("Empty TTS response")
            }
        }
    }
}
