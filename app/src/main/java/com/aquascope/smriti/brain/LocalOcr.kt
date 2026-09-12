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
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object LocalOcr {
    private const val TAG = "LocalOcr"
    private const val OCR_TIMEOUT_MS = 45_000L
    private const val GEMMA_OCR_TIMEOUT_MS = 120_000L

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
        withTimeout(GEMMA_OCR_TIMEOUT_MS) {
            val bitmap = decodeFile(file)
                ?: throw IllegalStateException("Could not decode image (use PNG/JPG)")
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
        withTimeout(GEMMA_OCR_TIMEOUT_MS) {
            val bitmap = decodeBitmap(context.applicationContext, uri)
                ?: throw IllegalStateException("Could not open the selected image")
            try {
                readBitmap(context, bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /**
     * Prefer Gemma 4 vision via Ollama when enabled; always keep ML Kit as fallback / merge.
     */
    suspend fun readBitmap(context: Context, bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        withTimeout(GEMMA_OCR_TIMEOUT_MS) {
            val mlKit = runCatching { recognizeMlKit(bitmap) }.getOrDefault("")
            val gemma = runCatching { recognizeGemma(context, bitmap) }.getOrNull().orEmpty()
            when {
                gemma.isNotBlank() && mlKit.isNotBlank() ->
                    if (gemma.length >= mlKit.length / 2) gemma else mergeUnique(listOf(gemma, mlKit))
                gemma.isNotBlank() -> gemma
                else -> mlKit
            }
        }
    }

    /** ML Kit only — used when context is unavailable. */
    suspend fun readBitmap(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        withTimeout(OCR_TIMEOUT_MS) { recognizeMlKit(bitmap) }
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

    private fun decodeFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            if (Build.VERSION.SDK_INT >= 28) {
                return try {
                    val src = ImageDecoder.createSource(file)
                    softwareBitmap(
                        ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                            decoder.isMutableRequired = false
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 1920, 1920)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            val decoded = if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = false
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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

    private fun recognizeMlKit(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val chunks = ArrayList<String>(2)
        runRecognizer("latin", TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS), image, chunks)
        runRecognizer(
            "devanagari",
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
            image,
            chunks
        )
        return mergeUnique(chunks)
    }

    private fun runRecognizer(
        label: String,
        client: com.google.mlkit.vision.text.TextRecognizer,
        image: InputImage,
        out: MutableList<String>
    ) {
        try {
            val result = Tasks.await(client.process(image), OCR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val text = result.text.orEmpty().trim()
            if (text.isNotBlank()) {
                out.add(text)
                Log.i(TAG, "$label OCR chars=${text.length}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$label OCR failed: ${t.message}")
        } finally {
            runCatching { client.close() }
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
