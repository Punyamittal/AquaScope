package com.aquascope.smriti.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * MediaPipe GenAI wrapper. Loads only when a model file is present.
 * Gemma 3 needs a session + a context window larger than the grounded Ask prompt.
 */
class MediaPipeLocalLlm(
    private val context: Context,
    private val modelFile: File
) : LocalLlmEngine {

    private val inference = AtomicReference<LlmInference?>(null)
    private var loadError: String? = null
    private var maxTokens: Int = 1024

    override val isReady: Boolean
        get() = inference.get() != null

    override val modelLabel: String?
        get() = if (isReady) modelFile.name else null

    fun warmUp(): Boolean {
        if (inference.get() != null) return true
        if (modelFile.name.endsWith(".litertlm", ignoreCase = true)) {
            loadError = LocalModelStore.GALLERY_NPU_MESSAGE
            Log.w(TAG, "Refusing Gallery NPU bundle: ${modelFile.name}")
            return false
        }
        // maxTokens is prompt + reply. 384 cannot fit a grounded Ask prompt.
        maxTokens = if (modelFile.length() > 900_000_000L) 768 else 1024
        return try {
            System.gc()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(40)
                .setPreferredBackend(LlmInference.Backend.DEFAULT)
                .build()
            val engine = LlmInference.createFromOptions(context, options)
            inference.set(engine)
            loadError = null
            Log.i(TAG, "Local LLM ready: ${modelFile.name} maxTokens=$maxTokens")
            true
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "Local LLM load failed: $loadError", t)
            inference.set(null)
            false
        }
    }

    fun lastError(): String? = loadError

    override fun generate(prompt: String): String? {
        val engine = inference.get() ?: return null
        return try {
            val clipped = clipToContext(engine, prompt)
            val text = generateWithSession(engine, clipped)
                ?: generateWithSession(engine, shortenForRetry(clipped))
            if (text.isNullOrBlank()) {
                val empty = "Model returned empty text (prompt may still exceed the $maxTokens token window)"
                loadError = empty
                Log.w(TAG, empty)
                null
            } else {
                loadError = null
                text
            }
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "Local LLM generate failed: $loadError", t)
            null
        }
    }

    private fun generateWithSession(engine: LlmInference, prompt: String): String? {
        val session = try {
            LlmInferenceSession.createFromOptions(engine, sessionOptions())
        } catch (t: Throwable) {
            Log.w(TAG, "session options failed, retrying defaults: ${t.message}")
            LlmInferenceSession.createFromOptions(
                engine,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTemperature(0.7f)
                    .build()
            )
        }
        return try {
            session.addQueryChunk(prompt)
            session.generateResponse()?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            try {
                session.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun sessionOptions(): LlmInferenceSession.LlmInferenceSessionOptions =
        LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTopP(0.95f)
            .setTemperature(0.7f)
            .setGraphOptions(
                GraphOptions.builder()
                    .setEnableVisionModality(false)
                    .setEnableAudioModality(false)
                    .build()
            )
            .build()

    private fun clipToContext(engine: LlmInference, prompt: String): String {
        val reserve = 160
        val budget = (maxTokens - reserve).coerceAtLeast(256)
        var text = formatPrompt(prompt)
        val tokens = runCatching { engine.sizeInTokens(text) }.getOrNull()
        if (tokens != null && tokens > budget) {
            val keep = ((text.length.toLong() * budget) / tokens).toInt().coerceAtLeast(400)
            text = text.take(keep)
            Log.w(TAG, "Clipped prompt $tokens → ~$budget tokens")
        } else if (tokens == null && text.length > budget * 4) {
            text = text.take(budget * 4)
        }
        return text
    }

    private fun formatPrompt(raw: String): String {
        if (raw.contains("<start_of_turn>") || raw.contains("<|im_start|>")) return raw
        val name = modelFile.name.lowercase()
        return if (name.contains("gemma")) {
            "<start_of_turn>user\n$raw<end_of_turn>\n<start_of_turn>model\n"
        } else {
            raw
        }
    }

    private fun shortenForRetry(prompt: String): String {
        val cut = prompt.length / 2
        return if (cut < 200) prompt else prompt.take(cut)
    }

    override fun close() {
        try {
            inference.getAndSet(null)?.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "SmritiLocalLlm"

        fun tryCreate(context: Context, store: LocalModelStore): MediaPipeLocalLlm? {
            val file = store.findInstalled() ?: return null
            val llm = MediaPipeLocalLlm(context.applicationContext, file)
            llm.warmUp()
            return llm
        }
    }
}
