package com.aquascope.smriti.brain.screenmind

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
 * Optional client for a desktop ScreenMind server (http://host:7777).
 * @see <a href="https://github.com/ayushh0110/ScreenMind">ScreenMind API</a>
 */
class ScreenMindPcClient(
    private val prefs: ScreenMindPreferences
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, limit: Int = 8): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(prefs.pcEnabled) { "PC ScreenMind is off" }
            require(query.isNotBlank()) { "Empty query" }
            val base = ScreenMindPreferences.normalizeBaseUrl(prefs.pcBaseUrl)
            val url = "$base/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=$limit"
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("ScreenMind search HTTP ${resp.code}")
                formatSearch(resp.body?.string().orEmpty())
            }
        }
    }

    suspend fun recentActivity(limit: Int = 10): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(prefs.pcEnabled) { "PC ScreenMind is off" }
            val base = ScreenMindPreferences.normalizeBaseUrl(prefs.pcBaseUrl)
            val req = Request.Builder().url("$base/api/status").get().build()
            // Prefer timeline for today if available
            val timelineReq = Request.Builder()
                .url("$base/api/timeline")
                .get()
                .build()
            http.newCall(timelineReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Fall back to status ping so UI can show connectivity
                    http.newCall(req).execute().use { st ->
                        if (!st.isSuccessful) error("ScreenMind offline HTTP ${st.code}")
                        return@runCatching "PC ScreenMind is online at $base"
                    }
                }
                formatTimeline(resp.body?.string().orEmpty(), limit)
            }
        }
    }

    suspend fun chat(message: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(prefs.pcEnabled) { "PC ScreenMind is off" }
            require(message.isNotBlank()) { "Empty message" }
            val base = ScreenMindPreferences.normalizeBaseUrl(prefs.pcBaseUrl)
            val body = JSONObject()
                .put("message", message)
                .toString()
                .toRequestBody(JSON)
            val req = Request.Builder()
                .url("$base/api/chat")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("ScreenMind chat HTTP ${resp.code}")
                val raw = resp.body?.string().orEmpty()
                // SSE or JSON — take readable text
                extractChatText(raw).ifBlank { raw.take(2_000) }
            }
        }
    }

    suspend fun ping(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = ScreenMindPreferences.normalizeBaseUrl(prefs.pcBaseUrl)
            val req = Request.Builder().url("$base/api/status").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                "PC ScreenMind online · $base"
            }
        }
    }

    private fun formatSearch(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            if (arr.length() == 0) return "No PC ScreenMind matches."
            return buildString {
                append("PC ScreenMind search:\n")
                for (i in 0 until minOf(arr.length(), 8)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val summary = o.optString("activity_summary")
                        .ifBlank { o.optString("summary") }
                        .ifBlank { o.optString("app_name") }
                    val app = o.optString("app_name")
                    append("• ")
                    if (app.isNotBlank()) append("[$app] ")
                    append(summary.take(160))
                    append('\n')
                }
            }.trim()
        }
        if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            val results = root.optJSONArray("results") ?: root.optJSONArray("activities")
            if (results != null) return formatSearch(results.toString())
        }
        return trimmed.take(1_500).ifBlank { "No PC ScreenMind matches." }
    }

    private fun formatTimeline(raw: String, limit: Int): String {
        val trimmed = raw.trim()
        val arr = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                root.optJSONArray("activities")
                    ?: root.optJSONArray("items")
                    ?: root.optJSONArray("timeline")
                    ?: return "PC ScreenMind timeline empty."
            }
            else -> return trimmed.take(1_200)
        }
        if (arr.length() == 0) return "PC ScreenMind timeline empty."
        return buildString {
            append("PC ScreenMind recent:\n")
            for (i in 0 until minOf(arr.length(), limit)) {
                val o = arr.optJSONObject(i) ?: continue
                val summary = o.optString("activity_summary")
                    .ifBlank { o.optString("summary") }
                    .ifBlank { o.optString("app_name", "activity") }
                append("• ")
                append(summary.take(160))
                append('\n')
            }
        }.trim()
    }

    private fun extractChatText(raw: String): String {
        // Non-streaming JSON
        runCatching {
            val o = JSONObject(raw)
            o.optString("reply")
                .ifBlank { o.optString("answer") }
                .ifBlank { o.optString("content") }
                .ifBlank { o.optJSONObject("message")?.optString("content").orEmpty() }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        // SSE lines: data: {...}
        val sb = StringBuilder()
        raw.lineSequence().forEach { line ->
            val data = line.removePrefix("data:").trim()
            if (data.isBlank() || data == "[DONE]") return@forEach
            runCatching {
                val o = JSONObject(data)
                val piece = o.optString("content")
                    .ifBlank { o.optString("token") }
                    .ifBlank { o.optJSONObject("message")?.optString("content").orEmpty() }
                if (piece.isNotBlank()) sb.append(piece)
            }
        }
        return sb.toString().trim()
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val TAG = "ScreenMindPc"
    }
}
