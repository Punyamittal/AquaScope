package com.smriti.core.guardian

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.File

/**
 * VoskStt — offline streaming speech-to-text via vosk-android (SPEC §2.4).
 *
 * Model directory resolution (first match wins):
 *  1. context.filesDir/vosk-model            (preferred — extracted from assets or sideloaded)
 *  2. context.getExternalFilesDir(null)/vosk-model
 *
 * [start] returns false (and logs) when the model dir is absent or the model fails to load —
 * it NEVER crashes. All vosk calls are wrapped in try/catch(Throwable).
 *
 * RECORD_AUDIO is a runtime permission requested by MainActivity; if denied, SpeechService
 * fails into the catch path and [start] returns false.
 */
@SuppressLint("MissingPermission")
class VoskStt(private val context: Context) {

    companion object {
        private const val TAG = "VoskStt"
        const val SAMPLE_RATE = 16000f
        const val MODEL_DIR_NAME = "vosk-model"
    }

    private var speechService: Any? = null
    private var recognizer: Any? = null
    private var model: Any? = null
    @Volatile private var listening = false

    /**
     * Resolve the on-disk model directory, or null if absent.
     * A valid dir must exist and be non-empty (guards against a half-extracted model).
     */
    private fun findModelDir(): File? {
        val candidates = listOfNotNull(
            File(context.filesDir, MODEL_DIR_NAME),
            context.getExternalFilesDir(null)?.let { File(it, MODEL_DIR_NAME) }
        )
        return candidates.firstOrNull { it.isDirectory && (it.list()?.isNotEmpty() == true) }
    }

    /**
     * Load the model (if needed) and start streaming recognition.
     * [onText] is invoked with every non-blank FINAL result.
     * @return false if the model is absent/unloadable or the mic cannot start; never throws.
     */
    fun start(onText: (String) -> Unit): Boolean {
        if (listening) return true
        val dir = findModelDir() ?: run {
            Log.w(TAG, "model dir absent (${context.filesDir}/$MODEL_DIR_NAME); STT disabled")
            return false
        }
        return try {
            val modelClass = Class.forName("org.vosk.Model")
            val recognizerClass = Class.forName("org.vosk.Recognizer")
            val speechServiceClass = Class.forName("org.vosk.android.SpeechService")

            val m = model ?: modelClass.getConstructor(String::class.java).newInstance(dir.absolutePath).also { model = it }
            val r = recognizer ?: recognizerClass.getConstructor(modelClass, Float::class.javaPrimitiveType).newInstance(m, SAMPLE_RATE).also { recognizer = it }
            val service = speechServiceClass.getConstructor(recognizerClass, Float::class.javaPrimitiveType).newInstance(r, SAMPLE_RATE)
            speechService = service

            val listenerClass = Class.forName("org.vosk.android.RecognitionListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onResult", "onFinalResult" -> {
                        val hypothesis = args?.getOrNull(0) as? String
                        if (!hypothesis.isNullOrEmpty()) {
                            try {
                                val text = org.json.JSONObject(hypothesis).optString("text").trim()
                                if (text.isNotEmpty()) onText(text)
                            } catch (t: Throwable) {
                                Log.w(TAG, "bad result json", t)
                            }
                        }
                    }
                    "onError" -> Log.w(TAG, "vosk error: ${args?.getOrNull(0)}")
                    "onTimeout" -> Log.i(TAG, "vosk listening timeout")
                }
                null
            }

            speechServiceClass.getMethod("startListening", listenerClass).invoke(service, proxy)
            listening = true
            Log.i(TAG, "vosk STT started (${dir.absolutePath})")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "vosk start failed or vosk-android runtime absent: ${t.message}")
            cleanup()
            false
        }
    }

    /** Stop listening and release native resources. Never throws. */
    fun stop() {
        listening = false
        cleanup()
    }

    private fun cleanup() {
        try {
            speechService?.let { it.javaClass.getMethod("stop").invoke(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "speechService.stop failed", t)
        }
        try {
            speechService?.let { it.javaClass.getMethod("shutdown").invoke(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "speechService.shutdown failed", t)
        }
        speechService = null
        try {
            recognizer?.let { it.javaClass.getMethod("close").invoke(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "recognizer.close failed", t)
        }
        recognizer = null
        try {
            model?.let { it.javaClass.getMethod("close").invoke(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "model.close failed", t)
        }
        model = null
    }
}
