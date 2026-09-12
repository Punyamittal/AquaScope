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
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setMaxTopK(40)
                .build()
            val engine = LlmInference.createFromOptions(context, options)
            inference.set(engine)
            loadError = null
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
            engine.generateResponse(prompt)?.trim()?.takeIf { it.isNotEmpty() }
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
