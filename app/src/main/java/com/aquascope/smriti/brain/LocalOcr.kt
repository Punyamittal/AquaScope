package com.aquascope.smriti.brain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.SmritiCore
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object LocalOcr {
    private const val TAG = "LocalOcr"
    private const val MLKIT_PER_SCRIPT_MS = 25_000L
    private const val GEMMA_OCR_TIMEOUT_MS = 90_000L
    private const val MAX_OCR_SIDE = 1920

    @Volatile private var latinClient: TextRecognizer? = null
    @Volatile private var devanagariClient: TextRecognizer? = null
    private val clientLock = Any()

    private fun latin(): TextRecognizer =
        latinClient ?: synchronized(clientLock) {
            latinClient ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .also { latinClient = it }
        }

    private fun devanagari(): TextRecognizer =
        devanagariClient ?: synchronized(clientLock) {
            devanagariClient
                ?: TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                    .also { devanagariClient = it }
        }

    /**
     * Copy a picker URI into app cache **while the temporary read grant is still valid**.
     * Call this synchronously from the Activity Result callback before any async work.
     */
    fun copyPickerUriToCache(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "ocr_imports").also { it.mkdirs() }
        val out = File(dir, "ocr_${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not open selected file (no read access)")
        if (!out.exists() || out.length() == 0L) {
            out.delete()
            throw IllegalStateException("Selected file was empty or unreadable")
        }
        Log.i(TAG, "Cached picker image ${out.length()} bytes → ${out.absolutePath}")
        return out
    }

    suspend fun readFile(file: File, context: Context? = null): String = withContext(Dispatchers.IO) {
        val bitmap = decodeFile(file)
            ?: throw IllegalStateException("Could not decode image (use PNG/JPG/WebP)")
        try {
            if (context != null) {
                readBitmap(context, bitmap)
            } else {
                recognizeMlKit(bitmap)
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    suspend fun read(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val cached = try {
            copyPickerUriToCache(context.applicationContext, uri)
        } catch (t: Throwable) {
            Log.w(TAG, "cache copy failed, trying direct decode: ${t.message}")
            null
        }
        if (cached != null) {
            try {
                return@withContext readFile(cached, context)
            } finally {
                cached.delete()
            }
        }
        val bitmap = decodeBitmap(context.applicationContext, uri)
            ?: throw IllegalStateException("Could not open the selected image")
        try {
            readBitmap(context, bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * Prefer Gemma 4 vision via Ollama when enabled; always keep ML Kit as fallback.
     * ML Kit and Gemma use separate timeouts so a slow/unreachable Ollama cannot
     * cancel a successful on-device OCR result.
     */
    suspend fun readBitmap(context: Context, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val work = scaleForOcr(bitmap)
        try {
            val mlKit = runCatching { recognizeMlKit(work) }.getOrDefault("")
            val gemma = withTimeoutOrNull(GEMMA_OCR_TIMEOUT_MS) {
                runCatching { recognizeGemma(context, work) }.getOrNull()
            }.orEmpty()
            when {
                gemma.isNotBlank() && mlKit.isNotBlank() ->
                    if (gemma.length >= mlKit.length / 2) gemma else mergeUnique(listOf(gemma, mlKit))
                gemma.isNotBlank() -> gemma
                mlKit.isNotBlank() -> mlKit
                else -> ""
            }
        } finally {
            if (work !== bitmap && !work.isRecycled) work.recycle()
        }
    }

    /** ML Kit only — used when context is unavailable. */
    suspend fun readBitmap(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val work = scaleForOcr(bitmap)
        try {
            recognizeMlKit(work)
        } finally {
            if (work !== bitmap && !work.isRecycled) work.recycle()
        }
    }

    private suspend fun recognizeGemma(context: Context, bitmap: Bitmap): String? {
        val smriti = SmritiCore.get(context)
        if (!smriti.ollamaPrefs.ocrEnabled) return null
        val b64 = bitmapToJpegBase64(bitmap) ?: return null
        Log.i(TAG, "Gemma 4 OCR via Ollama (${smriti.ollamaPrefs.ocrModel})…")
        val text = smriti.ollamaClient.ocrFromJpegBase64(b64).getOrElse { err ->
            Log.w(TAG, "Gemma OCR failed: ${err.message}")
            return null
        }
        val cleaned = text.trim()
        if (cleaned.isBlank()) return null
        Log.i(TAG, "Gemma OCR chars=${cleaned.length}")
        return cleaned
    }

    private fun bitmapToJpegBase64(src: Bitmap): String? {
        return try {
            val maxSide = 1280
            val scaled = if (src.width > maxSide || src.height > maxSide) {
                val scale = maxSide.toFloat() / maxOf(src.width, src.height)
                Bitmap.createScaledBitmap(
                    src,
                    (src.width * scale).toInt().coerceAtLeast(1),
                    (src.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                src
            }
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            if (scaled !== src && !scaled.isRecycled) scaled.recycle()
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "JPEG encode failed: ${t.message}")
            null
        }
    }

    private fun scaleForOcr(src: Bitmap): Bitmap {
        if (src.width <= MAX_OCR_SIDE && src.height <= MAX_OCR_SIDE) return src
        val scale = MAX_OCR_SIDE.toFloat() / maxOf(src.width, src.height)
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).toInt().coerceAtLeast(1),
            (src.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            if (Build.VERSION.SDK_INT >= 28) {
                return try {
                    val src = ImageDecoder.createSource(file)
                    softwareBitmap(
                        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                            decoder.isMutableRequired = false
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            val w = info.size.width
                            val h = info.size.height
                            if (w > MAX_OCR_SIDE || h > MAX_OCR_SIDE) {
                                val scale = MAX_OCR_SIDE.toFloat() / maxOf(w, h)
                                decoder.setTargetSize(
                                    (w * scale).toInt().coerceAtLeast(1),
                                    (h * scale).toInt().coerceAtLeast(1)
                                )
                            }
                        }
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "ImageDecoder file failed: ${t.message}")
                    null
                }
            }
            return null
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_OCR_SIDE, MAX_OCR_SIDE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)?.let { softwareBitmap(it) }
    }

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val decoded = if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.isMutableRequired = false
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    if (w > MAX_OCR_SIDE || h > MAX_OCR_SIDE) {
                        val scale = MAX_OCR_SIDE.toFloat() / maxOf(w, h)
                        decoder.setTargetSize(
                            (w * scale).toInt().coerceAtLeast(1),
                            (h * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } ?: return null
            softwareBitmap(decoded)
        } catch (t: Throwable) {
            Log.w(TAG, "bitmap decode failed: ${t.message}")
            null
        }
    }

    private fun softwareBitmap(decoded: Bitmap): Bitmap {
        if (Build.VERSION.SDK_INT >= 26 && decoded.config == Bitmap.Config.HARDWARE) {
            val copy = decoded.copy(Bitmap.Config.ARGB_8888, false)
            if (copy != null && copy !== decoded) {
                decoded.recycle()
                return copy
            }
        }
        return decoded
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            var halfH = height / 2
            var halfW = width / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private suspend fun recognizeMlKit(bitmap: Bitmap): String = coroutineScope {
        val latinJob = async(Dispatchers.IO) {
            runRecognizer("latin", latin(), InputImage.fromBitmap(bitmap, 0))
        }
        val devaJob = async(Dispatchers.IO) {
            runRecognizer("devanagari", devanagari(), InputImage.fromBitmap(bitmap, 0))
        }
        mergeUnique(listOfNotNull(latinJob.await(), devaJob.await()))
    }

    private fun runRecognizer(
        label: String,
        client: TextRecognizer,
        image: InputImage
    ): String? {
        return try {
            val result = Tasks.await(client.process(image), MLKIT_PER_SCRIPT_MS, TimeUnit.MILLISECONDS)
            val text = result.text.orEmpty().trim()
            if (text.isNotBlank()) {
                Log.i(TAG, "$label OCR chars=${text.length}")
                text
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$label OCR failed: ${t.message}")
            null
        }
    }

    private fun mergeUnique(chunks: List<String>): String {
        if (chunks.isEmpty()) return ""
        if (chunks.size == 1) return chunks[0]
        val primary = chunks[0]
        val extra = chunks.drop(1).filter { other ->
            other.length > 12 && !primary.contains(other.take(40), ignoreCase = true)
        }
        return if (extra.isEmpty()) primary else (listOf(primary) + extra).joinToString("\n\n")
    }
}

fun TaxonomyParser.Kind.toHalo(): SmritiLightState = when (this) {
    TaxonomyParser.Kind.HEALTH -> SmritiLightState.HEALTH_OK
    TaxonomyParser.Kind.GAME -> SmritiLightState.GAME_KILL
    TaxonomyParser.Kind.ACOUSTIC -> SmritiLightState.GUARDIAN
    TaxonomyParser.Kind.UNKNOWN -> SmritiLightState.EXTRACTION
    else -> SmritiLightState.EXTRACTION
}
