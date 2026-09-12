package com.aquascope.smriti.brain.peace

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.brain.LocalOcr
import com.aquascope.smriti.brain.TaxonomyParser
import com.aquascope.smriti.brain.screenmind.ForegroundAppResolver
import com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer
import com.aquascope.smriti.brain.screenmind.ScreenMindRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Gemma 4 (Ollama vision) summarize + categorize a PEACE/Capture highlight,
 * then format it for main Neural Core / Ask memory.
 */
object PeaceClipMemory {

    data class Result(
        val record: ScreenMindRecord,
        val memoryRaw: String,
        val kind: TaxonomyParser.Kind,
        val askContext: String,
        val usedGemma: Boolean
    )

    suspend fun summarizeAndCategorize(
        context: Context,
        frames: List<Bitmap>,
        clipFile: File,
        triggerReason: String,
        score: Float
    ): Result = withContext(Dispatchers.IO) {
        val fg = ForegroundAppResolver.current(context)?.label
        val smriti = SmritiCore.get(context)
        val gemmaOn = smriti.ollamaPrefs.ocrEnabled
        val frame = frames.lastOrNull()
        var usedGemma = false
        var record: ScreenMindRecord

        if (gemmaOn && frame != null) {
            val ocr = runCatching { LocalOcr.readBitmap(context, frame).trim() }.getOrDefault("")
            val jpeg = bitmapToJpegBase64(frame)
            val vision = if (jpeg != null) {
                smriti.ollamaClient.summarizeHighlightClip(jpeg, ocr, triggerReason).getOrElse { err ->
                    Log.w(TAG, "Gemma clip summary failed: ${err.message}")
                    smriti.ollamaClient.screenMindAnalyze(jpeg, ocr).getOrNull()
                }
            } else null
            if (vision != null) {
                usedGemma = true
                record = vision.copy(visibleText = vision.visibleText.ifBlank { ocr })
                Log.i(TAG, "Gemma clip ok app=${record.appName} cat=${record.category}")
            } else {
                record = ScreenMindAnalyzer.fromOcrOnly(ocr, triggerReason)
            }
        } else if (frame != null) {
            val ocr = runCatching { LocalOcr.readBitmap(context, frame).trim() }.getOrDefault("")
            record = ScreenMindAnalyzer.fromOcrOnly(ocr, triggerReason)
        } else {
            record = ScreenMindAnalyzer.fromOcrOnly("", triggerReason)
        }

        record = ScreenMindAnalyzer.withForegroundHint(record, fg)
        if (triggerReason.contains("peace", true) || triggerReason.contains("kill", true)) {
            if (record.category == "other" || record.category.isBlank()) {
                record = record.copy(category = "gaming")
            }
        }
        record = ensureReadableSummary(record, triggerReason, score, usedGemma)

        val kind = kindFor(record.category, triggerReason)
        val memoryRaw = buildMemoryRaw(record, clipFile, triggerReason, score, usedGemma)
        val ask = buildString {
            append(record.askContext())
            append("\nPEACE clip file=${clipFile.name}")
            append(" · category=${record.category} · app=${record.appName}")
            if (record.summary.isNotBlank()) append("\nSummary: ${record.summary}")
        }
        Result(record, memoryRaw, kind, ask, usedGemma)
    }

    /** Always produce a short human summary for the Neural Core MEMORY list. */
    private fun ensureReadableSummary(
        record: ScreenMindRecord,
        reason: String,
        score: Float,
        usedGemma: Boolean
    ): ScreenMindRecord {
        val app = record.appName.ifBlank { "Screen" }
        val cat = record.category.ifBlank { "clip" }
        val existing = record.summary.trim()
        if (existing.isNotBlank() &&
            !existing.contains("little readable", true) &&
            !existing.startsWith("On-screen text captured", true) &&
            !existing.startsWith("Screen clip (", true)
        ) {
            return record
        }
        val ocrBit = record.visibleText.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.length in 4..80 }
        val triggerBit = when {
            reason.contains("template", true) -> "kill-feed match"
            reason.contains("kill", true) || reason.contains("banner", true) -> "kill / HUD flash"
            reason.contains("hud", true) -> "HUD motion"
            reason.contains("manual", true) -> "manual Clip"
            reason.contains("stop", true) -> "Capture stop"
            else -> "auto highlight"
        }
        val summary = buildString {
            append(app)
            append(" · ")
            append(cat)
            append(" · ")
            append(triggerBit)
            if (usedGemma) append(" · Gemma")
            else if (ocrBit != null) {
                append(" — ")
                append(ocrBit)
            } else {
                append(" (score ")
                append("%.0f".format(score * 100))
                append("%)")
            }
        }.take(140)
        val detail = record.detailedContext.ifBlank {
            buildString {
                append("PEACE saved a highlight clip from ")
                append(app)
                append(" (action −5s…+5s, not the full recording). Trigger: ")
                append(reason)
                if (ocrBit != null) {
                    append(". On screen: ")
                    append(record.visibleText.take(240))
                } else {
                    append(". Little OCR text — open the clip to review.")
                }
            }
        }
        return record.copy(summary = summary, detailedContext = detail.take(500))
    }

    fun kindFor(category: String, reason: String): TaxonomyParser.Kind {
        val c = category.lowercase()
        val r = reason.lowercase()
        return when {
            c == "gaming" || r.contains("peace") || r.contains("kill") || r.contains("banner") ||
                r.contains("template") -> TaxonomyParser.Kind.GAME
            c == "communication" -> TaxonomyParser.Kind.PEOPLE
            c == "browsing" || c == "coding" -> TaxonomyParser.Kind.TOOLS
            c == "media" -> TaxonomyParser.Kind.TOOLS
            else -> TaxonomyParser.Kind.UNKNOWN
        }
    }

    private fun buildMemoryRaw(
        record: ScreenMindRecord,
        clipFile: File,
        reason: String,
        score: Float,
        usedGemma: Boolean
    ): String = buildString {
        // First meaningful lines drive MEMORY card title via TaxonomyParser.
        append("Title: ")
        append(record.summary.ifBlank { "Highlight · ${record.appName}" }.take(120))
        append("\nSummary: ")
        append(record.detailedContext.ifBlank { record.summary }.take(280))
        append("\nPEACE highlight clip")
        if (usedGemma) append(" · Gemma 4 summary")
        append("\nCategory: ${record.category}")
        append(" · App: ${record.appName}")
        append(" · Mood: ${record.mood}")
        append(" · conf=${"%.2f".format(record.confidence)}")
        append(" · trigger=$reason · score=${"%.2f".format(score)}")
        append("\nFile: ${clipFile.absolutePath}")
        if (record.sceneDescription.isNotBlank()) {
            append("\nScene: ")
            append(record.sceneDescription.take(1_000))
        }
        if (record.visibleText.isNotBlank()) {
            append("\nSeen on screen:\n")
            append(record.visibleText.take(1_500))
        } else if (record.snippets.isNotEmpty()) {
            append("\nSeen on screen:\n")
            append(record.snippets.joinToString("\n").take(1_500))
        }
        append("\nTags: clip, recording, peace, ${record.category}, ${record.appName}")
        append("\n(searchable in Neural Core memory / Ask)")
    }

    private fun bitmapToJpegBase64(bitmap: Bitmap): String? {
        return try {
            val max = 1280
            val scaled = if (bitmap.width > max || bitmap.height > max) {
                val ratio = minOf(max.toFloat() / bitmap.width, max.toFloat() / bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            if (scaled !== bitmap) scaled.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "jpeg encode: ${t.message}")
            null
        }
    }

    private const val TAG = "PeaceClipMemory"
}
