package com.aquascope.smriti.screenmind

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data structures for ScreenMind REST API responses.
 */
data class ScreenMindStatus(
    val connected: Boolean,
    val serverUrl: String,
    val version: String = "1.0",
    val modelReady: Boolean = false,
    val message: String = ""
)

data class ScreenMindTimelineItem(
    val id: Long = 0,
    val timestamp: String = "",
    @SerializedName("app_name") val appName: String = "",
    val category: String = "",
    val summary: String = "",
    val mood: String = "",
    @SerializedName("screenshot_path") val screenshotPath: String = ""
)

data class ScreenMindSearchResult(
    val id: Long = 0,
    val timestamp: String = "",
    @SerializedName("app_name") val appName: String = "",
    val category: String = "",
    val summary: String = "",
    val score: Double = 0.0
)

/**
 * Lightweight asynchronous client connecting AquaScope to the local ScreenMind desktop server.
 * Default port: 7777
 */
class ScreenMindClient(
    private var baseUrl: String = "http://10.0.2.2:7777"
) {
    private val gson = Gson()

    fun updateBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }

    fun getBaseUrl(): String = baseUrl

    suspend fun checkHealth(): Result<ScreenMindStatus> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/settings")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            if (code in 200..299) {
                Result.success(
                    ScreenMindStatus(
                        connected = true,
                        serverUrl = baseUrl,
                        modelReady = true,
                        message = "ScreenMind desktop AI memory online"
                    )
                )
            } else {
                Result.failure(Exception("HTTP $code from ScreenMind"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun search(query: String, limit: Int = 10): Result<List<ScreenMindSearchResult>> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = URL("$baseUrl/api/search?q=$encoded&limit=$limit")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                    requestMethod = "GET"
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = gson.fromJson(body, JsonObject::class.java)
                val results = mutableListOf<ScreenMindSearchResult>()
                if (json.has("results") && json.get("results").isJsonArray) {
                    json.getAsJsonArray("results").forEach { elem ->
                        results.add(gson.fromJson(elem, ScreenMindSearchResult::class.java))
                    }
                }
                Result.success(results)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    suspend fun getTimeline(limit: Int = 20): Result<List<ScreenMindTimelineItem>> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/api/timeline?limit=$limit")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 10000
                    requestMethod = "GET"
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = gson.fromJson(body, JsonObject::class.java)
                val items = mutableListOf<ScreenMindTimelineItem>()
                if (json.has("items") && json.get("items").isJsonArray) {
                    json.getAsJsonArray("items").forEach { elem ->
                        items.add(gson.fromJson(elem, ScreenMindTimelineItem::class.java))
                    }
                }
                Result.success(items)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    suspend fun chat(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/chat")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 30000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JsonObject().apply {
                addProperty("message", prompt)
            }.toString()

            OutputStreamWriter(conn.outputStream).use { it.write(payload) }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = gson.fromJson(body, JsonObject::class.java)
            val answer = when {
                json.has("answer") -> json.get("answer").asString
                json.has("response") -> json.get("response").asString
                else -> body
            }
            Result.success(answer)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
