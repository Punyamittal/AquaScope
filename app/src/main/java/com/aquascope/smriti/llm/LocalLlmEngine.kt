package com.aquascope.smriti.llm

/**
 * Optional on-device LLM. Missing/unloadable models must fail soft.
 */
interface LocalLlmEngine {
    val isReady: Boolean
    val modelLabel: String?
    fun generate(prompt: String): String?
    fun close()
}
