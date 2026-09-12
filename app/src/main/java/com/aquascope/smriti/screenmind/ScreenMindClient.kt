package com.aquascope.smriti.screenmind

import com.aquascope.smriti.model.PhysicalEvent
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
    val message: String = "",
    val syncedPhysicalEvents: Int = 0
)

data class ScreenMindTimelineItem(
    val id: Long = 0,
    val timestamp: String = "",
    @SerializedName("app_name") val appName: String = "",
    val category: String = "",
    val summary: String = "",
    val details: String = "",
    val mood: String = "",
    @SerializedName("screenshot_path") val screenshotPath: String = "",
    @SerializedName("screenshot_url") val screenshotUrl: String = ""
)

data class ScreenMindSearchResult(
    val id: String = "",
    val timestamp: String = "",
    @SerializedName("app_name") val appName: String = "",
    val category: String = "",
    val summary: String = "",
    val details: String = "",
    @SerializedName("relevance_score") val relevanceScore: Double = 0.0,
    @SerializedName("match_type") val matchType: String = "match",
    val score: Double = 0.0
)

/**
 * Robust asynchronous client connecting AquaScope to the ScreenMind desktop second-brain server.
 * Default port: 7777.
 */
class ScreenMindClient(
    private var baseUrl: String = "http://10.0.2.2:7777"
) {
    private val gson = Gson()

    fun updateBaseUrl(url: String) {
        baseUrl = url.trimEnd('/')
    }

    fun getBaseUrl(): String = baseUrl

    /**
     * Probes server health via /api/aquascope/status or fallback to /api/settings.
     */
    suspend fun checkHealth(): Result<ScreenMindStatus> = withContext(Dispatchers.IO) {
        try {
            // First attempt: /api/aquascope/status
            val aquaUrl = URL("$baseUrl/api/aquascope/status")
            val aquaConn = (aquaUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                requestMethod = "GET"
            }
            if (aquaConn.responseCode in 200..299) {
                val body = aquaConn.inputStream.bufferedReader().use { it.readText() }
                val json = gson.fromJson(body, JsonObject::class.java)
                val syncedCount = if (json.has("synced_physical_events")) json.get("synced_physical_events").asInt else 0
                val version = if (json.has("version")) json.get("version").asString else "1.0.0"
                return@withContext Result.success(
                    ScreenMindStatus(
                        connected = true,
                        serverUrl = baseUrl,
                        version = version,
                        modelReady = true,
                        message = "ScreenMind desktop AI memory online (AquaScope Bridge Active)",
                        syncedPhysicalEvents = syncedCount
                    )
                )
            }

            // Fallback attempt: /api/settings
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
                        version = "1.0.0",
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

    /**
     * Hybrid semantic and keyword search across desktop memory and synced physical scans.
     */
    suspend fun search(query: String, limit: Int = 15): Result<List<ScreenMindSearchResult>> =
        withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = URL("$baseUrl/api/search?q=$encoded&limit=$limit")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 12000
                    requestMethod = "GET"
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = gson.fromJson(body, JsonObject::class.java)
                val results = mutableListOf<ScreenMindSearchResult>()
                if (json.has("results") && json.get("results").isJsonArray) {
                    json.getAsJsonArray("results").forEach { elem ->
                        if (elem.isJsonObject) {
                            val obj = elem.asJsonObject
                            val id = if (obj.has("id")) obj.get("id").asString else "0"
                            val timestamp = if (obj.has("timestamp")) obj.get("timestamp").asString else ""
                            val appName = when {
                                obj.has("app_name") && !obj.get("app_name").isJsonNull -> obj.get("app_name").asString
                                obj.has("detected_app") && !obj.get("detected_app").isJsonNull -> obj.get("detected_app").asString
                                else -> "Desktop"
                            }
                            val category = if (obj.has("category") && !obj.get("category").isJsonNull) obj.get("category").asString else "activity"
                            val summary = if (obj.has("summary") && !obj.get("summary").isJsonNull) obj.get("summary").asString else ""
                            val details = if (obj.has("details") && !obj.get("details").isJsonNull) obj.get("details").asString else ""
                            val relScore = if (obj.has("relevance_score")) obj.get("relevance_score").asDouble else 0.0
                            val matchType = if (obj.has("match_type")) obj.get("match_type").asString else "keyword"

                            results.add(
                                ScreenMindSearchResult(
                                    id = id,
                                    timestamp = timestamp,
                                    appName = appName,
                                    category = category,
                                    summary = summary,
                                    details = details,
                                    relevanceScore = relScore,
                                    matchType = matchType,
                                    score = relScore
                                )
                            )
                        }
                    }
                }
                Result.success(results)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    /**
     * Retrieves desktop activity timeline.
     * Supports both ScreenMind's native "activities" schema and legacy "items" schema.
     */
    suspend fun getTimeline(limit: Int = 30): Result<List<ScreenMindTimelineItem>> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/api/timeline?limit=$limit")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 12000
                    requestMethod = "GET"
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = gson.fromJson(body, JsonObject::class.java)
                val items = mutableListOf<ScreenMindTimelineItem>()

                val array = when {
                    json.has("activities") && json.get("activities").isJsonArray -> json.getAsJsonArray("activities")
                    json.has("items") && json.get("items").isJsonArray -> json.getAsJsonArray("items")
                    else -> null
                }

                array?.forEach { elem ->
                    if (elem.isJsonObject) {
                        val obj = elem.asJsonObject
                        val id = if (obj.has("id")) obj.get("id").asLong else 0L
                        val timestamp = if (obj.has("timestamp")) obj.get("timestamp").asString else ""
                        val appName = when {
                            obj.has("app_name") && !obj.get("app_name").isJsonNull -> obj.get("app_name").asString
                            obj.has("detected_app") && !obj.get("detected_app").isJsonNull -> obj.get("detected_app").asString
                            else -> "Desktop Activity"
                        }
                        val category = if (obj.has("category") && !obj.get("category").isJsonNull) obj.get("category").asString else "general"
                        val summary = if (obj.has("summary") && !obj.get("summary").isJsonNull) obj.get("summary").asString else ""
                        val details = if (obj.has("details") && !obj.get("details").isJsonNull) obj.get("details").asString else ""
                        val mood = if (obj.has("mood") && !obj.get("mood").isJsonNull) obj.get("mood").asString else ""
                        val ssPath = if (obj.has("screenshot_path") && !obj.get("screenshot_path").isJsonNull) obj.get("screenshot_path").asString else ""
                        val ssUrl = if (obj.has("screenshot_url") && !obj.get("screenshot_url").isJsonNull) obj.get("screenshot_url").asString else ""

                        items.add(
                            ScreenMindTimelineItem(
                                id = id,
                                timestamp = timestamp,
                                appName = appName,
                                category = category,
                                summary = summary,
                                details = details,
                                mood = mood,
                                screenshotPath = ssPath,
                                screenshotUrl = ssUrl
                            )
                        )
                    }
                }
                Result.success(items)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

    /**
     * Ask a question to desktop memory with Gemma 4.
     * Supports both standard JSON responses and SSE streaming responses.
     */
    suspend fun chat(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/chat")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 40000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            val payload = JsonObject().apply {
                addProperty("question", prompt)
                addProperty("message", prompt)
                addProperty("stream", false)
            }.toString()

            OutputStreamWriter(conn.outputStream).use { it.write(payload) }

            val responseCode = conn.responseCode
            val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = inputStream.bufferedReader().use { it.readText() }

            if (responseCode !in 200..299) {
                return@withContext Result.failure(Exception("HTTP $responseCode: $body"))
            }

            // Attempt JSON parsing
            try {
                val json = gson.fromJson(body, JsonObject::class.java)
                val answer = when {
                    json.has("answer") -> json.get("answer").asString
                    json.has("response") -> json.get("response").asString
                    else -> null
                }
                if (!answer.isNullOrBlank()) {
                    return@withContext Result.success(answer)
                }
            } catch (e: Exception) {
                // Not standard JSON, parse as SSE stream lines
            }

            // Fallback: parse SSE stream lines (data: {"type": "answer", "answer": "..."})
            var finalAnswer = ""
            body.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("data:")) {
                    val rawData = trimmed.removePrefix("data:").trim()
                    try {
                        val parsed = gson.fromJson(rawData, JsonObject::class.java)
                        if (parsed.has("type") && parsed.get("type").asString == "answer") {
                            finalAnswer = parsed.get("answer").asString
                        }
                    } catch (_: Exception) {}
                }
            }

            if (finalAnswer.isNotBlank()) {
                Result.success(finalAnswer)
            } else {
                Result.success(body.trim())
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Push a physical acoustic scan event to ScreenMind's desktop memory.
     */
    suspend fun syncPhysicalEvent(event: PhysicalEvent): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/api/aquascope/event")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 8000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            val payload = JsonObject().apply {
                addProperty("id", event.id)
                addProperty("timestamp_ms", event.timestampMs)
                addProperty("location_id", event.locationId)
                addProperty("location_label", event.locationLabel)
                addProperty("object_id", event.objectId)
                addProperty("object_label", event.objectLabel)
                addProperty("event_type", event.eventType.name)
                addProperty("anomaly_score", event.anomalyScore)
                addProperty("confidence", event.confidence)
                event.baselineId?.let { addProperty("baseline_id", it) }
                addProperty("summary", event.summary)
                addProperty("evidence_state", event.evidenceState.name)
                addProperty("source", event.source)

                if (event.features.isNotEmpty()) {
                    val featObj = JsonObject()
                    event.features.forEach { (k, v) -> featObj.addProperty(k, v) }
                    add("features", featObj)
                }

                if (event.evidenceNotes.isNotEmpty()) {
                    val notesArray = com.google.gson.JsonArray()
                    event.evidenceNotes.forEach { notesArray.add(it) }
                    add("evidence_notes", notesArray)
                }
            }.toString()

            OutputStreamWriter(conn.outputStream).use { it.write(payload) }

            val code = conn.responseCode
            if (code in 200..299) {
                Result.success(true)
            } else {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Result.failure(Exception("HTTP $code: $err"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Syncs a batch of recent physical acoustic events to desktop memory.
     */
    suspend fun syncRecentScans(events: List<PhysicalEvent>): Result<Int> = withContext(Dispatchers.IO) {
        var successCount = 0
        for (event in events) {
            val res = syncPhysicalEvent(event)
            if (res.isSuccess) successCount++
        }
        Result.success(successCount)
    }
}
