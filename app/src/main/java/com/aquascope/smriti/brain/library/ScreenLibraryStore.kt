package com.aquascope.smriti.brain.library

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.aquascope.smriti.brain.screenmind.ForegroundAppResolver
import com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer
import com.aquascope.smriti.brain.screenmind.ScreenMindRecord
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class ScreenClip(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val imagePath: String?,
    val ocrPath: String?,
    val ocrText: String,
    val appName: String,
    val packageName: String?,
    val category: String,
    val websites: List<String>,
    val keywords: List<String>,
    val summary: String,
    /** Optional PEACE / Capture MP4 path — Library can open it. */
    val videoPath: String? = null
)

/**
 * On-device library of swipe/gallery screen captures, searchable by
 * app, website, ScreenMind category, and keywords.
 */
object ScreenLibraryStore {
    private const val TAG = "ScreenLibrary"
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<(List<ScreenClip>) -> Unit>()

    private fun dir(ctx: Context) = File(ctx.filesDir, "screen_library").also { it.mkdirs() }
    private fun imagesDir(ctx: Context) = File(dir(ctx), "images").also { it.mkdirs() }
    private fun indexFile(ctx: Context) = File(dir(ctx), "index.json")

    fun addListener(l: (List<ScreenClip>) -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: (List<ScreenClip>) -> Unit) {
        listeners.remove(l)
    }

    fun all(ctx: Context): List<ScreenClip> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val type = object : TypeToken<MutableList<ScreenClip>>() {}.type
            gson.fromJson<MutableList<ScreenClip>>(f.readText(), type) ?: mutableListOf()
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: ${t.message}")
            emptyList()
        }
    }

    @Synchronized
    fun save(ctx: Context, clip: ScreenClip): ScreenClip {
        val list = all(ctx).toMutableList()
        list.add(0, clip)
        while (list.size > 400) {
            val old = list.removeAt(list.lastIndex)
            old.imagePath?.let { runCatching { File(it).delete() } }
            old.ocrPath?.let { runCatching { File(it).delete() } }
        }
        indexFile(ctx).writeText(gson.toJson(list))
        listeners.forEach { runCatching { it(list) } }
        return clip
    }

    fun categories(ctx: Context): List<String> =
        all(ctx).map { it.category }.filter { it.isNotBlank() }.distinct().sorted()

    fun apps(ctx: Context): List<String> =
        all(ctx).map { it.appName }.filter { it.isNotBlank() }.distinct().sorted()

    fun websites(ctx: Context): List<String> =
        all(ctx).flatMap { it.websites }.distinct().sorted()

    fun keywords(ctx: Context): List<String> =
        all(ctx).flatMap { it.keywords }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(80)

    fun search(
        ctx: Context,
        query: String = "",
        category: String? = null,
        app: String? = null,
        website: String? = null,
        keyword: String? = null
    ): List<ScreenClip> {
        val q = query.trim().lowercase(Locale.getDefault())
        return all(ctx).filter { clip ->
            (category.isNullOrBlank() || clip.category.equals(category, true)) &&
                (app.isNullOrBlank() || clip.appName.equals(app, true)) &&
                (website.isNullOrBlank() || clip.websites.any { it.contains(website, true) }) &&
                (keyword.isNullOrBlank() || clip.keywords.any { it.equals(keyword, true) }) &&
                (
                    q.isBlank() ||
                        clip.ocrText.contains(q, true) ||
                        clip.summary.contains(q, true) ||
                        clip.appName.contains(q, true) ||
                        clip.keywords.any { it.contains(q, true) } ||
                        clip.websites.any { it.contains(q, true) } ||
                        clip.category.contains(q, true)
                    )
        }
    }

    fun saveBitmap(ctx: Context, bitmap: Bitmap): File {
        val out = File(imagesDir(ctx), "clip_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return out
    }

    /** Copy a screenshot into the library images folder (keeps original if already there). */
    fun saveImageFile(ctx: Context, source: File): File {
        if (source.absolutePath.startsWith(imagesDir(ctx).absolutePath) && source.exists()) {
            return source
        }
        val dest = File(imagesDir(ctx), "clip_${System.currentTimeMillis()}_${source.name}")
        source.copyTo(dest, overwrite = true)
        return dest
    }

    fun ingest(
        ctx: Context,
        ocrText: String,
        imageFile: File?,
        mind: ScreenMindRecord? = null,
        foreground: ForegroundAppResolver.ForegroundApp? = null,
        videoFile: File? = null
    ): ScreenClip {
        val record = mind ?: ScreenMindAnalyzer.fromOcrOnly(ocrText, "library")
        val withFg = ScreenMindAnalyzer.withForegroundHint(record, foreground?.label)
        val websites = ScreenClipCategorizer.extractWebsites(ocrText)
        val keywords = ScreenClipCategorizer.extractKeywords(ocrText, withFg)
        val keptImage = imageFile?.takeIf { it.exists() }?.let { saveImageFile(ctx, it) }
        val ocrFile = runCatching {
            val f = File(dir(ctx), "ocr_${System.currentTimeMillis()}.txt")
            f.writeText(ocrText)
            f
        }.getOrNull()
        val clip = ScreenClip(
            imagePath = keptImage?.absolutePath,
            ocrPath = ocrFile?.absolutePath,
            ocrText = ocrText,
            appName = withFg.appName.ifBlank { foreground?.label ?: "Unknown" },
            packageName = foreground?.packageName,
            category = withFg.category.ifBlank { "other" },
            websites = websites,
            keywords = keywords + listOfNotNull(
                if (videoFile != null) "peace" else null,
                if (videoFile != null) "clip" else null
            ),
            summary = withFg.summary.ifBlank { ocrText.lines().firstOrNull().orEmpty().take(120) },
            videoPath = videoFile?.takeIf { it.exists() }?.absolutePath
        )
        return save(ctx, clip)
    }
}

object ScreenClipCategorizer {
    private val urlRegex = Regex(
        """(?i)\b((?:https?://)?(?:www\.)?[a-z0-9][-a-z0-9]*\.(?:com|org|net|io|co|in|app|dev|ai|edu|gov)(?:/[^\s]*)?)"""
    )
    private val stop = setOf(
        "the", "and", "for", "with", "from", "this", "that", "your", "have", "are", "was",
        "you", "not", "but", "all", "can", "our", "out", "get", "has", "had", "how", "what",
        "when", "where", "who", "will", "just", "like", "into", "than", "then", "them", "they",
        "been", "more", "some", "also", "only", "over", "such", "about", "after", "before"
    )

    fun extractWebsites(text: String): List<String> {
        return urlRegex.findAll(text)
            .map { it.value.trim().lowercase(Locale.US).removePrefix("https://").removePrefix("http://") }
            .map { it.trimEnd('/', '.', ',') }
            .filter { it.contains('.') && it.length in 4..80 }
            .distinct()
            .take(12)
            .toList()
    }

    fun extractKeywords(text: String, mind: ScreenMindRecord): List<String> {
        val fromSnippets = mind.snippets
            .flatMap { it.split(Regex("[^A-Za-z0-9+#]+")) }
            .map { it.trim() }
        val fromText = text.lineSequence()
            .flatMap { it.split(Regex("[^A-Za-z0-9+#]+")) }
            .map { it.trim() }
        return (fromSnippets + fromText)
            .filter { it.length in 3..28 }
            .filter { it.any { ch -> ch.isLetter() } }
            .map { it.lowercase(Locale.US) }
            .filter { it !in stop }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(16)
    }
}
