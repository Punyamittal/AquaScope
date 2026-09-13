package com.aquascope.smriti.llm

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sarvam AI chat-completions client — a final rewrite pass, not a reasoning step.
 * Qwen already grounded [answer] from memory facts; this only asks Sarvam to
 * restate it naturally in Hindi/Hinglish. Blocking call (not suspend): safe here
 * because [SmritiAnswerComposer.compose] is always invoked from Dispatchers.Default
 * (see SmritiCore.ask callers), never the main thread.
 */
class SarvamClient(
    private val prefs: SarvamPreferences,
    private val http: OkHttpClient = defaultClient()
) {

    companion object {
        private const val TAG = "SarvamClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
    }

    /** Returns null on any failure/disabled state — caller must keep [answer] as-is. */
    fun rewrite(question: String, answer: String, targetLanguage: String): String? {
        if (!prefs.enabled || !prefs.hasApiKey()) return null
        if (answer.isBlank()) return null

        val languageName = when (targetLanguage) {
            "hi" -> "Hindi (Devanagari script)"
            "hinglish" -> "Hinglish (Hindi written in the Latin/English alphabet, casual WhatsApp style)"
            else -> targetLanguage
        }
        val system =
            "Rewrite the ANSWER below into natural, conversational $languageName. " +
                "Keep every fact, name, number, and date exactly as given — do not add or remove information. " +
                "Keep it concise (under 120 words). Plain spoken sentences, no markdown, no labels. " +
                "Return only the rewritten answer."
        val user = "QUESTION: $question\n\nANSWER TO REWRITE:\n$answer"

        return runCatching {
            val body = JSONObject()
                .put("model", prefs.model)
                .put("temperature", 0.2)
                .put("max_tokens", 1024)
                // Reasoning-capable Sarvam models can spend the whole token budget on
                // internal <reasoning_content> before ever writing the real answer,
                // leaving `content` null and finish_reason="length". Keep reasoning
                // minimal since this is just a rewrite pass, not a hard problem.
                .put("reasoning_effort", "low")
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user))
                )
                .toString()
                .toRequestBody(JSON)

            val req = Request.Builder()
                .url("${SarvamPreferences.BASE_URL}/v1/chat/completions")
                .addHeader("api-subscription-key", prefs.apiKey)
                .post(body)
                .build()

            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Sarvam HTTP ${resp.code}: ${raw.take(300)}")
                    return@use null
                }
                val message = JSONObject(raw)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                // org.json's optString() returns the literal string "null" (not blank/empty)
                // when a JSON key is present but its value is JSON null — guard for it
                // explicitly, otherwise a null `content` field would display as "null".
                val content = message?.let { if (it.isNull("content")) null else it.optString("content") }
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                if (content == null) {
                    Log.w(TAG, "Sarvam returned no usable content. Body: ${raw.take(500)}")
                }
                content
            }
        }.onFailure { Log.w(TAG, "Sarvam rewrite failed", it) }.getOrNull()
    }
}
