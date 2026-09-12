package com.aquascope.smriti.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * MediaPipe GenAI wrapper. Loads only when a model file is present.
 */
class MediaPipeLocalLlm(
    private val context: Context,
    private val modelFile: File
) : LocalLlmEngine {

    private val inference = AtomicReference<LlmInference?>(null)
    private var loadError: String? = null

    override val isReady: Boolean
        get() = inference.get() != null

    override val modelLabel: String?
        get() = if (isReady) modelFile.name else null

    fun warmUp(): Boolean {
        if (inference.get() != null) return true
        // Trim prompt budget on large models (e.g. Qwen 1.5B) to reduce OOM risk.
        val maxTokens = if (modelFile.length() > 900_000_000L) 256 else 384
        return try {
            System.gc()
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(40)
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
            // Cap input size — huge prompts + Qwen can native-OOM the process.
            val clipped = if (prompt.length > 3500) prompt.take(3500) else prompt
            engine.generateResponse(clipped)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "Local LLM generate failed", t)
            null
        }
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
            return if (llm.warmUp()) llm else null
        }
    }
}
