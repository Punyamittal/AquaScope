package com.aquascope.smriti.brain.screenmind

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.brain.LocalOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Port of ScreenMind's screenshot → structured activity pipeline for Neural Core.
 * Uses ML Kit OCR + optional Gemma 4 vision (via Ollama), same prompt shape as
 * https://github.com/ayushh0110/ScreenMind.
 */
object ScreenMindAnalyzer {

    private const val TAG = "ScreenMind"

    private val categories = setOf(
        "coding", "browsing", "communication", "writing", "design",
        "media", "terminal", "meeting", "idle", "other", "gaming"
    )
    private val moods = setOf(
        "productive", "distracted", "collaborative", "learning", "neutral"
    )

    suspend fun analyze(
        context: Context,
        bitmap: Bitmap,
        reason: String = "capture"
    ): ScreenMindRecord = withContext(Dispatchers.IO) {
        val ocrText = runCatching { LocalOcr.readBitmap(context, bitmap).trim() }
            .getOrDefault("")
        val smriti = SmritiCore.get(context)
        val prefs = ScreenMindPreferences(context)
        if (!prefs.enabled) {
            return@withContext fromOcrOnly(ocrText, reason)
        }
        val gemmaOn = smriti.ollamaPrefs.ocrEnabled
        if (!gemmaOn) {
            return@withContext fromOcrOnly(ocrText, reason)
        }
        val jpeg = bitmapToJpegBase64(bitmap) ?: return@withContext fromOcrOnly(ocrText, reason)
        val vision = smriti.ollamaClient.screenMindAnalyze(jpeg, ocrText).getOrNull()
        if (vision != null) {
            Log.i(TAG, "Gemma ScreenMind ok app=${vision.appName} cat=${vision.category}")
            return@withContext vision.copy(
                visibleText = vision.visibleText.ifBlank { ocrText }
            )
        }
        fromOcrOnly(ocrText, reason)
    }

    suspend fun analyzeFrames(
        context: Context,
        frames: List<Bitmap>,
        reason: String
    ): ScreenMindRecord {
        if (frames.isEmpty()) return fromOcrOnly("", reason)
        // Analyze up to last 3 frames; keep the strongest app/game identity.
        val analyzed = frames.takeLast(3).map { bmp -> analyze(context, bmp, reason) }
        val best = analyzed.maxByOrNull { scoreRecord(it) } ?: analyzed.last()
        val mergedOcr = analyzed.map { it.visibleText }.filter { it.isNotBlank() }
            .joinToString("\n")
            .lines()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
            .joinToString("\n")
        return best.copy(
            visibleText = mergedOcr.ifBlank { best.visibleText },
            snippets = (best.snippets + analyzed.flatMap { it.snippets }).distinct().take(12)
        )
    }

    private fun scoreRecord(r: ScreenMindRecord): Int {
        var s = 0
        if (r.appName !in listOf("Phone UI", "unknown", "")) s += 3
        if (r.category == "gaming") s += 2
        if (r.visibleText.isNotBlank()) s += 1
        if (r.summary.isNotBlank()) s += 1
        s += (r.confidence * 10).toInt()
        return s
    }

    fun fromOcrOnly(ocrText: String, reason: String = "ocr"): ScreenMindRecord {
        val category = heuristicCategory(ocrText)
        val app = heuristicApp(ocrText)
        val summary = when {
            ocrText.isBlank() -> "Screen clip ($reason) with little readable text"
            else -> "On-screen text captured ($reason)"
        }
        return ScreenMindRecord(
            appName = app,
            category = category,
            summary = summary,
            detailedContext = ocrText.take(400),
            mood = "neutral",
            confidence = if (ocrText.isBlank()) 0.2 else 0.55,
            sceneDescription = ocrText.take(800),
            visibleText = ocrText,
            snippets = ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(8)
        )
    }

