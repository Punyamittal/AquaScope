package com.aquascope.smriti.llm

import android.content.Context
import com.google.gson.JsonParser
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Multilingual Whisper byte-level BPE decoder (vocab + special tokens from assets).
 */
class WhisperTokenizer private constructor(
    private val idToToken: Array<String?>,
    private val byteDecoder: Map<Char, Int>
) {

    fun decode(tokenIds: List<Int>, skipSpecial: Boolean = true): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            if (id < 0 || id >= idToToken.size) continue
            val tok = idToToken[id] ?: continue
            if (skipSpecial && isSpecial(tok)) continue
            sb.append(tok)
        }
        return decodeBytes(sb.toString()).trim()
    }

    private fun isSpecial(tok: String): Boolean =
        tok.startsWith("<|") && tok.endsWith("|>")

    private fun decodeBytes(text: String): String {
        val bytes = ArrayList<Byte>(text.length)
        for (ch in text) {
            val b = byteDecoder[ch]
            if (b != null) bytes.add(b.toByte())
            else {
                // Fallback: keep BMP char as UTF-8
                val encoded = ch.toString().toByteArray(StandardCharsets.UTF_8)
                for (x in encoded) bytes.add(x)
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    companion object {
        const val SOT = 50258
        const val EOT = 50257
        const val TRANSCRIBE = 50359
        const val TRANSLATE = 50358
        const val NO_TIMESTAMPS = 50363
        const val TIMESTAMP_BEGIN = 50364

        /** language code → token id (multilingual Whisper). */
        val LANGUAGE_IDS: Map<String, Int> = mapOf(
            "en" to 50259, "zh" to 50260, "de" to 50261, "es" to 50262, "ru" to 50263,
            "ko" to 50264, "fr" to 50265, "ja" to 50266, "pt" to 50267, "tr" to 50268,
            "pl" to 50269, "ca" to 50270, "nl" to 50271, "ar" to 50272, "sv" to 50273,
            "it" to 50274, "id" to 50275, "hi" to 50276, "fi" to 50277, "vi" to 50278,
            "he" to 50279, "uk" to 50280, "el" to 50281, "ms" to 50282, "cs" to 50283,
            "ro" to 50284, "da" to 50285, "hu" to 50286, "ta" to 50287, "no" to 50288,
            "th" to 50289, "ur" to 50290, "hr" to 50291, "bg" to 50292, "lt" to 50293,
            "la" to 50294, "mi" to 50295, "ml" to 50296, "cy" to 50297, "sk" to 50298,
            "te" to 50299, "fa" to 50300, "lv" to 50301, "bn" to 50302, "sr" to 50303,
            "az" to 50304, "sl" to 50305, "kn" to 50306, "et" to 50307, "mk" to 50308,
            "br" to 50309, "eu" to 50310, "is" to 50311, "hy" to 50312, "ne" to 50313,
            "mn" to 50314, "bs" to 50315, "kk" to 50316, "sq" to 50317, "sw" to 50318,
            "gl" to 50319, "mr" to 50320, "pa" to 50321, "si" to 50322, "km" to 50323,
            "sn" to 50324, "yo" to 50325, "so" to 50326, "af" to 50327, "oc" to 50328,
            "ka" to 50329, "be" to 50330, "tg" to 50331, "sd" to 50332, "gu" to 50333,
            "am" to 50334, "yi" to 50335, "lo" to 50336, "uz" to 50337, "fo" to 50338,
            "ht" to 50339, "ps" to 50340, "tk" to 50341, "nn" to 50342, "mt" to 50343,
            "sa" to 50344, "lb" to 50345, "my" to 50346, "bo" to 50347, "tl" to 50348,
            "mg" to 50349, "as" to 50350, "tt" to 50351, "haw" to 50352, "ln" to 50353,
            "ha" to 50354, "ba" to 50355, "jw" to 50356, "su" to 50357
        )

        fun languageId(tag: String?): Int {
            if (tag.isNullOrBlank()) return LANGUAGE_IDS.getValue("en")
            val primary = tag.replace('_', '-').lowercase().substringBefore('-')
            return LANGUAGE_IDS[primary] ?: LANGUAGE_IDS.getValue("en")
        }

        fun load(context: Context): WhisperTokenizer {
            val idToToken = arrayOfNulls<String>(51_865)
            context.assets.open("whisper/vocab.json").use { stream ->
                val root = JsonParser.parseReader(InputStreamReader(stream, StandardCharsets.UTF_8)).asJsonObject
                for ((token, idEl) in root.entrySet()) {
                    val id = idEl.asInt
                    if (id in idToToken.indices) idToToken[id] = token
                }
            }
            context.assets.open("whisper/added_tokens.json").use { stream ->
                val root = JsonParser.parseReader(InputStreamReader(stream, StandardCharsets.UTF_8)).asJsonObject
                for ((token, idEl) in root.entrySet()) {
                    val id = idEl.asInt
                    if (id in idToToken.indices) idToToken[id] = token
                }
            }
            return WhisperTokenizer(idToToken, bytesToUnicode().entries.associate { (b, c) -> c to b })
        }

        /** GPT-2 / Whisper bytes_to_unicode. */
        private fun bytesToUnicode(): Map<Int, Char> {
            val bs = ArrayList<Int>()
            for (i in '!'.code..'~'.code) bs.add(i)
            for (i in '¡'.code..'¬'.code) bs.add(i)
            for (i in '®'.code..'ÿ'.code) bs.add(i)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val map = HashMap<Int, Char>(bs.size)
            for (i in bs.indices) map[bs[i]] = cs[i].toChar()
            return map
        }
    }
}
