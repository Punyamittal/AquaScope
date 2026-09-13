package com.aquascope.smriti.llm

/**
 * Cleans up on-device LLM output:
 * - Reverses GPT-2 / Qwen byte-level BPE encoding (space/newline markers and
 *   leaked raw byte-surrogate chars for multi-byte scripts like Devanagari)
 * - Truncates stop tokens (<|im_end|>, etc.)
 * - Trims repetitive hallucinated tails
 */
object BpeDecoder {

    /**
     * GPT-2 / Qwen byte-level BPE maps each of the 256 raw byte values to one
     * "surrogate" unicode char (ASCII 33-126 and Latin-1 161-172/174-255 map to
     * themselves; the remaining unmapped bytes -- space, newline, control chars --
     * get consecutive codepoints starting at U+0100). MediaPipe's detokenizer
     * reconstructs most tokens into real text correctly, but leaks these raw
     * byte-surrogate chars for some multi-byte sequences (observed: Devanagari
     * characters split across BPE tokens come back corrupted instead of the
     * real character). [UNICODE_TO_BYTE] reverses that mapping so any such run
     * can be byte-decoded back into correct UTF-8.
     */
    private val UNICODE_TO_BYTE: Map<Char, Int> = buildByteToUnicode().entries.associate { (b, c) -> c to b }

    private fun buildByteToUnicode(): Map<Int, Char> {
        val bs = mutableListOf<Int>()
        bs.addAll('!'.code..'~'.code)
        bs.addAll(161..172)
        bs.addAll(174..255)
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        return bs.zip(cs).associate { (b, c) -> b to c.toChar() }
    }

    /** Byte-decodes any run of GPT-2 byte-surrogate chars back into real UTF-8 text. */
    private fun decodeByteLevelBpe(input: String): String {
        if (input.none { it in UNICODE_TO_BYTE }) return input
        val out = StringBuilder(input.length)
        val byteBuf = java.io.ByteArrayOutputStream()
        fun flush() {
            if (byteBuf.size() > 0) {
                out.append(String(byteBuf.toByteArray(), Charsets.UTF_8))
                byteBuf.reset()
            }
        }
        for (ch in input) {
            val b = UNICODE_TO_BYTE[ch]
            if (b != null) {
                byteBuf.write(b)
            } else {
                flush()
                out.append(ch)
            }
        }
        flush()
        return out.toString()
    }

    /** Un-escapes GPT-2 byte-level BPE markers and strips stray replacement chars. */
    fun decode(input: String): String {
        val hasReplacementChar = input.indexOf(0xFFFD.toChar()) >= 0
        if (!hasReplacementChar && input.none { it in UNICODE_TO_BYTE }) {
            return input
        }
        val decoded = decodeByteLevelBpe(input)
        return if (hasReplacementChar) decoded.filterNot { it.code == 0xFFFD } else decoded
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