    fun parseJson(raw: String, ocrFallback: String = ""): ScreenMindRecord? {
        val json = extractJsonObject(raw) ?: return null
        return try {
            val snippets = json.optJSONArray("visible_text_snippets")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it)?.takeIf { s -> s.isNotBlank() } }
            }.orEmpty()
            val cat = normalizeCategory(json.optString("activity_category", "other"))
            val mood = normalizeMood(json.optString("mood", "neutral"))
            val visible = snippets.joinToString("\n").ifBlank { ocrFallback }
            ScreenMindRecord(
                appName = json.optString("app_name", "unknown").ifBlank { "unknown" },
                category = cat,
                summary = json.optString("activity_summary", "").trim(),
                detailedContext = json.optString("detailed_context", "").trim(),
                mood = mood,
                confidence = json.optDouble("confidence", 0.7),
                sceneDescription = json.optString("scene_description", "").trim(),
                visibleText = visible,
                snippets = snippets
            )
        } catch (t: Throwable) {
            Log.w(TAG, "parse failed: ${t.message}")
            null
        }
    }

    private fun extractJsonObject(raw: String): JSONObject? {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull()
    }

    private fun normalizeCategory(raw: String): String {
        val c = raw.lowercase().trim()
        return if (c in categories) c else "other"
    }

    private fun normalizeMood(raw: String): String {
        val m = raw.lowercase().trim()
        return if (m in moods) m else "neutral"
    }

    private fun heuristicCategory(text: String): String {
        val t = text.lowercase()
        return when {
            listOf(
                "kill", "kd", " rank", "match", "respawn", "bgmi", "pubg", "codm",
                "call of duty", "free fire", "genshin", "roblox", "valorant",
                "minecraft", "fortnite", "league of legends", "mobile legends",
                "among us", "clash of clans", "clash royale", "subway surfers",
                "candy crush", "pokemon go", "brawl stars", "asphalt", "fifa",
                "ea sports fc", "warzone", "apex legends"
            ).any { it in t } -> "gaming"
            listOf("whatsapp", "telegram", "discord", "chat", "message").any { it in t } -> "communication"
            listOf("github", "gradle", "kotlin", "error:", "stacktrace").any { it in t } -> "coding"
            listOf("zoom", "meet", "teams").any { it in t } -> "meeting"
            listOf("youtube", "spotify", "netflix").any { it in t } -> "media"
            listOf("chrome", "http", "www.", ".com").any { it in t } -> "browsing"
            else -> "other"
        }
    }

    private fun heuristicApp(text: String): String {
        val t = text.lowercase()
        return when {
            "bgmi" in t || "battlegrounds mobile india" in t -> "BGMI"
            "pubg" in t -> "PUBG"
            "codm" in t || "call of duty" in t -> "Call of Duty"
            "free fire" in t -> "Free Fire"
            "genshin" in t -> "Genshin Impact"
            "roblox" in t -> "Roblox"
            "valorant" in t -> "Valorant"
            "minecraft" in t -> "Minecraft"
            "fortnite" in t -> "Fortnite"
            "mobile legends" in t -> "Mobile Legends"
            "league of legends" in t || "wild rift" in t -> "League of Legends"
            "among us" in t -> "Among Us"
            "clash of clans" in t -> "Clash of Clans"
            "clash royale" in t -> "Clash Royale"
            "subway surfers" in t -> "Subway Surfers"
            "candy crush" in t -> "Candy Crush"
            "pokemon go" in t -> "Pokémon GO"
            "brawl stars" in t -> "Brawl Stars"
            "asphalt" in t -> "Asphalt"
            "fifa" in t || "ea sports fc" in t -> "EA Sports FC"
            "whatsapp" in t -> "WhatsApp"
            "telegram" in t -> "Telegram"
            "instagram" in t -> "Instagram"
            "youtube" in t -> "YouTube"
            "chrome" in t -> "Chrome"
            "settings" in t -> "Settings"
            else -> "Phone UI"
        }
    }

    /** Prefer OS foreground label when OCR heuristics only say Phone UI. */
    fun withForegroundHint(record: ScreenMindRecord, foregroundLabel: String?): ScreenMindRecord {
        val fg = foregroundLabel?.trim().orEmpty()
        if (fg.isBlank()) return record
        val weakApp = record.appName.equals("Phone UI", true) ||
            record.appName.equals("unknown", true) ||
            record.appName.isBlank()
        if (!weakApp) return record
        val gaming = looksLikeGameLabel(fg) || record.category == "gaming"
        return record.copy(
            appName = fg,
            category = if (gaming) "gaming" else record.category,
            summary = when {
                record.summary.isBlank() -> "Opened $fg on screen"
                record.summary.contains("little readable", true) -> "Opened $fg on screen"
                else -> record.summary
            }
        )
    }

    private fun looksLikeGameLabel(label: String): Boolean {
        val t = label.lowercase()
        return listOf(
            "game", "bgmi", "pubg", "cod", "free fire", "genshin", "roblox",
            "valorant", "minecraft", "fortnite", "clash", "fifa", "asphalt",
            "legends", "warzone", "call of duty"
        ).any { it in t }
    }

    private fun bitmapToJpegBase64(bitmap: Bitmap): String? = try {
        val scaled = if (bitmap.width > 1280) {
            val h = (bitmap.height * 1280f / bitmap.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, 1280, h, true)
        } else bitmap
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        if (scaled !== bitmap) scaled.recycle()
        Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    } catch (_: Throwable) {
        null
    }
}
