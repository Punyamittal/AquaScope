package com.aquascope.smriti.brain

/**
 * Deterministic routing of OCR / transcripts into typed episodic entities.
 * Never invents facts — only labels what the text already contains.
 */
object TaxonomyParser {

    enum class Kind {
        TOOLS, HEALTH, PLACES, LOCATION, MONEY, PEOPLE, ACCESS, GAME, ACOUSTIC, UNKNOWN
    }

    data class Parsed(
        val kind: Kind,
        val title: String,
        val fields: Map<String, String>
    )

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) {
            return Parsed(Kind.UNKNOWN, "Empty input", emptyMap())
        }
        val lower = text.lowercase()
        return when {
            matches(lower, GAME) ->
                Parsed(Kind.GAME, firstLine(text), fields(text, "event" to text))
            matches(lower, HEALTH) ->
                Parsed(
                    Kind.HEALTH,
                    firstLine(text),
                    fields(
                        text,
                        "medicine" to capture(lower, Regex("""(mg|ml|tablet|capsule|dose|insulin|paracetamol|ibuprofen)""")),
                        "note" to text
                    )
                )
            matches(lower, MONEY) ->
                Parsed(
                    Kind.MONEY,
                    firstLine(text),
                    fields(
                        text,
                        "amount" to capture(text, Regex("""(?:₹|rs\.?|inr|usd|\$)\s*[\d,]+(?:\.\d{2})?""", RegexOption.IGNORE_CASE)),
                        "note" to text
                    )
                )
            matches(lower, ACCESS) ->
                Parsed(
                    Kind.ACCESS,
                    firstLine(text),
                    fields(text, "secret_kind" to "local_code", "note" to text)
                )
            matches(lower, TOOLS) ->
                Parsed(
                    Kind.TOOLS,
                    firstLine(text),
                    fields(
                        text,
                        "url" to capture(text, Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)),
                        "purpose" to text
                    )
                )
            matches(lower, PLACES) || matches(lower, LOCATION) ->
                Parsed(
                    Kind.LOCATION,
                    firstLine(text),
                    fields(text, "place" to text)
                )
            matches(lower, PEOPLE) ->
                Parsed(Kind.PEOPLE, firstLine(text), fields(text, "conversation" to text))
            matches(lower, ACOUSTIC) ->
                Parsed(Kind.ACOUSTIC, firstLine(text), fields(text, "signal" to text))
            else -> Parsed(Kind.UNKNOWN, firstLine(text), fields(text, "raw" to text))
        }
    }

    private val GAME = listOf("kill", "knock", "victory", "winner winner", "bgmi", "death cam", "clutch", "clip", "recording")
    private val HEALTH = listOf("mg", "tablet", "capsule", "dose", "medicine", "insulin", "medication", "pill")
    private val MONEY = listOf("invoice", "due", "payment", "₹", "rs.", "total", "amount")
    private val ACCESS = listOf("wifi", "wi-fi", "password", "gate code", "otp", "pin ")
    private val TOOLS = listOf("http", "www.", "github", "app:", "software", "api key")
    private val PLACES = listOf("address", "street", "road", "nagar", "sector")
    private val LOCATION = listOf("parking", "level", "basement", "mall", "landmark")
    private val PEOPLE = listOf("called", "meeting", "visit", "tomorrow", "said")
    private val ACOUSTIC = listOf("alarm", "glass", "cough", "fall", "leak", "anomaly", "guardian", "smoke", "ir blast", "infrared")

    private fun matches(lower: String, keys: List<String>): Boolean =
        keys.any { lower.contains(it) }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Memory"

    private fun capture(text: String, regex: Regex): String =
        regex.find(text)?.value.orEmpty()

    private fun fields(text: String, vararg pairs: Pair<String, String>): Map<String, String> {
        val map = linkedMapOf("ingested" to text.take(400))
        pairs.forEach { (k, v) -> if (v.isNotBlank()) map[k] = v }
        return map
    }
}
