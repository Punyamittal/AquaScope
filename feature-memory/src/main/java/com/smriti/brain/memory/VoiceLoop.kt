package com.smriti.brain.memory

import java.io.File

/**
 * Offline STT/LLM/TTS loop. Models are optional files under filesDir/models.
 * Never fabricates a transcript when the engine is missing.
 */
class VoiceLoop(private val modelsDir: File) {
    fun status(): String {
        val stt = File(modelsDir, "vosk").isDirectory
        val llm = modelsDir.listFiles()?.any { it.name.endsWith(".task") } == true
        return buildString {
            append(if (stt) "STT ready" else "STT model missing (place Vosk under models/vosk)")
            append(" · ")
            append(if (llm) "LLM ready" else "LLM .task missing")
        }
    }
}
