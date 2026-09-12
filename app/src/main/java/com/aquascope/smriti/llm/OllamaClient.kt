package com.aquascope.smriti.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin Ollama / OpenAI-compatible client for speech translation and Gemma 4 vision OCR.
 * Tries Ollama `/api/chat` first, then `/v1/chat/completions` for text.
 */
class OllamaClient(
    private val prefs: OllamaPreferences,
    private val http: OkHttpClient = defaultClient()
) {

    companion object {
        private const val TAG = "OllamaClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
    }

    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = OllamaPreferences.normalizeBaseUrl(prefs.baseUrl)
            val req = Request.Builder().url("$base/api/tags").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Ollama HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                val names = parseModelNames(body)
                val wanted = prefs.model.trim().ifBlank { OllamaPreferences.DEFAULT_MODEL }
                val hasWanted = names.any { it.equals(wanted, true) || it.startsWith("$wanted:", true) }
                val qwenHint = names.firstOrNull { it.contains("qwen", ignoreCase = true) }
                val ocrWanted = prefs.ocrModel.trim().ifBlank { OllamaPreferences.DEFAULT_OCR_MODEL }
                val hasOcr = names.any {
                    it.equals(ocrWanted, true) || it.startsWith("$ocrWanted:", true) ||
                        (ocrWanted.startsWith("gemma4") && it.startsWith("gemma4", true))
                }
                buildString {
                    append("Reachable")
                    if (names.isEmpty()) {
                        append(" · no models pulled yet")
                    } else {
                        append(" · ${names.size} model(s)")
                    }
                    append("\nTranslate: $wanted")
                    when {
                        hasWanted -> append(" ✓")
                        qwenHint != null -> append(" (missing — try: ollama pull $wanted)\nOn server: $qwenHint")
                        else -> append(" (missing — run: ollama pull $wanted)")
                    }
                    append("\nOCR: $ocrWanted")
                    when {
                        !prefs.ocrEnabled -> append(" (off)")
                        hasOcr -> append(" ✓")
                        else -> append(" (pull: ollama pull $ocrWanted)")
                    }
                }
            }
        }
    }

    /** Names from `/api/tags` (e.g. qwen2.5:1.5b). */
    suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val base = OllamaPreferences.normalizeBaseUrl(prefs.baseUrl)
            val req = Request.Builder().url("$base/api/tags").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Ollama HTTP ${resp.code}")
                parseModelNames(resp.body?.string().orEmpty())
            }
        }
    }

    /**
     * If prefs model is missing on the server, pick a Qwen tag already pulled
     * (same family as in-app Ask), else the first available model.
     */
    suspend fun resolveModelTag(): String = withContext(Dispatchers.IO) {
        val wanted = prefs.model.trim().ifBlank { OllamaPreferences.DEFAULT_MODEL }
        val names = listModels().getOrNull().orEmpty()
        if (names.isEmpty()) return@withContext wanted
        if (names.any { it.equals(wanted, true) }) return@withContext wanted
        names.firstOrNull { it.startsWith("qwen2.5:1.5", true) }
            ?: names.firstOrNull { it.contains("qwen2.5", true) }
            ?: names.firstOrNull { it.contains("qwen", true) }
            ?: names.first()
    }

    suspend fun translateToEnglish(text: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "Empty speech" }
            if (SpeechLanguage.isLikelyEnglish(trimmed)) return@runCatching trimmed

            val prompt =
                "Translate the following speech transcript into clear English. " +
                    "Return ONLY the English translation, no quotes or explanation.\n\n$trimmed"

            val base = OllamaPreferences.normalizeBaseUrl(prefs.baseUrl)
            val model = resolveModelTagSync(base)

            translateViaOllamaChat(base, model, prompt)
                ?: translateViaOpenAi(base, model, prompt)
                ?: error("Ollama did not return a translation. Is $model pulled? Try: ollama pull ${OllamaPreferences.DEFAULT_MODEL}")
        }
    }

    /**
     * Multimodal OCR with Gemma 4 (or other vision tags). Sends JPEG base64 via `/api/chat` images[].
     */
    suspend fun ocrFromJpegBase64(jpegBase64: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(jpegBase64.isNotBlank()) { "Empty image" }
            require(prefs.ocrEnabled) { "Gemma OCR is off — enable it in System → Local model" }
            val base = OllamaPreferences.normalizeBaseUrl(prefs.baseUrl)
            val model = resolveOcrModelTagSync(base)
            val prompt =
                "You are an OCR engine. Read ALL visible text in this phone screenshot or photo. " +
                    "Return ONLY the readable text in reading order. Keep numbers, labels, and UI strings. " +
                    "Do not describe the image. Do not invent text. If almost no text is visible, return an empty reply."

            ocrViaOllamaChat(base, model, prompt, jpegBase64)
                ?: error("Gemma OCR returned nothing. Is $model pulled? Try: ollama pull ${OllamaPreferences.DEFAULT_OCR_MODEL}")
        }
    }

    /**
     * ScreenMind-style structured vision analysis (JSON). Uses the OCR/vision model.
     */
    suspend fun screenMindAnalyze(
        jpegBase64: String,
        ocrContext: String = ""
    ): Result<com.aquascope.smriti.brain.screenmind.ScreenMindRecord> = withContext(Dispatchers.IO) {
        runCatching {
            require(jpegBase64.isNotBlank()) { "Empty image" }
            require(prefs.ocrEnabled) { "Enable Gemma OCR in System for ScreenMind vision" }
            val base = OllamaPreferences.normalizeBaseUrl(prefs.baseUrl)
            val model = resolveOcrModelTagSync(base)
            val prompt = buildString {
                append(
                    """Analyze this phone screenshot. Return ONLY a JSON object:
{"app_name":"main app visible","activity_category":"ONE of: coding, browsing, communication, writing, design, media, terminal, meeting, idle, gaming, other","activity_summary":"one specific sentence about what the user is doing","detailed_context":"2-3 sentences with specifics","visible_text_snippets":["up to 8 key text items"],"mood":"ONE of: productive, distracted, collaborative, learning, neutral","confidence":0.85,"scene_description":"DETAILED inventory of everything visible"}
Rules: activity_category MUST be one of the listed values. Do NOT invent unread text."""
                )
                if (ocrContext.isNotBlank()) {
                    append("\n\nOCR context (may be partial):\n")
                    append(ocrContext.take(1_200))
                }
            }
            val raw = ocrViaOllamaChat(base, model, prompt, jpegBase64)
                ?: error("ScreenMind vision returned nothing from $model")
            com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer.parseJson(raw, ocrContext)
                ?: error("ScreenMind JSON parse failed")
        }
    }

    /**
     * Gemma 4 vision summary for PEACE / Capture highlight clips — categorize + summarize for main memory.
     */
    suspend fun summarizeHighlightClip(
        jpegBase64: String,
        ocrContext: String = "",
        triggerReason: String = "clip"
    ): Result<com.aquascope.smriti.brain.screenmind.ScreenMindRecord> = withContext(Dispatchers.IO) {
        runCatching {
            require(jpegBase64.isNotBlank()) { "Empty image" }
            require(prefs.ocrEnabled) { "Enable Gemma OCR in System → Local model for clip summaries" }
            val base = OllamaPreferences.normalizeBaseUrl(prefs.baseUrl)
            val model = resolveOcrModelTagSync(base)
            val prompt = buildString {
                append(
                    """This is a frame from an automatic gameplay/highlight clip (PEACE-style kill-feed capture).
Trigger: $triggerReason
Return ONLY a JSON object:
{"app_name":"game or app name","activity_category":"ONE of: gaming, media, browsing, communication, other","activity_summary":"one clear sentence summarizing this highlight for memory search","detailed_context":"2-3 sentences: what happened, who/what is on screen, why it was clipped","visible_text_snippets":["up to 8 UI / kill-feed / score strings"],"mood":"ONE of: productive, distracted, collaborative, learning, neutral","confidence":0.85,"scene_description":"inventory of HUD, kill feed, score, characters"}
Prefer activity_category=gaming when this looks like a match, kill, victory, or scoreboard. Be specific so Ask can find this clip later. Do NOT invent unread text."""
                )
                if (ocrContext.isNotBlank()) {
                    append("\n\nOCR context:\n")
                    append(ocrContext.take(1_500))
                }
            }
            val raw = ocrViaOllamaChat(base, model, prompt, jpegBase64)
                ?: error("Gemma clip summary returned nothing from $model")
            com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer.parseJson(raw, ocrContext)
                ?: error("Gemma clip JSON parse failed")
        }
    }

    private fun resolveOcrModelTagSync(base: String): String {
        val wanted = prefs.ocrModel.trim().ifBlank { OllamaPreferences.DEFAULT_OCR_MODEL }
        val names = runCatching {
            val req = Request.Builder().url("$base/api/tags").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) emptyList()
                else parseModelNames(resp.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())
        if (names.isEmpty()) return wanted
        if (names.any { it.equals(wanted, true) }) return wanted
        return names.firstOrNull { it.startsWith("gemma4:e4b", true) }
            ?: names.firstOrNull { it.startsWith("gemma4:e2b", true) }
            ?: names.firstOrNull { it.startsWith("gemma4", true) }
            ?: names.firstOrNull { it.startsWith("gemma3:4b", true) }
            ?: names.firstOrNull { it.startsWith("gemma3", true) }
            ?: wanted
    }

    private fun ocrViaOllamaChat(
        base: String,
        model: String,
        prompt: String,
        jpegBase64: String
    ): String? {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", prompt)
                        .put("images", JSONArray().put(jpegBase64))
                )
            )
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url("$base/api/chat")
            .post(body)
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "OCR HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
                    return@use null
                }
                val root = JSONObject(resp.body?.string().orEmpty())
                root.optJSONObject("message")?.optString("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun resolveModelTagSync(base: String): String {
        val wanted = prefs.model.trim().ifBlank { OllamaPreferences.DEFAULT_MODEL }
        val names = runCatching {
            val req = Request.Builder().url("$base/api/tags").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) emptyList()
                else parseModelNames(resp.body?.string().orEmpty())
            }
        }.getOrDefault(emptyList())
        if (names.isEmpty()) return wanted
        if (names.any { it.equals(wanted, true) }) return wanted
        return names.firstOrNull { it.startsWith("qwen2.5:1.5", true) }
            ?: names.firstOrNull { it.contains("qwen2.5", true) }
            ?: names.firstOrNull { it.contains("qwen", true) }
            ?: names.first()
    }

    private fun parseModelNames(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONArray("models") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val name = arr.optJSONObject(i)?.optString("name").orEmpty().trim()
                    if (name.isNotBlank()) add(name)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun translateViaOllamaChat(base: String, model: String, prompt: String): String? {
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                )
            )
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url("$base/api/chat")
            .post(body)
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = JSONObject(resp.body?.string().orEmpty())
                root.optJSONObject("message")?.optString("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun translateViaOpenAi(base: String, model: String, prompt: String): String? {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                )
            )
            .toString()
            .toRequestBody(JSON)
        val req = Request.Builder()
            .url("$base/v1/chat/completions")
            .post(body)
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val root = JSONObject(resp.body?.string().orEmpty())
                root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}

/**
 * Lightweight script / ASCII heuristics — no network required.
 */
object SpeechLanguage {

    fun isLikelyEnglish(text: String): Boolean {
        val s = text.trim()
        if (s.isEmpty()) return true
        var letters = 0
        var latin = 0
        var nonLatin = 0
        for (ch in s) {
            if (!ch.isLetter()) continue
            letters++
            val block = Character.UnicodeBlock.of(ch)
            when (block) {
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A,
                Character.UnicodeBlock.LATIN_EXTENDED_B,
                Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL -> latin++
                else -> nonLatin++
            }
        }
        if (letters == 0) return true
        if (nonLatin > letters / 5) return false
        // Mostly Latin: treat as English unless clearly another Latin language later.
        return latin >= letters * 4 / 5
    }
}
