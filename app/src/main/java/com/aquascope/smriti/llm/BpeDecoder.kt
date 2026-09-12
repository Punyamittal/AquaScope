package com.aquascope.smriti.llm

/**
 * Cleans up on-device LLM output:
 * - Un-escapes BPE whitespace tokens ('Ġ' -> space, 'Ċ' -> newline)
 * - Truncates stop tokens (<|im_end|>, etc.)
 * - Trims repetitive hallucinated tails
 */
object BpeDecoder {

    /**
     * Un-escapes GPT-2 / Hugging Face whitespace markers and cleans corrupt markers.
     */
    fun decode(input: String): String {
        if (!input.contains('Ġ') && !input.contains('Ċ') && !input.contains('ĉ') && !input.contains('č') && !input.contains('\ufffd')) {
            return input
        }
        return input
            .replace('Ġ', ' ')
            .replace('Ċ', '\n')
            .replace('ĉ', '\t')
            .replace('č', '\r')
            .replace("\ufffd", "")
    }

    /**
     * Truncates repeating trailing words or phrases caused by models missing stop tokens.
     */
    fun deduplicateTail(text: String): String {
        val tokens = text.trim().split(Regex("\\s+")).toMutableList()
        if (tokens.size < 4) return text

        for (phraseLen in 1..3) {
            if (tokens.size < phraseLen * 3) continue
            val phrase = tokens.takeLast(phraseLen)
            var repeats = 0
            var i = tokens.size - phraseLen
            while (i >= 0 && tokens.subList(i, i + phraseLen) == phrase) {
                repeats++
                i -= phraseLen
            }
            if (repeats >= 2) {
                val keepCount = tokens.size - (repeats - 1) * phraseLen
                val cleaned = tokens.take(keepCount).joinToString(" ").trimEnd { it == ',' || it == ' ' }
                return if (!cleaned.endsWith("।") && !cleaned.endsWith(".") && !cleaned.endsWith("?")) {
                    "$cleaned।"
                } else {
                    cleaned
                }
            }
        }
        return text
    }

    /**
     * Cleans up model output: stop tokens, byte-fallback decoding, tail dedup.
     */
    fun cleanModelOutput(raw: String): String {
        var text = raw.trim()
        val stopMarkers = listOf(
            "<|im_end|>", "<|endoftext|>", "<|im_start|>",
            "<end_of_turn>", "<start_of_turn>", "USER_QUESTION:", "HARD RULES:"
        )
        for (m in stopMarkers) {
            val idx = text.indexOf(m)
            if (idx != -1) {
                text = text.substring(0, idx).trim()
            }
        }
        text = decode(text)
        text = deduplicateTail(text)
        return text.trim()
    }
}
